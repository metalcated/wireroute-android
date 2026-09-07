# WireRoute for Android documentation

WireRoute is a native Android client for standard WireGuard tunnels, including endpoints hosted on MikroTik RouterOS 7. It is independently maintained and does not provide a VPN subscription, hosted endpoint, or RouterOS administration service.

This is the Android documentation index. The Apple project shares the product concepts, but its Xcode builds, Keychain behavior, configuration profiles, and macOS RouterOS peer manager are not Android features.

## Documentation

- [Project overview and build commands](../README.md)
- [Import profiles, scan QR codes, and understand Apple configuration formats](MOBILECONFIG.md)
- [On-Demand VPN, automatic Wi-Fi/cellular/Ethernet profiles, and trusted networks](ON_DEMAND.md)
- [Secure RouterOS setup, firewall ordering, validation, and rollback](ROUTEROS_SETUP.md)
- [Support and troubleshooting](SUPPORT.md)
- [Privacy policy](PRIVACY.md)
- [Security reporting](SECURITY.md)
- [Legal and open-source notices](LEGAL.md)
- [Apache License 2.0](COPYING)
- [Google Play signing and release instructions](GOOGLE_PLAY_RELEASE.md)
- [Google Play listing text, graphics, and screenshots](../store-listing/README.md)

The app exposes Support, Privacy, Security, Legal, and RouterOS setup under **Settings → Help and policies**. These pages open from this repository's published `main/docs` directory and require internet access.

## First connection

1. Obtain a working WireGuard-compatible endpoint and a profile from its administrator, or prepare your own using the RouterOS guide.
2. Add a profile from a `.conf` file, a ZIP of configuration files, a configuration QR code, or the profile editor.
3. Review its addresses, endpoint, peer public key, DNS servers, and routes. Use a unique client key and address for each device.
4. Select the profile on Home and tap **Connect**. Review Android's VPN permission request if prompted.
5. Check Activity for a recent handshake and increasing transfer counters, then test the intended network destinations and DNS. A handshake alone does not establish that routing works.

Never share private keys, preshared keys, complete exports, or configuration QR codes in public issues. Treat imported profiles as sensitive credentials.

## Routing behavior

- **Split tunnel** uses the profile's specific `AllowedIPs`.
- **Full tunnel** assigns default routes for the address families configured on the profile's interface.
- Switching modes preserves specific routes in local profile metadata for restoration.
- If no specific routes are available, switching to Split prompts for network routes. Default routes are not valid Split destinations.
- With multiple peers, Full mode requires an unambiguous gateway peer. Review the profile editor if the app asks you to choose one.
- Router-side forwarding, NAT, DNS reachability, and IPv6 policy still need to support the selected mode.

These controls do not modify your router. Endpoints, keys, addresses, routes, and DNS values come from profiles or user input, not deployment-specific application constants.

## DNS Protection

Open a profile, choose **DNS Protection**, select a mode, and tap **Save**:

- **Profile DNS** uses the servers saved in the profile. The page shows whether each server matches a VPN route and offers **Edit Profile DNS…**.
- **Encrypted DNS** uses DNS-over-HTTPS with Cloudflare, Cloudflare Security, Cloudflare Family, AdGuard DNS, AdGuard Family, Quad9 Secure, Google Public DNS, or a custom resolver.
- A custom resolver needs an HTTPS URL; optional bootstrap addresses let the app reach the resolver without first resolving its hostname.
- Encrypted DNS replaces the profile's DNS servers while connected. Internal domain names may stop resolving, and filtering depends on the selected provider.
- Reconnect an active profile after saving DNS changes. If the app asks for a restart to change the DNS-capable backend, follow that message before connecting.

DNS preferences are local per-profile metadata. Standard configuration exports are not a backup of all WireRoute application settings; review DNS Protection after importing a profile on another device.

## Activity and diagnostics

Activity shows transfer rates, session totals, last-handshake information, and recent connections for the selected profile. Its SQLite history stays on the device and does not store packet contents.

Choose 1, 7, or 30 days under **Settings → Activity retention**. The Activity menu can clear completed history after confirmation. **Settings → View log** opens diagnostics; share only relevant, sanitized lines.

## Appearance and endpoint map

Choose **Nordic Blue** or **System** under **Settings → Appearance**.

**Locate endpoint** uses the selected profile's public endpoint IP, not the phone's GPS. After confirmation, it requests an approximate location from `ipwho.is`; OpenFreeMap supplies map resources. Private, local, reserved, and documentation-only addresses cannot be located publicly. Results are approximate and kept in memory for the app session.

## Building and releases

Use the Android Gradle project from the repository root, not Xcode or Swift Package Manager. The permanent application ID is `com.metalcated.wireroute`; debug builds append `.debug`. Internal upstream namespaces remain `com.wireguard.android`.

See the [root build instructions](../README.md#building) and [Google Play release guide](GOOGLE_PLAY_RELEASE.md). No Apple signing identifiers, Developer ID certificates, or notarization steps apply to this Android build.

## Platform and license boundaries

Android uses the repository's [Apache License 2.0](COPYING), not the Apple project's MIT license. Existing upstream attribution remains intact; “WireGuard” names the compatible protocol and upstream components.

The [Apple configuration reference](MOBILECONFIG.md#archived-apple-reference) is retained for context only. Its upstream Apple bundle identifiers and example keys must not be treated as WireRoute Android configuration or production credentials.
