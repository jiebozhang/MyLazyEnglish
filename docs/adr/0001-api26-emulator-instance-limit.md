# ADR 0001: API 26 Emulator Instance Limit on Local Host

- Status: Accepted as a local environment limitation; revisit during INT-2 device-matrix work.
- Scope: T0-1 device acceptance only.

## Context

The API 26 emulator exits before boot with `It seems too many emulator instances are running on this machine. Aborting.` The issue reproduced with a newly created `LazyEng_API26_v2` AVD after deleting the original `LazyEng_API26` AVD and confirming its AVD directory and `.ini` file were absent.

## Checks

- No active `qemu` or `emulator` process was present before retrying.
- Stale lock files in the original API 26 and API 36 AVDs were manually cleared by the user before the final retry.
- Emulator logs report `Windows Hypervisor Platform accelerator is operational`; disabled virtualization was not the cause.
- Recreating the API 26 AVD under a new name did not change the failure.
- API 36 target acceptance succeeded on physical device `bc17cdb8` (API 36): the debug APK installed and `com.lazyeng.family/.MainActivity` displayed the project shell.

## Decision

Treat this as a host emulator/AVD environment issue with no confirmed active competing VM identified. Do not attempt additional local workarounds in T0-1. Revisit the API 26 device-matrix case during INT-2. T0-1's build, unit-test, lint, and API 36 physical-device acceptance are complete; this API 26 host limitation is a known environment note, not a blocker to T0-2.
