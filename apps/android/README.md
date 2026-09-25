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

Compose UI tests belong in `app/src/androidTest` and run with a connected device. Room v1 is owned by E1-T1 and exports `core/database/schemas/com.lazyeng.family.core.database.FamilyDatabase/1.json`. It is the initial schema and has no migration. Starting with E2-T1/v2, preserve prior schema files and add real migration tests using `room-testing`.

E1-T1 data-layer tests run on the host with Robolectric (API 28/native SQLite):

```powershell
.\gradlew.bat :core:database:testDebugUnitTest
```

These tests execute the generated Room DAO implementations, verify exact v1 columns and cross-family/profile isolation, and reopen disk-backed Room/DataStore instances to check process-recreation persistence without a device. They use only synthetic data and temporary files. Reports are in `core/database/build/reports/tests/testDebugUnitTest/`. Robolectric downloads its Android test runtime on first use; no family media or dictionary dataset is needed.

`FamilyDatabase.open(context)` is owned once by application assembly and exposes domain repository interfaces; `DataStoreCurrentProfileStore.create(context, profileRepository)` owns the non-sensitive current selection. A missing/deleted/cross-family selection returns `null` for later UI to show the picker. Ordinary storage failures propagate to the caller. PIN, UI assembly and downstream cleanup remain later tasks; see ADR 0007.

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
