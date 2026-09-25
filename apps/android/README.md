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
- `core/designsystem`, `core/database`, `core/datastore`, `core/media`, `core/network`: shared Android modules.
- `feature/onboarding`, `profiles`, `home`, `library`, `importmedia`, `player`, `dictionary`, `vocabulary`, `progress`, `parent`: isolated feature modules.

Features depend on shared core modules and do not depend on each other. Modules gain behavior only in their owning tasks. E1-T1 adds only Family/Profile persistence in Room v1 and current-selection preferences in DataStore.
