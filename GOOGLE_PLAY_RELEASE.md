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

## Store listing assets

The [Nordic Blue listing kit](store-listing/README.md) contains the English listing copy, Play icon, feature graphic, real Android phone/tablet screenshots, and an optional preview video. Its guide maps every file to the matching Console field. The non-working screenshot fixtures are not functional app-review credentials.

Official guidance: [Android app signing](https://developer.android.com/studio/publish/app-signing), [prepare and roll out a release](https://support.google.com/googleplay/android-developer/answer/9859348), and [internal testing](https://support.google.com/googleplay/android-developer/answer/9845334).
