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

Compose UI tests belong in `app/src/androidTest` and run with a connected device. Database migration tests belong in `core/database/src/androidTest` and use `room-testing`. The database module has no Room schema yet, so T0-2 adds the test runner and dependency only; migration tests must be added with the first real schema in E1-T1, not simulated with a fake migration.

## Module map

- `app`: application entry point and assembly.
- `core/model`, `core/common`, `core/testing`: pure Kotlin modules.
- `core/designsystem`, `core/database`, `core/datastore`, `core/media`, `core/network`: shared Android modules.
- `feature/onboarding`, `profiles`, `home`, `library`, `importmedia`, `player`, `dictionary`, `vocabulary`, `progress`, `parent`: isolated feature modules.

Features depend on shared core modules and do not depend on each other. Modules are empty until their owning task adds behavior. No business tables, Room entities, or Room schema are defined here.
