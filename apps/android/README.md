# LazyEng Family Android

This is the native Android application workspace. The React prototype remains in `../../frontend` as a visual and interaction reference.

## Build requirements

- JDK 17
- Android SDK platform 36 and Build Tools 36.0.0
- Android SDK licenses accepted

Set `ANDROID_HOME` to the local Android SDK location. Do not commit `local.properties`; it contains machine-specific paths.
Set `JAVA_HOME` to a JDK 17 installation before running Gradle. The local `gradle.properties` file is ignored by Git so a machine-specific Gradle JDK path is not shared.

## Verification

From this directory, run:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest
.\gradlew.bat :core:testing:test
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
# Requires a connected device or emulator:
.\gradlew.bat :app:connectedDebugAndroidTest
```

On macOS/Linux, use `./gradlew` with the same tasks. The wrapper pins Gradle 9.3.1.

The PR workflow assembles the app and test APKs, runs `core/testing` JVM tests plus Android unit tests, and runs Lint. These checks do not download dictionary datasets or media files. `core/testing` is consumed only through test configurations and contains deterministic, in-memory fixtures and fakes.

Compose UI tests belong in `app/src/androidTest` and run with a connected device. Room v1 is owned by E1-T1 and its exported `core/database/schemas/com.lazyeng.family.core.database.FamilyDatabase/1.json` remains the initial fixture. E2-T1 upgrades the database to v2 with an explicit 1-to-2 migration and real host/device migration tests. Preserve all prior schema files.

E1-T1 data-layer tests run on the host with Robolectric (API 28/native SQLite):

```powershell
.\gradlew.bat :core:database:testDebugUnitTest
```

These tests execute the generated Room DAO implementations, verify exact v1 columns and cross-family/profile isolation, and reopen disk-backed Room/DataStore instances to check process-recreation persistence without a device. They use only synthetic data and temporary files. Reports are in `core/database/build/reports/tests/testDebugUnitTest/`. Robolectric downloads its Android test runtime on first use; no family media or dictionary dataset is needed.

`FamilyDatabase.open(context)` is owned once by application assembly and exposes domain repository interfaces; `DataStoreCurrentProfileStore.create(context, profileRepository)` owns the non-sensitive current selection. A missing/deleted/cross-family selection returns `null` for later UI to show the picker. Ordinary storage failures propagate to the caller. PIN, UI assembly and downstream cleanup remain later tasks; see ADR 0007.

## E5 offline dictionary package

The app ships a gzip-compressed, pinned ECDICT subset as an APK asset. The first local lookup installs it asynchronously to `files/dictionary/ecdictionary.sqlite`; SHA-256 and SQLite integrity/schema are checked before an atomic replacement. A failed or interrupted extraction is retried on the next lookup. The generated package includes `ECDICT-NOTICE.txt`; see ADR 0019 for version, measured size, integrity hashes, and the bounded private-household distribution decision. No runtime network access is used.

From the repository root, reproduce the APK asset from the pinned upstream CSV (keep both source and full intermediate SQLite outside the checkout):

```powershell
py -3 tools/import_ecdict.py --csv <pinned-ecdict.csv> --database <outside-checkout>/ecdictionary-full.sqlite
py -3 tools/build_ecdict_mini.py --database <outside-checkout>/ecdictionary-full.sqlite --output apps/android/app/src/main/assets/dictionary/ecdictionary.sqlite.gzip --limit 20000
Copy-Item docs/dictionary/ECDICT-NOTICE.txt apps/android/app/src/main/assets/dictionary/ECDICT-NOTICE.txt
```

The `.gzip` suffix is intentional: AAPT automatically expands `.gz` assets and removes that suffix. The builder also writes a checksum and package manifest beside the compressed asset. For a rooted development device only, an already-built uncompressed database can be provisioned without reinstalling the APK:

```powershell
tools/provision_ecdict.ps1 -DatabasePath <outside-checkout>/ecdictionary.sqlite -Serial <adb-serial>
```

The app still verifies the database against the packaged checksum on the next process start. Do not use the root helper as a production installation mechanism.

## Module map

- `app`: application entry point and assembly.
- `core/model`, `core/common`, `core/testing`: pure Kotlin modules.
- `core/designsystem`, `core/database`, `core/datastore`, `core/security`, `core/media`, `core/network`: shared Android modules.
- `feature/onboarding`, `profiles`, `home`, `library`, `importmedia`, `player`, `dictionary`, `vocabulary`, `progress`, `parent`: isolated feature modules.

Features depend on shared core modules and do not depend on each other. Modules gain behavior only in their owning tasks. E1-T1 adds only Family/Profile persistence in Room v1 and current-selection preferences in DataStore.

## E1-T2 onboarding and PIN

The app now starts with native onboarding: family/PIN confirmation, a child Profile, then a home placeholder. It uses the existing domain repositories and Room v1 without changing its schema. Non-secret drafts live in DataStore; one-way PIN verifiers and durable throttle metadata live in a Keystore-protected, non-backed-up atomic file. No raw PIN is saved. See ADR 0008 for recovery semantics and measured temporary KDF parameters.

Host verification:

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug
```

The default connected test suite runs stateless onboarding UI tests. Full-flow acceptance is opt-in and requires an unused test installation; it refuses to overwrite any existing PIN record:

```text
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lazyeng.family.OnboardingScreenTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lazyeng.family.OnboardingFlowTest -Pandroid.testInstrumentationRunnerArguments.onboardingAcceptance=true
```

After installing both app/test APKs on a chosen test device, the following separate instrumentation processes verify the production encrypted store across an actual force-stop. They use an isolated cache subdirectory and a controlled clock, never the household record or a hardcoded PIN:

```text
adb -s <serial> shell am instrument -w -e class com.lazyeng.family.PinProcessRestartTest#prepare -e pinProcessPhase prepare com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am force-stop com.lazyeng.family
adb -s <serial> shell pidof com.lazyeng.family
adb -s <serial> shell am instrument -w -e class com.lazyeng.family.PinProcessRestartTest#verify -e pinProcessPhase verify com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

Prepare must run on an unused `cache/pin-process-fixture` directory. The verifier survives the process change and remains locked at the injected timestamp; this is a persistence proof, not a real-time 30-second duration measurement. The full-flow UI test separately waits out the real 30-second lock and verifies successful reset. These explicit acceptance tests are skipped unless their matching runner argument is present; skipped tests must not be reported as passed.

UI fixture screenshots are written under the test app's `files/onboarding-evidence/`. They contain only synthetic text. Production screens keep `FLAG_SECURE` enabled, including during acceptance; do not disable it to capture PIN input or bypass device installation restrictions.

E1-T2's separate `OnboardingFlowTest#restoresHomeAfterExternalProcessDeath` test uses the opt-in runner argument `onboardingRestoration=true` after the full flow and an external `adb shell am force-stop`. It never initializes missing household data. The flow captures only synthetic non-PIN Profile/home content, temporarily clearing its test Activity's secure window flag and restoring it immediately; production PIN screens remain protected.

On the development MIUI device, APK installation resets background-start permissions. With the device owner's approval, both app/test packages need MIUI auto-start/background Activity launch and RUN_ANY_IN_BACKGROUND allowed **after installation**, then the installed test APK can be run with `adb shell am instrument`. Otherwise ActivityScenario can stall before rendering. These are device-local settings, not production app permissions or project build configuration. Detailed diagnosis and successful 1.3x screenshots are in ADR 0008.

## E1-T3 Profile management

The home placeholder now opens the child-accessible picker and PIN-gated parent member management. Parent management creates/edits Profile fields; deleting requires a fresh second PIN. Room remains v1. See ADR 0009 for the limited directory projection, event contract, atomic cleaner registration and onboarding completion preference.

The Profile integration suite uses isolated synthetic Room/DataStore/Keystore records and can run without resetting existing household data:

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lazyeng.family.ProfilesFlowTest
```

On MIUI, after installing both APKs and reapplying the authorized device-local launch settings, the equivalent installed runner is:

```text
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.ProfilesFlowTest com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

Synthetic screenshots are written to `files/profiles-evidence/`. The 1.3x cases override density in the Compose harness without changing the device's system font setting. Real PIN digits are neither logged nor captured; deletion-confirmation screenshots are taken before input. Production `FLAG_SECURE` is unchanged.

## E2-T1 video persistence and migration

Room v2 adds Video, VideoAsset, ImportJob and Profile-scoped WatchProgress. Existing Family/Profile data is preserved by `VideoMigration.MIGRATION_1_2`; no destructive or downgrade fallback exists. `FamilyDatabase.videoRepository` returns the result-bearing `VideoCatalog`; READY is denied by default until E3-T2 and E2-T2 supply real subtitle/media readiness checkers. See ADR 0010 for contracts, assumptions and test evidence.

```text
./gradlew :core:database:testDebugUnitTest :core:database:assembleDebugAndroidTest
./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lazyeng.family.core.database.VideoMigrationDeviceTest
```

The migration test uses exported v1/v2 schema assets and an isolated database. It never resets the main application's data. Full validation also runs `:app:assembleDebug`, the three pure-Kotlin core test tasks, `testDebugUnitTest` and `lintDebug`. This task has no UI or real import workflow; next is E3-T1, not E2-T2.

## E3-T1 subtitle parsing and migration

Room v3 adds scoped subtitle tracks, draft/published version storage and ordered lines. Both 1->2 and 2->3 migrations are registered; exported v1/v2 fixtures are retained. `SubtitleParser` in core/common parses bytes without Android or I/O, preserving source hash/encoding/parser version. Ambiguous legacy encodings require explicit selection. Unsupported WebVTT layout is reported, not silently discarded. Publication, timeline validation and the real subtitle readiness adapter remain E3-T2. See ADR 0011 for the exact subset and acceptance evidence.

```text
./gradlew :app:assembleDebug :core:database:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lazyeng.family.core.database.SubtitleMigrationDeviceTest
```

Pure JVM parser tests require `:core:common:test` (not included by `testDebugUnitTest`). Synthetic file fixtures live in `core/common/src/test/resources/subtitles`; the optional `Generate-EncodingFixtures.ps1` reproduces byte-specific variants. They are already checked in, so ordinary tests require no PowerShell, download, dictionary or real media. Migration tests use isolated databases, not household data.

## E2-T3 subtitle preview device acceptance

Run from apps/android (Windows uses gradlew.bat). Screen tests dispatch events to
test callbacks, without launching system pickers. The separate opt-in real SAF
test uses generated media and an isolated in-memory database, not household data.

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Accept installation prompts while the phone is unlocked. After BOTH installs,
apply the owner-approved MIUI launch settings from ENV_NOTES.local.md to both
packages. Running Gradle Connected afterward reinstalls packages and may reset
these settings; its cleanup may uninstall the app. Use the installed runner for
this device-specific workaround, without claiming a Gradle Connected pass.

Generate synthetic fixtures with PowerShell and an available ffmpeg executable:

```powershell
./feature/importmedia/tools/New-ImportFixtures.ps1 -Ffmpeg ffmpeg
```

```text
adb -s <serial> shell mkdir -p /sdcard/Download/LazyEng-E2T3-Fixtures
adb -s <serial> push feature/importmedia/build/device-fixtures/lazyeng-import-fixture.mp4 /sdcard/Download/LazyEng-E2T3-Fixtures/
adb -s <serial> push feature/importmedia/build/device-fixtures/lazyeng-import-fixture.srt /sdcard/Download/LazyEng-E2T3-Fixtures/
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.SubtitlePreviewScreenTest,com.lazyeng.family.SubtitleCandidateDeviceTest -e safCandidateAcceptance true -e timeout_msec 120000 com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

Keep the generated directory limited to these two files. The opt-in test confirms
the actual tree grant using bounded system UI actions, then previews, publishes
and rolls back via production import code. Without safCandidateAcceptance=true,
that test is skipped, not passed. Other historical device tests have different
external setup requirements; do not substitute an unprepared all-class run.

Screenshots use font scale 1.3 in the test composition and are written to the
target package's files/subtitle-preview-evidence/. Export with adb exec-out
run-as and a binary-safe output stream into app/build/reports/e2-t3/.
See ADR 0014 for the six-test result and evidence list. Do not commit generated
media, APKs or screenshots. Production FLAG_SECURE and user data remain intact.

## E3-T2 timeline, tokens and publication

`TimelineValidator` and `SubtitleTokenizer` in core/common are pure JVM utilities. Timeline reports separate blocking errors from advisory warnings; offset previews never mutate input. Token ranges are UTF-16/end-exclusive with exact original substrings; normalized lookup forms are not guessed lemmas.

`subtitleRepository().publish` validates and atomically publishes/selects a draft; callers receive detailed warnings/errors. Published metadata and lines cannot be edited or deleted through the Repository/DAO. Calibration/replacement must use new version/line IDs. Room remains v3 with unchanged schema exports. The default video repository now uses the real current-published-English checker, while media accessibility remains closed until E2-T2 supplies its adapter. ADR 0012 documents thresholds, contracts and limits.

```text
./gradlew :app:assembleDebug :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
```

The new pure JVM and native-SQLite host tests cover offsets, timeline errors/warnings, draft CRUD, publication/rollback/concurrency, immutable history, scope and readiness. No UI or device permissions are involved. Next task: E2-T2.

## E2-T4 child and parent video detail

After an import completes, open video details. The child projection checks READY,
published English and current SAF access before exposing Watch/Continue. Parent
management uses a separate PIN-gated route, displays real version history/rights,
and offers confirmed archive or subtitle repair through the existing preview.
The repair target is fixed to the selected video; Room remains v3.

Watch emits the video ID and saved position. The player belongs to E4-T1; until
then the app explicitly reports that playback is not yet integrated. It does not
pretend a video is playing. Persistent library navigation belongs to EH.

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

After both installations, reapply the MIUI settings from ENV_NOTES.local.md,
then run the installed tests without another reinstall:

```text
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.VideoDetailScreenTest -e timeout_msec 120000 com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.SubtitlePreviewScreenTest -e timeout_msec 120000 com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

The repair picker test intercepts ACTION_OPEN_DOCUMENT using Espresso Intents
and a generated local SRT; it never opens DocumentsUI. Synthetic font-scale-1.3
screenshots are under files/video-detail-evidence/ in the target app, exported to
app/build/reports/e2-t4/. No household database is reset. See ADR 0015 for state,
scope, repair/version decisions and the final acceptance evidence.
