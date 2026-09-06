# On-Demand VPN on Android

Open **Profiles → a profile → On-Demand**. Choose Wi-Fi, cellular, or both, and optionally select **Only these networks** or **Except these trusted networks**. Enter exact, case-sensitive Wi-Fi names, one per line. Save to enable automatic connection handling.

Only one profile can be armed at a time. Enabling another asks for confirmation and preserves the previous profile's saved rules. On-Demand never replaces another active VPN. It uses the profile's existing routing and DNS Protection configuration; it does not administer the router.

## Permissions and background behavior

- Grant Android's VPN consent when prompted.
- Allow notifications to see the persistent monitor and its current status. Android can hide the notification if permission is declined, but still lists the foreground service in its active-app controls.
- Basic Wi-Fi/cellular rules need no location access.
- Named-network rules require precise location, **Allow all the time**, and device Location enabled. Android protects Wi-Fi names as location information. No GPS coordinates are requested, and Wi-Fi names are not uploaded.
- Missing/redacted Wi-Fi names pause automatic decisions: the app neither guesses a trusted-network match nor disconnects the current VPN. The status explains what access is missing.

Monitoring continues when the screen closes, with a foreground notification. It attempts to restart after reboot or an app update when VPN consent remains available. Android/OEM battery restrictions or a force-stop can prevent a restart; open WireRoute to resume. This is an Android monitor, not Apple's system-managed Network Extension rules.

## Manual and system controls

- A manual disconnect pauses this profile until the physical network changes. Saving its On-Demand settings again clears the pause.
- Disabling On-Demand stops monitoring but leaves the current tunnel unchanged. Disconnect separately if desired.
- With no physical network available, the monitor waits and leaves the tunnel state unchanged.
- Android **Always-on VPN** takes precedence. Turn it off in Android's VPN settings to use conditional Wi-Fi/cellular rules. Do not combine trusted-network disconnect rules with **Block connections without VPN**; that setting intentionally blocks ordinary traffic outside the VPN.
- A manually connected different profile is not replaced. Its connection must end before the armed profile can connect automatically.
- On-Demand requires the userspace VPN engine. If the kernel backend was already selected, close and reopen WireRoute after saving the rules.
- Deleting a profile removes its rules; renaming it preserves them. Standard `.conf`/ZIP exports do not include On-Demand metadata.

Wi-Fi is preferred over cellular when both physical networks remain registered and validated. Ethernet and other transport types do not match the Wi-Fi/cellular controls.

## Troubleshooting

Check the On-Demand notification and reopen the profile's On-Demand dialog for its latest status. Confirm that VPN consent is still granted, Android Always-on is off, and no other VPN is active. For Wi-Fi exceptions, check exact spelling, precise/background location access, and device Location. A connected tunnel still requires a reachable endpoint and a valid peer; automatic activation does not guarantee a successful handshake.

See [Privacy](PRIVACY.md), [Support](SUPPORT.md), and the [documentation index](README.md).

Platform references: [Android VPN](https://developer.android.com/develop/connectivity/vpn), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted), and [background location permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background).
