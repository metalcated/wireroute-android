# WireRoute for Android

WireRoute is a native Android client for standard WireGuard tunnels, including endpoints hosted on MikroTik RouterOS 7. Its Blue Nordic interface and workflows mirror the WireRoute applications for iOS, macOS, and Windows while tunnel execution remains on the proven WireGuard Android engine.

WireRoute is based on the official [WireGuard Android](https://git.zx2c4.com/wireguard-android/) project. It opportunistically uses the in-kernel WireGuard implementation when available and otherwise uses the non-root userspace implementation. WireRoute is independently maintained and does not provide a VPN subscription or hosted endpoint.

Profile addresses, endpoints, keys, DNS servers, routes, and RouterOS values come from imported profiles or user input; deployment-specific values are not hard-coded in the app.

## Current capabilities

- Create, edit, import, export, and scan standard WireGuard profiles.
- Select Split Tunnel or Full Tunnel routing per profile while preserving saved specific routes.
- Choose profile DNS or per-profile encrypted DNS-over-HTTPS protection, with built-in resolver presets and configurable bootstrap addresses.
- Connect and disconnect profiles through the Android VPN service.
- Review live transfer rates, local connection history, and diagnostics.
- Preview the approximate location of a configured public endpoint on an OpenFreeMap map after an explicit privacy confirmation.
- Choose the fixed Blue Nordic appearance or Android System colors.

## Endpoint map and Android location

**Locate endpoint** geolocates the selected profile's resolved public endpoint IP address. It does not read the phone's GPS position and does not require Android location access. Private, local, reserved, and documentation-only endpoint addresses cannot be placed on a public map.

After confirmation, WireRoute sends only the resolved public endpoint IP address to `ipwho.is` over HTTPS. The approximate result remains in memory for the current app session. OpenFreeMap supplies the map tiles, glyphs, and symbols.

## RouterOS

WireRoute works with standard WireGuard profiles generated for RouterOS 7. Android WireRoute does not connect to the RouterOS REST API or modify router configuration; RouterOS remains under the administrator's control.

See [Secure RouterOS WireGuard setup](docs/ROUTEROS_SETUP.md) for a guarded example covering the interface, client peer, firewall, NAT, and Android profile. Review an existing router before making changes because interface names, subnets, firewall order, and NAT policy are deployment-specific.

## Documentation

- [Android documentation and getting started](docs/README.md)
- [Profile import, QR codes, and Apple configuration compatibility](docs/MOBILECONFIG.md)
- [On-Demand VPN, automatic profile switching, and trusted Wi-Fi rules](docs/ON_DEMAND.md)
- [Secure RouterOS WireGuard setup](docs/ROUTEROS_SETUP.md)
- [Support and contact](docs/SUPPORT.md)
- [Privacy policy](docs/PRIVACY.md)
- [Security reporting](docs/SECURITY.md)
- [Legal and open-source notices](docs/LEGAL.md)
- [Apache License 2.0](docs/COPYING)
- [Google Play signing and releases](docs/GOOGLE_PLAY_RELEASE.md)
- [Google Play listing assets and upload guide](store-listing/README.md)

## Building

The Android application ID is `com.metalcated.wireroute`; debug builds use `com.metalcated.wireroute.debug`. The internal source/library namespace remains `com.wireguard.android` for compatibility with the VPN engine. Native tunnel cache paths use the application ID.

Clone the repository with its submodules and build with the checked-in Gradle wrapper:

```sh
git clone --recurse-submodules https://github.com/metalcated/wireroute-android.git
cd wireroute-android
./gradlew assembleRelease
```

macOS users may need [`flock(1)`](https://github.com/discoteq/flock).

Run the Android checks with:

```sh
./gradlew testDebugUnitTest lintDebug
```

For a signed Android App Bundle and the first upload to Google Play, follow [Google Play releases](docs/GOOGLE_PLAY_RELEASE.md).

## Upstream tunnel library

The inherited tunnel library remains compatible with the upstream WireGuard Android API. Its Maven coordinates and Java/Kotlin package names intentionally retain `com.wireguard.android` for source and binary compatibility:

```kotlin
implementation("com.wireguard.android:tunnel:$wireguardTunnelVersion")
```

See the upstream [Maven artifact](https://search.maven.org/artifact/com.wireguard.android/tunnel) and [class library documentation](https://javadoc.io/doc/com.wireguard.android/tunnel) for embedding details.

## License

This repository is licensed under the [Apache License 2.0](docs/COPYING). WireRoute-specific changes and inherited upstream files retain their applicable copyright and license notices.
