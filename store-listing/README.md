# WireRoute — Google Play listing kit

English (United States), Nordic Blue. Prepared September 5, 2026.

Use this kit in **WireRoute → Store presence → Store listings → Default store listing**. If your Console groups that menu under **Grow users**, open it there. Select **English (United States) — en-US**.

## What goes where

Paths below are relative to this folder. Open a text file and paste its contents into the matching field; upload image files individually. Do not upload the ZIP itself to Play Console.

| Play Console field | File or folder | Format |
| --- | --- | --- |
| App name | [en-US/app-name.txt](en-US/app-name.txt) | WireRoute |
| Short description | [en-US/short-description.txt](en-US/short-description.txt) | Under 80 characters |
| Full description | [en-US/full-description.txt](en-US/full-description.txt) | Under 4,000 characters |
| App icon | [en-US/graphics/app-icon-512.png](en-US/graphics/app-icon-512.png) | 512 × 512, 32-bit PNG, under 1 MB |
| Feature graphic | [en-US/graphics/feature-graphic-1024x500.png](en-US/graphics/feature-graphic-1024x500.png) | 1024 × 500, RGB PNG, under 15 MB |
| Phone screenshots | [en-US/graphics/phone-screenshots/](en-US/graphics/phone-screenshots/) | Eight 1440 × 2560 RGB PNGs, numbered in upload order |
| 7-inch tablet screenshots | [en-US/graphics/tablet-7-inch/](en-US/graphics/tablet-7-inch/) | Five 1440 × 2560 RGB PNGs |
| 10-inch tablet screenshots | [en-US/graphics/tablet-10-inch/](en-US/graphics/tablet-10-inch/) | Five 1800 × 3200 RGB PNGs |
| Video | [en-US/video/wireroute-preview-1080p.mp4](en-US/video/wireroute-preview-1080p.mp4) | Optional, 30 seconds, 1920 × 1080, silent H.264 |
| Release notes | [en-US/release-notes.txt](en-US/release-notes.txt) | Paste into your release, not the store description |

All screenshots are 9:16 with each side at least 1080 pixels and each file below 8 MB. The phone set exceeds the four-screenshot minimum for consideration for promotion; that does not guarantee promotion or approval.

## Phone screenshot order

1. Home — endpoint map, selected profile, and connection controls.
2. Profiles — home, office, and travel profiles with routing badges.
3. Profile detail — split/full routing and DNS settings.
4. Profile DNS — configured DNS servers and their route indicators.
5. Encrypted DNS — resolver URL and bootstrap configuration.
6. Resolver choices — preset providers and Custom.
7. Activity — transfer cards and the honest inactive/empty-history state.
8. Settings — Nordic Blue, retention, export, and diagnostics.

Use [en-US/alt-text.txt](en-US/alt-text.txt) if Console offers accessibility descriptions. Keep screenshot order consistent between languages if you add translations later.

## Optional video

The supplied video is an edited slideshow of actual Android screenshots, not a live connection demonstration. It has no soundtrack or third-party music. The first and last cards use WireRoute branding; the middle 24 seconds show the app.

1. Upload the MP4 to **your YouTube channel** as public or unlisted.
2. Ensure the video has no ads and no age restriction.
3. Use [en-US/video/youtube-title.txt](en-US/video/youtube-title.txt) and [en-US/video/youtube-description.txt](en-US/video/youtube-description.txt).
4. Paste the video's YouTube watch URL into the listing's **Video** field.

Nothing has been uploaded to YouTube or Google Play. You can leave Video blank and add it later.

## Other device sections

- **Tablet:** The supplied sets are real captures from Android running with small-tablet and large-tablet display configurations. They are not enlarged phone images. These emulator captures do not establish full physical-tablet compatibility; test a Play-installed build on your intended devices.
- **Chromebook:** Leave blank for this phone/tablet listing unless you are actively preparing Chromebook distribution. No ChromeOS-specific screenshot or compatibility claim is included.
- **Android XR:** Leave blank unless you support and test an XR experience. No headset screenshots have been fabricated.
- **Android TV:** The current manifest also exposes a TV launcher. If you opt into TV distribution, review that separate flow's TV testing and asset requirements. This kit does not establish TV release readiness or change distribution settings.

## Before saving the listing

Review the copy, upload the matching assets, then use **Save** and **Review** in Console. Saving listing assets is not a production rollout.

This kit covers the listing fields you showed. Privacy policy, Data safety, app access/reviewer credentials, content rating, VPN declarations, contact email, and release rollout are separate tasks. Use your real contact details and accurate declarations. Screenshot fixtures are deliberately non-working and must not be supplied as functional reviewer credentials.

## Provenance and boundaries

- App: `com.metalcated.wireroute`, Play build `1.0.20260315 (519)`, based on commit `a3e387dd`.
- Screens: actual released-variant UI on the Android emulator, with Nordic Blue selected. No synthetic interface, iOS screenshot, fabricated successful VPN connection, or invented traffic data is used.
- Samples: `HomeNetwork`, `OfficeGateway`, and `TravelSecure`, using reserved documentation addresses/example.com endpoints and intentionally non-secret fixture keys. No user VPN profiles, credentials, logs, or histories are included.
- Phone configuration: 1440 × 2560 at 400 dpi, logical width 576 dp.
- Small-tablet configuration: 1440 × 2560 at 320 dpi, logical width 720 dp.
- Large-tablet configuration: 1800 × 3200 at 320 dpi, logical width 900 dp.
- Tablet names identify Play upload categories and simulated layout classes, not a claim that a physical 7-inch or 10-inch device was tested.
- Android demo mode standardizes status-bar information. Screenshots retain real app layout and MapLibre attribution. Opaque capture PNGs are converted to RGB without resizing or retouching the UI.
- Icon: faithful size/format export of the existing `branding/wireroute.png`; no replacement logo.
- Feature art: generated using OpenAI's built-in image-generation tool, with Nordic Blue routing paths and checked lettering. The final prompt is in [source/feature-graphic-prompt.txt](source/feature-graphic-prompt.txt).
- The design skill guided the restrained blue palette and typography; image generation was used only for the promotional feature graphic, not the product screenshots.
- No app code, live router configuration, physical phone data, or publication settings were changed to make these assets.

`manifest.json` records dimensions, sizes, character counts, and SHA-256 checksums after validation. The ZIP contains this guide, the manifest, the feature-art prompt, and the `en-US` upload files; it excludes capture fixtures, signing material, and intermediate files.

## Official guidance

Checked against Google's [preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en) and [store listing guidance](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en). The Console's current validation remains authoritative.

## Recreate or validate

Capture only on a dedicated test emulator. The tools in `source/` require Python 3; rendering and image-format normalization require FFmpeg. The video tool also needs a locally installed font. Do not import screenshot fixtures over real profiles.

- `source/capture_android.py`: inspect visible Android UI, navigate named controls, and save real screenshots. `ANDROID_SERIAL` selects the emulator; `ANDROID_ADB` can override the SDK tool path.
- `source/render_preview.py`: reproduce the silent video from the captured phone screens. `LISTING_FONT` can specify a local sans-serif font.
- `source/package_listing.py`: normalize opaque screenshot PNGs to RGB, validate the upload files, create `manifest.json`, and assemble the ZIP in the repository's ignored `build/` folder. Raw captures are preserved in that folder before format conversion.
