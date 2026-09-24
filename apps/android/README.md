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
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On macOS/Linux, use `./gradlew` with the same tasks. The wrapper pins Gradle 9.3.1.

## Module map

- `app`: application entry point and assembly.
- `core/model`, `core/common`, `core/testing`: pure Kotlin modules.
- `core/designsystem`, `core/database`, `core/datastore`, `core/media`, `core/network`: shared Android modules.
- `feature/onboarding`, `profiles`, `home`, `library`, `importmedia`, `player`, `dictionary`, `vocabulary`, `progress`, `parent`: isolated feature modules.

Features depend on shared core modules and do not depend on each other. Modules are empty until their owning task adds behavior. No business tables, Room entities, or Room schema are defined here.
