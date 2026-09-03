# WireRoute Privacy Policy

Effective date: September 2, 2026

This policy describes the data practices of WireRoute for Android.

## Summary

WireRoute does not require an account and does not operate a developer-controlled VPN, analytics, advertising, telemetry, or crash-reporting service. The project does not collect or sell personal data, track users, or use third-party advertising or analytics SDKs.

WireRoute does not collect, sell, use for secondary purposes, or disclose VPN-derived user data. Profiles, activity history, and diagnostics remain on the device unless you explicitly export or share them.

## Data stored on the device

WireRoute stores information required for features you choose:

- Tunnel profiles, endpoints, addresses, routes, DNS servers, keys, and routing preferences
- Local per-profile connection sessions, transfer counters, rates, and last-handshake times
- Local diagnostic logs
- Appearance, retention, and selected-profile settings

Activity history does not contain packet contents. Retention can be set to 1, 7, or 30 days. Diagnostic logs can contain interface names, endpoint hostnames or addresses, public keys, handshake state, and error details.

## Camera and location

Camera access is used only when you choose to scan a WireGuard configuration QR code. The QR code is processed on the device and is not uploaded to the WireRoute project.

The endpoint map does not read the Android device's GPS position. **Locate endpoint** operates on the public endpoint configured in the selected profile, so enabling Android location access does not affect it.

## Network connections

WireRoute makes network connections only to provide app functionality:

- VPN traffic is sent to the endpoint configured in the selected profile.
- DNS requests may be sent to DNS servers configured in that profile.
- OpenFreeMap supplies vector tiles, glyphs, and symbols for the map. Ordinary HTTPS requests expose connection metadata and requested map resources to that provider.
- If you tap **Locate endpoint** and confirm the disclosure, WireRoute sends the selected profile's resolved public endpoint IP address to `ipwho.is` over HTTPS. The response may include an approximate city, region, country, latitude, and longitude. Private and reserved addresses are rejected before lookup. The result remains in memory for the current app session.

Those services and the VPN or RouterOS systems configured by you are operated independently and may process traffic under their own policies. IP geolocation is approximate and must not be treated as a physical address.

## Exports and support requests

WireRoute exports data only when requested and after you choose a destination. Profile exports can contain private keys and other sensitive network information. Protect them and delete them when no longer needed.

Information voluntarily submitted through GitHub is handled by GitHub. Never include private keys, passwords, complete profiles, or other secrets in a support request.

## Your choices and data removal

- Delete profiles you no longer need.
- Choose the activity retention period and clear completed history.
- Delete exported profile and log files from their destination.
- Uninstall WireRoute to remove its ordinary Android application data according to Android platform behavior.

WireRoute has no remote user account or developer-controlled data store, so there is no remote account data to request or delete.

## Changes and contact

Material policy changes will be published in this repository with an updated effective date. For privacy questions, use [SUPPORT.md](SUPPORT.md) without including secrets.
