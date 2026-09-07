# On-Demand VPN on Android

Open **Profiles → a profile → On-Demand**. Choose Wi-Fi, cellular, or both, and optionally select **Only these networks** or **Except these trusted networks**. Enter exact, case-sensitive Wi-Fi names, one per line. Save to enable automatic connection handling.

In single-profile mode, only one profile can be armed at a time. Enabling another asks for confirmation and preserves the previous profile's saved rules. Single-profile On-Demand never replaces another active VPN. It uses the profile's existing routing and DNS Protection configuration; it does not administer the router.

## Automatic profiles (524)

Open **Profiles → Automatic profiles** to choose a different saved profile for each network. This is optional and starts **Off**, including when upgrading. It uses the same On-Demand monitor and permission disclosure.

1. Choose a **Default** profile, or **VPN off** for no default connection.
2. Choose an action for **Other Wi-Fi**, **Cellular**, and **Ethernet**: use the default, keep the VPN off, or connect a specific profile.
3. Add **Trusted Wi-Fi** names where the VPN should stay off.
4. Add **Wi-Fi profile assignments**, such as your office network → Work profile. Names match exactly, including case and spaces. Duplicate assignments and names shared with the trusted list are rejected.
5. Enable automatic profiles and save. If single-profile On-Demand was armed, confirm the mode change. Its rules remain saved but are disabled; switching automatic profiles off does not rearm them.

For Wi-Fi, trusted networks take precedence, then an exact profile assignment, then **Other Wi-Fi**. Cellular and Ethernet use their own actions. There is no implicit first-profile fallback, wildcard matching, or access-point/BSSID matching. A missing assigned/default profile pauses decisions and is shown by name; it never silently substitutes another profile. Renaming a profile preserves references. Deleted assignments can be edited or explicitly removed in the modal.

Only connections started by automatic profiles may be switched or disconnected by that mode. A manual connection or another VPN is never taken over. Manual control pauses automatic switching on the current network; after a manual connection, that profile also remains protected until you disconnect it. Saving the settings clears the pause, but does not take ownership of a manual connection. Turning this mode off leaves the current VPN unchanged and relinquishes ownership.

Handover stops the old automatic profile before starting the new one. Each profile retains its own routing and DNS Protection settings. **Switching is not a kill switch:** there can be a brief interval without a VPN. A failed connection is reported and retried; the app does not silently choose another endpoint. The selected destination is checked before stopping the old tunnel, and queued changes are rejected if settings, network, or manual control changed in the meantime.

If Wi-Fi names are required but unavailable, decisions pause rather than selecting a fallback. With no physical network, the connection is left unchanged. Unsupported transports also leave it unchanged. The active physical network is preferred; when Android exposes the VPN instead, validated physical networks take precedence, with Ethernet preferred over Wi-Fi and then cellular as a tie-breaker.

Network matching is a convenience, not network authentication: Wi-Fi names can be spoofed. Only mark networks trusted when you accept that risk. Rules remain in the device-local database and are not included in standard `.conf`/ZIP profile exports.

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

In single-profile mode, Wi-Fi is preferred over cellular when both physical networks remain registered and validated. Ethernet and other transport types do not match its Wi-Fi/cellular controls; use automatic profiles to configure an Ethernet action.

## Troubleshooting

Check the On-Demand notification and reopen the profile's On-Demand dialog for its latest status. Confirm that VPN consent is still granted, Android Always-on is off, and no other VPN is active. For Wi-Fi exceptions, check exact spelling, precise/background location access, and device Location. A connected tunnel still requires a reachable endpoint and a valid peer; automatic activation does not guarantee a successful handshake.

See [Privacy](PRIVACY.md), [Support](SUPPORT.md), and the [documentation index](README.md).

Platform references: [Android VPN](https://developer.android.com/develop/connectivity/vpn), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted), and [background location permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background).
