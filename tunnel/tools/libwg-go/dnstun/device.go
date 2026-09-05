/* SPDX-License-Identifier: Apache-2.0 */

// Package dnstun intercepts DNS packets addressed to WireRoute's virtual resolver and
// answers them with RFC 8484 DNS-over-HTTPS responses before WireGuard selects a peer.
package dnstun

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/tun"
)

const (
	dnsPort                   = 53
	maximumBootstrapAddresses = 8
	maximumDNSMessage         = 65507
	maximumTCPDNSMessage      = 65535 - 20 - 20 - 2
	queryTimeout              = 12 * time.Second
	staleTCPState             = 30 * time.Second
)

// Config describes one encrypted resolver attached to a WireRoute tunnel.
type Config struct {
	ResolverURL        string
	BootstrapAddresses []string
	VirtualAddress     string
	Logf               func(format string, args ...interface{})
}

type tcpKey struct {
	address [4]byte
	port    uint16
}

type tcpState struct {
	buffer     []byte
	clientNext uint32
	serverNext uint32
	updatedAt  time.Time
}

// Device is a transparent tun.Device wrapper. Non-DNS packets pass through unchanged.
type Device struct {
	tun.Device
	client      *http.Client
	closed      chan struct{}
	closeOnce   sync.Once
	logf        func(format string, args ...interface{})
	nextAddress atomic.Uint32
	nextTCPSeq  atomic.Uint32
	querySlots  chan struct{}
	resolverURL string
	tcpMu       sync.Mutex
	tcpStates   map[tcpKey]*tcpState
	transport   *http.Transport
	virtualIP   [4]byte
	writeMu     sync.Mutex
}

// Wrap creates an in-tunnel encrypted DNS endpoint. All bootstrap addresses are literal IPs.
func Wrap(device tun.Device, config Config) (*Device, error) {
	// JNI releases its UTF-8 buffers when wgTurnOn returns. url.Parse retains
	// substrings (including the TLS hostname and port), so own the URL before
	// asynchronous DNS requests can outlive that call.
	resolver, err := url.Parse(strings.Clone(config.ResolverURL))
	if err != nil || !strings.EqualFold(resolver.Scheme, "https") || resolver.Hostname() == "" ||
		resolver.User != nil || resolver.Fragment != "" {
		return nil, errors.New("encrypted DNS requires a valid HTTPS resolver URL")
	}
	virtual := net.ParseIP(config.VirtualAddress).To4()
	if virtual == nil {
		return nil, errors.New("encrypted DNS requires an IPv4 virtual resolver address")
	}
	addresses := make([]net.IP, 0, len(config.BootstrapAddresses))
	for _, raw := range config.BootstrapAddresses {
		if len(addresses) == maximumBootstrapAddresses {
			break
		}
		address := net.ParseIP(strings.TrimSpace(raw))
		if address == nil {
			return nil, fmt.Errorf("invalid encrypted DNS bootstrap address %q", raw)
		}
		addresses = append(addresses, address)
	}
	if len(addresses) == 0 {
		return nil, errors.New("encrypted DNS requires at least one resolved bootstrap address")
	}

	port := resolver.Port()
	if port == "" {
		port = "443"
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		return nil, errors.New("encrypted DNS resolver URL has an invalid port")
	}
	proxy := &Device{
		Device:      device,
		closed:      make(chan struct{}),
		logf:        config.Logf,
		querySlots:  make(chan struct{}, 32),
		resolverURL: resolver.String(),
		tcpStates:   make(map[tcpKey]*tcpState),
	}
	copy(proxy.virtualIP[:], virtual)
	dialer := &net.Dialer{Timeout: queryTimeout, KeepAlive: 30 * time.Second}
	transport := &http.Transport{
		Proxy:               nil,
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        8,
		MaxIdleConnsPerHost: 8,
		IdleConnTimeout:     45 * time.Second,
		TLSClientConfig: &tls.Config{
			MinVersion: tls.VersionTLS12,
			ServerName: resolver.Hostname(),
		},
	}
	transport.DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
		start := int(proxy.nextAddress.Add(1)-1) % len(addresses)
		var lastErr error
		for attempt := range addresses {
			address := addresses[(start+attempt)%len(addresses)]
			connection, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(address.String(), port))
			if err == nil {
				return connection, nil
			}
			lastErr = err
		}
		return nil, lastErr
	}
	proxy.transport = transport
	proxy.client = &http.Client{
		Transport: transport,
		Timeout:   queryTimeout,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return errors.New("encrypted DNS resolver redirects are not allowed")
		},
	}
	proxy.nextTCPSeq.Store(uint32(time.Now().UnixNano()))
	return proxy, nil
}

// Read removes virtual-resolver packets from WireGuard's outbound stream and resolves them
// asynchronously so the HTTPS connection can itself continue through the tunnel if routed there.
func (device *Device) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	for {
		count, err := device.Device.Read(bufs, sizes, offset)
		if err != nil {
			return count, err
		}
		output := 0
		for index := 0; index < count; index++ {
			packet := bufs[index][offset : offset+sizes[index]]
			if device.intercept(packet) {
				continue
			}
			if output != index {
				sizes[output] = copy(bufs[output][offset:], packet)
			} else {
				sizes[output] = sizes[index]
			}
			output++
		}
		if output > 0 {
			return output, nil
		}
	}
}

// Write serializes WireGuard traffic with locally generated DNS responses.
func (device *Device) Write(bufs [][]byte, offset int) (int, error) {
	device.writeMu.Lock()
	defer device.writeMu.Unlock()
	return device.Device.Write(bufs, offset)
}

func (device *Device) Close() error {
	device.closeOnce.Do(func() {
		close(device.closed)
		device.transport.CloseIdleConnections()
	})
	return device.Device.Close()
}

func (device *Device) intercept(packet []byte) bool {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return false
	}
	headerLength := int(packet[0]&0x0f) * 4
	if headerLength < 20 || len(packet) < headerLength || !bytes.Equal(packet[16:20], device.virtualIP[:]) {
		return false
	}
	// Do not attempt to parse fragmented DNS requests.
	if binary.BigEndian.Uint16(packet[6:8])&0x3fff != 0 {
		return false
	}
	switch packet[9] {
	case 17:
		return device.interceptUDP(packet, headerLength)
	case 6:
		return device.interceptTCP(packet, headerLength)
	default:
		return false
	}
}

func (device *Device) interceptUDP(packet []byte, ipHeaderLength int) bool {
	if len(packet) < ipHeaderLength+8 || binary.BigEndian.Uint16(packet[ipHeaderLength+2:ipHeaderLength+4]) != dnsPort {
		return false
	}
	udpLength := int(binary.BigEndian.Uint16(packet[ipHeaderLength+4 : ipHeaderLength+6]))
	if udpLength < 20 || ipHeaderLength+udpLength > len(packet) {
		return true
	}
	requestPacket := append([]byte(nil), packet[:ipHeaderLength+udpLength]...)
	query := append([]byte(nil), requestPacket[ipHeaderLength+8:]...)
	device.resolveAsync(query, func(response []byte) {
		device.inject(makeUDPResponse(requestPacket, ipHeaderLength, response))
	})
	return true
}

func (device *Device) interceptTCP(packet []byte, ipHeaderLength int) bool {
	if len(packet) < ipHeaderLength+20 || binary.BigEndian.Uint16(packet[ipHeaderLength+2:ipHeaderLength+4]) != dnsPort {
		return false
	}
	tcpHeaderLength := int(packet[ipHeaderLength+12]>>4) * 4
	if tcpHeaderLength < 20 || len(packet) < ipHeaderLength+tcpHeaderLength {
		return true
	}
	key := tcpKey{port: binary.BigEndian.Uint16(packet[ipHeaderLength : ipHeaderLength+2])}
	copy(key.address[:], packet[12:16])
	flags := packet[ipHeaderLength+13]
	clientSequence := binary.BigEndian.Uint32(packet[ipHeaderLength+4 : ipHeaderLength+8])
	payload := packet[ipHeaderLength+tcpHeaderLength:]
	now := time.Now()

	device.tcpMu.Lock()
	for candidate, state := range device.tcpStates {
		if now.Sub(state.updatedAt) > staleTCPState {
			delete(device.tcpStates, candidate)
		}
	}
	state := device.tcpStates[key]
	if flags&0x02 != 0 { // SYN
		initialSequence := device.nextTCPSeq.Add(65537)
		state = &tcpState{clientNext: clientSequence + 1, serverNext: initialSequence + 1, updatedAt: now}
		device.tcpStates[key] = state
		device.tcpMu.Unlock()
		device.inject(makeTCPResponse(packet, ipHeaderLength, initialSequence, state.clientNext, 0x12, nil)) // SYN + ACK
		return true
	}
	if state == nil {
		device.tcpMu.Unlock()
		device.inject(makeTCPResponse(packet, ipHeaderLength, 0, clientSequence+uint32(len(payload)), 0x04, nil)) // RST
		return true
	}
	state.updatedAt = now
	if len(payload) > 0 && clientSequence == state.clientNext {
		state.buffer = append(state.buffer, payload...)
		state.clientNext += uint32(len(payload))
	}
	serverSequence := state.serverNext
	clientNext := state.clientNext
	if flags&0x01 != 0 { // FIN
		clientNext++
		delete(device.tcpStates, key)
		device.tcpMu.Unlock()
		device.inject(makeTCPResponse(packet, ipHeaderLength, serverSequence, clientNext, 0x11, nil)) // FIN + ACK
		return true
	}
	if len(state.buffer) < 2 {
		device.tcpMu.Unlock()
		if len(payload) > 0 {
			device.inject(makeTCPResponse(packet, ipHeaderLength, serverSequence, clientNext, 0x10, nil))
		}
		return true
	}
	queryLength := int(binary.BigEndian.Uint16(state.buffer[:2]))
	if queryLength < 12 || queryLength > maximumDNSMessage || len(state.buffer) < queryLength+2 {
		device.tcpMu.Unlock()
		return true
	}
	query := append([]byte(nil), state.buffer[2:queryLength+2]...)
	state.buffer = append([]byte(nil), state.buffer[queryLength+2:]...)
	device.tcpMu.Unlock()
	device.inject(makeTCPResponse(packet, ipHeaderLength, serverSequence, clientNext, 0x10, nil))
	requestPacket := append([]byte(nil), packet...)
	device.resolveAsync(query, func(response []byte) {
		if len(response) > maximumTCPDNSMessage {
			response = truncatedResponse(query)
		}
		framed := make([]byte, len(response)+2)
		binary.BigEndian.PutUint16(framed[:2], uint16(len(response)))
		copy(framed[2:], response)
		device.tcpMu.Lock()
		active := device.tcpStates[key]
		if active == nil {
			device.tcpMu.Unlock()
			return
		}
		sequence := active.serverNext
		acknowledgement := active.clientNext
		active.serverNext += uint32(len(framed))
		active.updatedAt = time.Now()
		device.tcpMu.Unlock()
		device.inject(makeTCPResponse(requestPacket, ipHeaderLength, sequence, acknowledgement, 0x18, framed)) // PSH + ACK
	})
	return true
}

func (device *Device) resolveAsync(query []byte, deliver func([]byte)) {
	select {
	case device.querySlots <- struct{}{}:
		go func() {
			defer func() { <-device.querySlots }()
			response, err := device.resolve(query)
			if err != nil {
				if device.logf != nil {
					device.logf("Encrypted DNS query failed: %v", err)
				}
				response = serverFailureResponse(query)
			}
			deliver(response)
		}()
	default:
		deliver(serverFailureResponse(query))
	}
}

func (device *Device) resolve(query []byte) ([]byte, error) {
	if len(query) < 12 || len(query) > maximumDNSMessage {
		return nil, errors.New("invalid DNS query length")
	}
	ctx, cancel := context.WithTimeout(context.Background(), queryTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		device.resolverURL,
		bytes.NewReader(query),
	)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("User-Agent", "WireRoute for Android")
	response, err := device.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("encrypted DNS resolver returned %s", response.Status)
	}
	mediaType, _, mediaTypeError := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if mediaTypeError != nil || !strings.EqualFold(mediaType, "application/dns-message") {
		return nil, errors.New("encrypted DNS resolver returned an invalid media type")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maximumDNSMessage+1))
	if err != nil {
		return nil, err
	}
	if len(body) < 12 || len(body) > maximumDNSMessage {
		return nil, errors.New("encrypted DNS resolver returned an invalid message length")
	}
	if body[0] != query[0] || body[1] != query[1] {
		return nil, errors.New("encrypted DNS resolver returned a mismatched query ID")
	}
	return body, nil
}

func (device *Device) inject(packet []byte) {
	if len(packet) == 0 {
		return
	}
	select {
	case <-device.closed:
		return
	default:
	}
	device.writeMu.Lock()
	_, err := device.Device.Write([][]byte{packet}, 0)
	device.writeMu.Unlock()
	if err != nil && device.logf != nil {
		device.logf("Encrypted DNS response failed: %v", err)
	}
}

func makeUDPResponse(request []byte, ipHeaderLength int, dnsResponse []byte) []byte {
	if len(dnsResponse) > maximumDNSMessage {
		dnsResponse = truncatedResponse(request[ipHeaderLength+8:])
	}
	response := make([]byte, 20+8+len(dnsResponse))
	response[0] = 0x45
	response[1] = request[1]
	binary.BigEndian.PutUint16(response[2:4], uint16(len(response)))
	copy(response[4:8], request[4:8])
	response[8] = 64
	response[9] = 17
	copy(response[12:16], request[16:20])
	copy(response[16:20], request[12:16])
	binary.BigEndian.PutUint16(response[20:22], dnsPort)
	copy(response[22:24], request[ipHeaderLength:ipHeaderLength+2])
	binary.BigEndian.PutUint16(response[24:26], uint16(8+len(dnsResponse)))
	copy(response[28:], dnsResponse)
	binary.BigEndian.PutUint16(response[10:12], checksum(response[:20]))
	return response
}

func makeTCPResponse(request []byte, ipHeaderLength int, sequence, acknowledgement uint32, flags byte, payload []byte) []byte {
	const maximumTCPPayload = 65535 - 20 - 20
	if len(payload) > maximumTCPPayload {
		payload = payload[:maximumTCPPayload]
	}
	response := make([]byte, 20+20+len(payload))
	response[0] = 0x45
	response[1] = request[1]
	binary.BigEndian.PutUint16(response[2:4], uint16(len(response)))
	copy(response[4:8], request[4:8])
	response[8] = 64
	response[9] = 6
	copy(response[12:16], request[16:20])
	copy(response[16:20], request[12:16])
	binary.BigEndian.PutUint16(response[20:22], dnsPort)
	copy(response[22:24], request[ipHeaderLength:ipHeaderLength+2])
	binary.BigEndian.PutUint32(response[24:28], sequence)
	binary.BigEndian.PutUint32(response[28:32], acknowledgement)
	response[32] = 5 << 4
	response[33] = flags
	binary.BigEndian.PutUint16(response[34:36], 65535)
	copy(response[40:], payload)
	binary.BigEndian.PutUint16(response[36:38], tcpChecksum(response[12:16], response[16:20], response[20:]))
	binary.BigEndian.PutUint16(response[10:12], checksum(response[:20]))
	return response
}

func serverFailureResponse(query []byte) []byte {
	if len(query) < 12 {
		return nil
	}
	response := append([]byte(nil), query...)
	response[2] |= 0x80
	response[3] = (response[3] & 0xf0) | 0x82
	for index := 6; index < 10; index++ {
		response[index] = 0
	}
	return response
}

func truncatedResponse(query []byte) []byte {
	response := serverFailureResponse(query)
	if len(response) >= 12 {
		response[2] |= 0x02
		response[3] &= 0xf0
	}
	return response
}

func checksum(data []byte) uint16 {
	var sum uint32
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) == 1 {
		sum += uint32(data[0]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func tcpChecksum(source, destination, segment []byte) uint16 {
	pseudoHeader := make([]byte, 12+len(segment))
	copy(pseudoHeader[0:4], source)
	copy(pseudoHeader[4:8], destination)
	pseudoHeader[9] = 6
	binary.BigEndian.PutUint16(pseudoHeader[10:12], uint16(len(segment)))
	copy(pseudoHeader[12:], segment)
	return checksum(pseudoHeader)
}
