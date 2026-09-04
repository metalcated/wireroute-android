/* SPDX-License-Identifier: Apache-2.0 */

package dnstun

import (
	"bytes"
	"encoding/binary"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestResolvePostsDNSMessage(t *testing.T) {
	query := []byte{
		0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00,
	}
	response := append([]byte(nil), query...)
	response[2] |= 0x80
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost {
			t.Fatalf("method = %s, want POST", request.Method)
		}
		if request.Header.Get("Accept") != "application/dns-message" ||
			request.Header.Get("Content-Type") != "application/dns-message" {
			t.Fatal("missing RFC 8484 media type headers")
		}
		writer.Header().Set("Content-Type", "application/dns-message")
		_, _ = writer.Write(response)
	}))
	defer server.Close()

	device := &Device{client: server.Client(), resolverURL: server.URL}
	actual, err := device.resolve(query)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, response) {
		t.Fatalf("response = %x, want %x", actual, response)
	}
}

func TestResolveRejectsMismatchedTransaction(t *testing.T) {
	query := []byte{
		0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
	}
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/dns-message")
		_, _ = writer.Write([]byte{
			0xab, 0xcd, 0x81, 0x80, 0x00, 0x01, 0x00, 0x00,
			0x00, 0x00, 0x00, 0x00,
		})
	}))
	defer server.Close()

	device := &Device{client: server.Client(), resolverURL: server.URL}
	if _, err := device.resolve(query); err == nil {
		t.Fatal("mismatched transaction ID was accepted")
	}
}

func TestWrapRejectsUnsafeResolverConfiguration(t *testing.T) {
	tests := []Config{
		{ResolverURL: "http://dns.example/dns-query", BootstrapAddresses: []string{"1.1.1.1"}, VirtualAddress: "10.64.0.53"},
		{ResolverURL: "https://user@dns.example/dns-query", BootstrapAddresses: []string{"1.1.1.1"}, VirtualAddress: "10.64.0.53"},
		{ResolverURL: "https://dns.example:0/dns-query", BootstrapAddresses: []string{"1.1.1.1"}, VirtualAddress: "10.64.0.53"},
		{ResolverURL: "https://dns.example/dns-query", BootstrapAddresses: []string{"not-an-address"}, VirtualAddress: "10.64.0.53"},
		{ResolverURL: "https://dns.example/dns-query", BootstrapAddresses: []string{"1.1.1.1"}, VirtualAddress: "not-an-address"},
	}
	for _, config := range tests {
		if _, err := Wrap(nil, config); err == nil {
			t.Fatalf("unsafe configuration was accepted: %+v", config)
		}
	}
}

func TestMakeUDPResponseSwapsEndpoints(t *testing.T) {
	request := make([]byte, 20+8+12)
	request[0] = 0x45
	request[8] = 64
	request[9] = 17
	copy(request[12:16], []byte{192, 0, 2, 10})
	copy(request[16:20], []byte{10, 64, 0, 53})
	binary.BigEndian.PutUint16(request[20:22], 42424)
	binary.BigEndian.PutUint16(request[22:24], dnsPort)
	binary.BigEndian.PutUint16(request[24:26], uint16(len(request)-20))
	dnsResponse := []byte{
		0x12, 0x34, 0x81, 0x80, 0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
	}

	response := makeUDPResponse(request, 20, dnsResponse)
	if !bytes.Equal(response[12:16], request[16:20]) || !bytes.Equal(response[16:20], request[12:16]) {
		t.Fatal("IP endpoints were not swapped")
	}
	if binary.BigEndian.Uint16(response[20:22]) != dnsPort ||
		binary.BigEndian.Uint16(response[22:24]) != 42424 {
		t.Fatal("UDP ports were not swapped")
	}
	if checksum(response[:20]) != 0 {
		t.Fatal("invalid IPv4 header checksum")
	}
	if !bytes.Equal(response[28:], dnsResponse) {
		t.Fatal("DNS response payload changed")
	}
}

func TestServerFailureResponse(t *testing.T) {
	query := []byte{
		0x12, 0x34, 0x01, 0x20, 0x00, 0x01, 0x00, 0x02,
		0x00, 0x03, 0x00, 0x04,
	}
	response := serverFailureResponse(query)
	if response[2]&0x80 == 0 || response[3]&0x0f != 2 {
		t.Fatalf("flags = %02x%02x, want response with SERVFAIL", response[2], response[3])
	}
	if !bytes.Equal(response[6:10], []byte{0, 0, 0, 0}) {
		t.Fatal("SERVFAIL retained answer or authority counts")
	}
	if !bytes.Equal(query[6:10], []byte{0, 2, 0, 3}) {
		t.Fatal("input query was modified")
	}
}
