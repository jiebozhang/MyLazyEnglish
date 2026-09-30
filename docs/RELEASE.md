# LazyEng Family Release (Private Sideload)

This package is intended for private household use, not public store distribution.

## Install

1. Transfer the signed `app-release.apk` to the Android device.
2. Allow installation from the file manager used to open the APK, if Android asks.
3. Open the APK and choose **Install**. For an update, install over the existing app; do not clear its data.
4. Launch **LazyEng 家庭版** and complete onboarding if this is a new installation.

The package is signed with the household's locally generated release key. Keep that key and its credentials backed up securely outside the repository. Android accepts an in-place update only when the signing certificate matches the installed package's certificate. Losing or replacing the key requires uninstalling the old app and may lose local data.

Current V1.0 artifact: versionName `1.0.0`, versionCode `2`, 5,309,659 bytes.
SHA-256: `069890ea4743c85c9c220d5a47b4fd20d01d95f9d4d11ae7612c1f747a17918d`.
See the [family delivery guide](delivery/v1.0-family-delivery-guide.md) and
[current acceptance record](adr/0034-private-release-signing-and-delivery.md).

## Upgrade and Data

The app uses the existing ordered Room migration chain through schema v6. The v1 fixture has been verified through the complete chain without destructive migration (see ADR 0033). Installing this release over an existing app preserves its private data and lets Room upgrade it on first database open. This release task validates only the current app to release signing/install hop; it does not repeat a historical-device upgrade matrix.

## Dictionary Attribution

The bundled ECDICT package includes its MIT copyright and permission notice in the app assets (`ECDICT-NOTICE.txt`). The project follows the owner's bounded personal-household-use decision recorded in [ADR 0016](adr/0016-ecdict-import-and-lookup.md). The upstream corpus provenance gap and the accepted family-use risk remain documented in [ADR 0004](adr/0004-dictionary-license-and-sizing.md); this is not legal advice or approval for public/commercial redistribution.

## Known Limits

- English subtitle tracks are required. Existing imported bilingual content can be displayed; no whole-video translation is generated.
- YouTube ingestion, automatic translation, and ASR are deferred to V1.1, not included in V1.0.
- AI explanations require a household-configured provider and network access; offline dictionary lookup remains local.
- ECDICT is used under the bounded personal-household risk acceptance described above.
- Full official device/performance acceptance is incomplete; family-use exclusions and pending checks are listed in the delivery guide. This is a tested household release, not a claim of universal certification.
