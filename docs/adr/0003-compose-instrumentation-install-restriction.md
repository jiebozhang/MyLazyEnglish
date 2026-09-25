# ADR 0003: Compose Instrumentation Install Restriction on Local Device

- Status: Accepted as a local device limitation; revisit during INT-2 device-matrix work.
- Scope: T0-4 Compose UI instrumentation acceptance only.

## Context

The Compose instrumentation APK intermittently installed and started on physical device `bc17cdb8` (API 36), but the test run did not complete. On the latest retry, installation failed before test execution with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. The normal `app-debug.apk` installs and launches successfully on the same device; the restriction affects the instrumentation test package, not the application APK.

## Checks

- Inspected `LoadingSkeleton` and the design-system tests: the skeleton is static, with no infinite transition; the tests do not call `waitForIdle()`.
- Set `screen_off_timeout` to 600000 ms and confirmed the device was awake with the keyguard not showing.
- Attempted to uninstall `com.lazyeng.family.test`, the requested `com.lazyeng.family.debug` and `com.lazyeng.family.debug.test`, and the actual instrumentation package `com.lazyeng.family.core.designsystem.test`; uninstall commands returned `DELETE_FAILED_INTERNAL_ERROR`.
- Confirmed `app-debug.apk` installs and `com.lazyeng.family/.MainActivity` launches on API 36.
- A prior instrumentation attempt installed and started the test APK but stalled without progress. Its partial XML recorded one passing test and a canceled driver; this is not a complete test pass.
- The latest `:core:designsystem:connectedDebugAndroidTest` retry failed during test APK installation with `INSTALL_FAILED_USER_RESTRICTED`, before any tests executed.

## Decision

Treat this as a local device/package-install restriction affecting instrumentation tests. Do not weaken signing or verification and do not attempt further installation workarounds in T0-4. Revisit connected Compose tests during INT-2 device-matrix work. T0-4's build, unit tests, lint, Preview catalog, and physical-device app-shell launch are complete; the instrumentation limitation is a known environment note, not a blocker to T0-5.
