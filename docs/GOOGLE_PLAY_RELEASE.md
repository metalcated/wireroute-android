# Google Play releases

WireRoute's permanent Google Play application ID is `com.metalcated.wireroute`, configured by `wirerouteApplicationId` in `gradle.properties`. Keep this ID for subsequent releases. The `com.wireguard.android` source namespace and tunnel-library coordinates are separate from the Play application ID.

## Generate a signed bundle in Android Studio

1. Open this repository in Android Studio and sync the Gradle project.
2. Select **Build > Generate Signed Bundle / APK > Android App Bundle**.
3. Choose the **ui** application module.
4. Select the existing `.signing/wireroute-upload.jks` upload keystore described below, with alias `wireroute-upload` and its saved password. On a new machine, restore the backed-up upload key first. Use **Create new** only if no upload key has been created or registered yet; new keys should have a strong password and a validity of at least 25 years. Do not use the debug keystore.
5. Select the **googleplay** build variant and finish the wizard. This variant disables the inherited app self-updater and excludes its package-install permission.
6. Use Android Studio's **Locate** link to find the signed `.aab` file in the destination selected in the wizard.

Google Play App Signing manages the key used for installations from Play. The upload keystore signs the bundle sent to Play; retain it for future uploads.

## Build with the local upload key

On the prepared development Mac, the upload keystore is `.signing/wireroute-upload.jks`, its alias is `wireroute-upload`, and `.signing/upload-password` contains its generated password. This directory is private and excluded from Git. Back up both files in secure storage before the first upload, and restore them locally when building on a new machine. Never commit or send these files with an app release.

The Google Play build automatically uses these files when both are present:

```sh
./gradlew :ui:bundleGoogleplay
```

The signed output is `ui/build/outputs/bundle/googleplay/ui-googleplay.aab`. Verify its signature before uploading:

```sh
jarsigner -verify ui/build/outputs/bundle/googleplay/ui-googleplay.aab
```

Without the local upload files or credentials supplied through Android Studio, this produces an **unsigned** bundle at the same path. An unsigned bundle is not ready for Play upload. Use the signed-bundle wizard above on a machine without the local key setup.

## Upload the first internal test

1. In Play Console, open **WireRoute > Test and release > Testing > Internal testing**.
2. Choose **Create new release**. Complete Play App Signing setup when prompted; for this new Play app, Google can generate and manage the app signing key.
3. Under **App bundles**, upload the signed `.aab` produced by the local Gradle build or Android Studio.
4. Confirm the package is `com.metalcated.wireroute`, review the version, add release notes, and save the draft.
5. On the **Testers** tab, add the Google accounts that will test the app, including the account on your test phone.
6. Review the release, resolve any errors Play reports, and roll it out to internal testing when ready. Send the opt-in link to those testers so they can install through Google Play.

The former `com.wireguard.android.debug` development build and the new Play application have different IDs and separate app data. Export any profiles you want to keep using WireRoute's export feature, and import them into the Play build. Profile exports contain private VPN keys, so keep them private.

For later releases, increase `wireguardVersionCode` before building and uploading another bundle. Version codes already uploaded to Play cannot be reused.

## On-Demand release declarations

Version 521 adds optional On-Demand networking and Wi-Fi-name rules. Before publishing it, review Play Console's foreground-service and background-location declarations and the updated [privacy policy](PRIVACY.md). The foreground monitor uses the `systemExempted` VPN-app category; named-network rules request precise/background location to read SSIDs while the app is closed. Basic Wi-Fi/cellular rules do not require location. Prepare a demonstration of the optional permission disclosure and network-rule behavior if Play requests one. Do not describe location access as an endpoint-map requirement.

Review Data Safety against the actual local-only Wi-Fi-name behavior; do not copy declarations from the earlier location-free build. Google Play review/approval is separate from a successful local build. See [On-Demand setup and limitations](ON_DEMAND.md).

## Android 15 edge-to-edge compatibility

Version 522 removes WireRoute's runtime status/navigation bar color setters. The main screen's inset-aware root supplies the background behind system bars, with icon contrast controlled through `WindowInsetsControllerCompat`. Older Android versions retain theme-provided bar colors. Material Components is updated to 1.14.0, whose bottom-sheet color setters are guarded below API 35. See the [Android migration guidance](https://developer.android.com/develop/ui/views/layout/edge-to-edge) and [Material release notes](https://github.com/material-components/material-components-android/releases/tag/1.14.0).

The `SimpleActor$offer$2` location reported for version 520 includes WireRoute's startup coroutine merged by R8; it is not evidence of a DataStore defect. Validate both appearance modes and gesture/three-button navigation after dependency updates. Compatibility libraries may retain legacy API references for older devices; local build success does not guarantee Play's static warning disappears. Upload the new bundle and review that release's report, not the historical version 520 report.

## Location hardware compatibility

Version 523 explicitly marks `android.hardware.location` optional, alongside the GPS and network-location subfeatures. Without the parent declaration, the On-Demand location permissions imply a required location feature and can exclude devices from Google Play. This changes install eligibility only: precise/background permission prompts and On-Demand behavior remain unchanged, as do minimum API 24 and target API 36.

Before uploading, inspect the generated Google Play manifest and APK feature list (`aapt2 dump badging`). All three location features must appear as `uses-feature-not-required`, with no required or implied-required location feature. After uploading, check Play Console's device comparison to confirm the affected models are supported again; local checks cannot confirm Play's device-catalog result.

## Automatic profiles (524)

Version 524 adds opt-in network-based profile switching under **Profiles → Automatic profiles**. Wi-Fi assignments, trusted-network exclusions, cellular/Ethernet choices, and a default profile use the existing On-Demand service. No additional permissions or hardware requirements are introduced. Existing single-profile rules remain saved, and upgrades do not enable the new mode. See [setup, priority, manual overrides, and handover limitations](ON_DEMAND.md#automatic-profiles-524).

Run `:ui:testDebugUnitTest` for policy tests. Build `:ui:assembleDebugAndroidTest`, install the debug app and test APK on the emulator, and run `adb -s emulator-5554 shell am instrument -w com.metalcated.wireroute.debug.test/com.wireguard.android.wireroute.ProfileSwitchingStoreRunner` for isolated real-SQLite persistence tests. The runner uses its own test database and does not modify VPN profiles. Repeat real endpoint handovers on a physical device before claiming end-to-end VPN connectivity validation; an active tunnel indicator alone is not proof of a handshake or working DNS.

Optional emulator-only runtime checks add `-e runtime true` before the instrumentation component. Grant VPN consent first, disable existing automation, and disconnect emulator tunnels. This opt-in test creates/reuses `SwitchTestWiFi` and `SwitchTestCell` loopback fixtures, temporarily toggles emulator Wi-Fi, checks both handover directions and manual-control behavior, and restores Wi-Fi and the disabled automation draft afterward. It leaves the two inactive test profiles in place; they are not usable VPN endpoints. The runner refuses physical devices.

## Notification branding (525)

Version 525 replaces the inherited WireGuard symbol in On-Demand notifications and Quick Settings with a monochrome adaptation of the WireRoute logo. This includes the tile-add request and active/inactive tile states. Android controls the system tint; launcher artwork, notification actions, VPN behavior, permissions, and optional hardware declarations are unchanged.

For isolated rendering and wiring checks, build/install the debug app and test APK as above, then run `adb -s emulator-5554 shell am instrument -w -e branding true com.metalcated.wireroute.debug.test/com.wireguard.android.wireroute.ProfileSwitchingStoreRunner`. This checks the real notification builder, tile manifest icon, transparent white rendering at small sizes, and legacy slashed-tile rendering without starting automation. It writes a light/dark preview to the debug app's `cache/notification-branding.png`. Physical notification-shade and tile behavior still require device verification.

## Store listing assets

The [Nordic Blue listing kit](../store-listing/README.md) contains the English listing copy, Play icon, feature graphic, real Android phone/tablet screenshots, and an optional preview video. Its guide maps every file to the matching Console field. The non-working screenshot fixtures are not functional app-review credentials.

Official guidance: [Android app signing](https://developer.android.com/studio/publish/app-signing), [prepare and roll out a release](https://support.google.com/googleplay/android-developer/answer/9859348), and [internal testing](https://support.google.com/googleplay/android-developer/answer/9845334).
