# Secure RouterOS WireGuard Setup

This guide provides a guarded example for a RouterOS 7 WireGuard server used by WireRoute for Android. It is not a script to paste into an unknown router.

Review the existing interface lists, addresses, routes, firewall, NAT, DNS, and WireGuard configuration before changing anything. Rule order matters. Work from a trusted management connection, use RouterOS Safe Mode when practical, keep a recovery path open, and make a protected backup according to your policy.

> **Protect secrets:** Never commit or share a WireGuard private key, preshared key, RouterOS password, recovery export, complete client configuration, or QR code. Generate and retain the client private key on the client device.

## Example plan

Every value below is documentation-only. Replace it with values that do not overlap an existing LAN, VPN, peer, or routed network.

| Purpose | Example value |
| --- | --- |
| WAN interface list | `WAN` |
| Protected LAN | `192.168.88.0/24` |
| WireGuard interface | `wg-remote` |
| WireGuard subnet | `10.200.0.0/24` |
| Router tunnel address | `10.200.0.1/24` |
| Android client address | `10.200.0.2/32` |
| WireGuard UDP port | `51820` |
| Public endpoint | `vpn.example.net:51820` |

The public endpoint must resolve to the router's public address. If another gateway performs NAT in front of RouterOS, forward only the selected UDP port to RouterOS. Do not expose RouterOS management services to the internet.

## 1. Inspect the router first

These commands are read-only:

```routeros
/system resource print
/system package print
/interface list member print detail
/ip address print detail
/ip route print detail
/ip firewall filter print stats detail
/ip firewall nat print stats detail
/ip service print detail
/interface wireguard print detail
/interface wireguard peers print detail
```

Confirm that `WAN` contains the real internet-facing interface. Do not create duplicate established/related, invalid-drop, final-drop, or masquerade rules when the router already has suitable rules.

## 2. Create the WireGuard interface

RouterOS generates the interface key pair when `private-key` is omitted:

```routeros
/interface wireguard
add name=wg-remote listen-port=51820 mtu=1420 comment="WireRoute remote access"

/ip address
add address=10.200.0.1/24 interface=wg-remote comment="WireRoute tunnel subnet"

/interface wireguard print detail where name="wg-remote"
```

Copy only the interface `public-key` into the Android profile. The RouterOS interface private key stays on the router.

## 3. Add the Android client peer

Generate the Android client's key pair in WireRoute, then add only its public key to RouterOS:

```routeros
/interface wireguard peers
add interface=wg-remote name="android-01" \
    public-key="<ANDROID_PUBLIC_KEY>" \
    allowed-address=10.200.0.2/32 \
    comment="WireRoute Android"
```

Use a unique address for every client. Peer `allowed-address` values may not overlap on the same WireGuard interface. For this road-warrior example, the RouterOS peer contains only the address owned by that client; do not put the protected LAN or `0.0.0.0/0` in the RouterOS peer entry.

## 4. Permit the WireGuard handshake

Place the UDP accept rule after the early established/related accept and invalid drop, but before the final WAN input drop:

```routeros
/ip firewall filter
add chain=input action=accept protocol=udp dst-port=51820 \
    in-interface-list=WAN \
    comment="WireRoute: allow WireGuard handshake"
```

Move the rule to the correct position for the router's existing policy, then print the chain again to verify its effective order. Do not rely on a rule number copied from documentation.

## 5. Permit only intended forwarded traffic

Keep the WireGuard interface out of a broad trusted LAN interface list. Use narrow rules so permitted destinations remain visible:

```routeros
/ip firewall address-list
add list=wireroute-protected-lans address=192.168.88.0/24 \
    comment="WireRoute: protected LAN"

/ip firewall filter
add chain=forward action=accept in-interface=wg-remote \
    src-address=10.200.0.0/24 dst-address-list=wireroute-protected-lans \
    comment="WireRoute: allow Split Tunnel destinations"

add chain=forward action=accept in-interface=wg-remote \
    src-address=10.200.0.0/24 out-interface-list=WAN \
    comment="WireRoute: allow Full Tunnel internet"

add chain=forward action=drop in-interface=wg-remote \
    src-address=10.200.0.0/24 \
    comment="WireRoute: drop other client forwarding"
```

Place these after the forward chain's established/related accept and invalid drop, but before broad forward drops. Omit the WAN rule if Full Tunnel must not be available.

For Full Tunnel IPv4 internet access, first verify whether an existing source-NAT rule already masquerades traffic leaving the WAN. If it does not, add one source-specific rule:

```routeros
/ip firewall nat
add chain=srcnat action=masquerade src-address=10.200.0.0/24 \
    out-interface-list=WAN \
    comment="WireRoute: Full Tunnel IPv4 NAT"
```

NAT is not required merely to reach correctly routed internal networks.

## 6. Configure WireRoute for Android

A Split Tunnel profile using the example values is:

```ini
[Interface]
PrivateKey = <ANDROID_PRIVATE_KEY_CREATED_ON_THIS_DEVICE>
Address = 10.200.0.2/32
DNS = <REACHABLE_DNS_SERVER>

[Peer]
PublicKey = <ROUTER_WIREGUARD_PUBLIC_KEY>
Endpoint = vpn.example.net:51820
AllowedIPs = 192.168.88.0/24, 10.200.0.0/24
PersistentKeepalive = 25
```

Import the file or scan a QR code displayed through a trusted channel. Delete temporary files and QR images after import.

- **Split Tunnel** uses the profile's specific `AllowedIPs`.
- **Full Tunnel** changes the effective client routes to the supported default route while preserving the profile's specific routes for later restoration.
- A keepalive of 25 seconds is commonly useful for a phone behind NAT.

Use a DNS server reachable through the selected routes. If RouterOS answers DNS for VPN clients, deliberately allow TCP and UDP port 53 from the WireGuard subnet while keeping recursive DNS inaccessible from the WAN.

Do not add `::/0` until the router has a deliberate IPv6 tunnel prefix, forwarding policy, and IPv6 firewall rules.

## Endpoint map troubleshooting

WireRoute's **Locate endpoint** feature needs a public `Endpoint` in the selected profile, such as `vpn.example.net:51820`. It does not use the Android device's GPS location and does not require Android location access.

The map cannot locate private, local, reserved, or documentation-only addresses. DNS names must resolve to a public IP address. IP geolocation is approximate and must not be treated as a physical address.

## Rule-order checklist

Before applying changes, review the existing policy and keep a working management connection or local recovery console available. Use RouterOS Safe Mode for interactive changes where appropriate.

- In the input chain, keep established/related handling and invalid-packet drops intact. Place the narrow WireGuard UDP allowance before the applicable WAN drop.
- In the forward chain, place the intended VPN destination allowances before the VPN-client drop and any broader drop that would otherwise match first.
- Check for earlier broad accept rules that would bypass the intended restrictions. Do not add the VPN interface to a trusted LAN list merely to make connectivity work.
- Review IPv4 and IPv6 separately. IPv4 firewall and NAT rules do not establish an IPv6 policy.

## Validation

After applying reviewed changes:

1. Print the WireGuard interface, peer, firewall, and NAT state again.
2. Import the profile and connect from a network outside the protected LAN.
3. Confirm a recent handshake and increasing transfer counters on both Android and RouterOS.
4. In Split Tunnel mode, verify only the intended protected destinations use the VPN.
5. If Full Tunnel is allowed, verify the public egress address, DNS behavior, and IPv6 policy.
6. Confirm RouterOS management services remain unreachable from untrusted networks.

Inspect detailed configuration output privately; it can contain credentials or private keys. Share sanitized diagnostics only.

## Roll back safely

Record the original configuration and the exact rules you change before applying anything. If validation fails, use the retained management connection or recovery console to disable only the newly added, identified rules and restore the specific values you changed. Recheck rule order and management access afterward. Do not reset the router or remove broad groups of existing firewall rules to troubleshoot a VPN connection.

Disconnect the Android profile while correcting the router configuration. Removing an Android profile does not undo router-side changes, and changing Split/Full Tunnel does not change the router firewall.

## Official references

- [MikroTik WireGuard documentation](https://help.mikrotik.com/docs/spaces/ROS/pages/69664792/WireGuard)
- [MikroTik advanced firewall guide](https://help.mikrotik.com/docs/spaces/ROS/pages/328513/Building+Advanced+Firewall)
- [MikroTik configuration management and Safe Mode](https://help.mikrotik.com/docs/spaces/ROS/pages/328155/Configuration+Management)

Return to the [Android documentation index](README.md) or [support guide](SUPPORT.md).
