# ADR 0006: SAF and Media3 Spike

- Status: Feasible on the tested Xiaomi 10S/local document provider; accepted as T0-5 Spike 3 evidence.
- Date: 2026-09-25.
- Scope: PRD v2.2 sections 17/19, IMP-01/02, checklist T0-5 and E2-T2. No production import UI, Room schema, or repository implementation.

## Method and References

The independent `:spikes:saf-media3` debug application launches `ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE` and `video/*`. It takes the returned read grant with `takePersistableUriPermission` and stores the selected Uri in private SharedPreferences. Metadata retrieval runs off the UI thread. `MediaMetadataRetriever` receives the SAF Uri and `MediaExtractor` receives a provider file descriptor. ExoPlayer receives `MediaItem.fromUri(uri)` without a cache, copy, or transcode layer.

- [Android SAF documentation](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions) describes persisting document grants; a moved/deleted document still requires recovery.
- [Media3 getting started](https://developer.android.com/media/media3/exoplayer/hello-world) documents ExoPlayer, MediaItem, PlayerView, and releasing the player.
- [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3) were checked; the Version Catalog pins ExoPlayer/UI to 1.11.1. The pin is isolated to this spike, not a production player decision.

Runtime validation used the user-approved Xiaomi 10S (`M2102J2SC`, `thyme`), API 31, reported MIUI `V130`, build incremental `999.9.99`. It is a modified/root-capable development device; the harness used ordinary debug-signed APK installation and app-UID access, with no root commands or permission bypasses. Results do not certify stock firmware or every API 26+ device.

## Synthetic Fixture

`New-TestClip.ps1` generates five seconds of FFmpeg `testsrc2` color bars and digital silence, encoded as H.264 Baseline/yuv420p plus AAC in MP4. No third-party video is used or committed.

- Measured bytes: **196,245**.
- SHA-256: `68de64a5d5799335d3b65b8977b5dfd4f771d1b1a75db1340cda5f1a8a91bbfd`.
- Device metadata: duration **5016 ms**, **320 x 240**, container **video/mp4**, video codec **video/avc**, rotation **0**.
- Host FFprobe: H.264 video and MP4 duration **5.000000 s**, AAC duration **4.991995 s**. The generator reproduced the same byte count and SHA-256 when rerun.
- The app accesses only the Uri explicitly selected in DocumentsUI. Media scanning was needed for the ADB-pushed synthetic clip to appear in the provider's Videos index; this host preparation is not part of the app import implementation.

## Actual Device Results

| Check | Observed evidence | Result |
| --- | --- | --- |
| System selection and persistence | Selected the synthetic clip in DocumentsUI; `Persisted read grant: true`; direct metadata and first frame at 0 ms | Pass |
| Play and pause | `IS_PLAYING=true` at 0 ms; pause at 823 ms, position 826 ms after 400 ms with `playing=false` | Pass; 3 ms clock settling |
| Seek and resume | Seek callback 826 -> 2000 ms, first frame at 2000 ms; resumed to 2684 ms, paused at 2688 ms | Pass |
| Real process death | PID 8344; `am force-stop`; `pidof` returned empty; relaunch PID 8918 | Pass; not merely backgrounding |
| End-to-end restart | No picker interaction; persisted grant true, same metadata, first frame, READY/playing true, ENDED at 5004 ms | Pass |
| No private media copy | `cache` empty; `files` held only 24-byte framework `profileInstalled` and the diagnostic report; preferences held the Uri | Pass for inspected files/cache/preferences; source has no media-copy/cache code |
| Original file removed | Checked device/host SHA-256 equality, deleted only the synthetic file, restarted PID 9139 | Access error detected |
| Error handling and retry | Provider threw `SecurityException`; the original classifier labeled it `PERMISSION_REVOKED`; repeated Read metadata returned the same error, PID 9139 stayed alive | Pass, no crash or stuck read |

The deletion test did **not** yield `FileNotFoundException` on this provider. The spike classifier now distinguishes `SOURCE_FILE_MISSING` from `PERMISSION_REVOKED`: `FileNotFoundException`, or `SecurityException` while the Uri still has a persisted read grant, maps to `SOURCE_FILE_MISSING`; a `SecurityException` with no persisted read grant maps to `PERMISSION_REVOKED`. Unit tests cover both branches. The previous device run recorded the provider exception, but did not snapshot the grant list at the time of deletion, so this follow-up did not claim a device-verified error label for that run. E2-T2 must validate this inference against target providers and offer re-selection for either condition. Settings-based manual revocation was not tested and remains explicitly unverified; E2-T2 must verify it.

Raw app callback evidence is retained in [0006-device-report.txt](evidence/0006-device-report.txt). Screenshots remain ignored build artifacts under `apps/android/spikes/saf-media3/build/evidence/`: `baseline.png`, `pause-after-seek.png`, `restart-playing.png`, `missing-file.png`. Restart screenshot shows the generated frame timestamp advancing and the pause control visible. Screenshots of unrelated picker contents are excluded from evidence.

## Build and Tests

With the local SDK environment and project JDK 17:

```text
gradlew -p apps/android :spikes:saf-media3:assembleDebug :spikes:saf-media3:testDebugUnitTest :spikes:saf-media3:lintDebug
```

- Build: successful; debug APK installed normally on Xiaomi 10S.
- Unit tests after the error-code follow-up: **4 passed, 0 failures/errors/skips**, covering retained versus absent read grants, missing-source versus other I/O, unsupported-media and unexpected-error classification.
- Lint: **No issues found**. Diagnostic text deliberately stays English in this temporary harness.
- No instrumentation package is required for this spike. Device evidence comes from actual system picker interaction, ExoPlayer callbacks, force-stop/relaunch, source deletion, and private-storage inspection.
- Reproduction instructions and fixture generator: [spike README](../../apps/android/spikes/saf-media3/README.md).

## Decision and Follow-up

SAF persisted read references plus direct Media3 playback are **feasible** for this local H.264/AAC sample on Xiaomi 10S. Reference-first import is suitable input to E2-T2. Keep permission persistence separate from Uri-string persistence, handle provider errors, release player resources, and perform metadata work off the main thread. This temporary Activity and error enum are not the production import architecture.

Limitations: no API 26/AOSP/other-brand validation, cloud provider, removable storage, large-file stress, physical device reboot, or independent Settings revocation test. The successful process-death case fulfills this spike; INT-2 should extend the matrix. The MIUI access-denied response after deletion is recorded without claiming it differs from all AOSP providers. FFprobe, Android metadata, and Media3 reported slightly different duration/end-position values as recorded above; the exact cause of the millisecond difference was not investigated in this spike.

T0-5 research is closed with three recorded outcomes: dictionary rights remain unresolved and require clearance or an alternative before E5 (ADR 0004); Keystore/throttle/fail-closed mechanisms are verified while final PIN performance calibration belongs to E1-T2 on the formal flagship target (ADR 0005); this SAF/Media3 path passed the present device checks. The next allowed implementation task is **E1-T1 (Family/Profile persistence and isolation, Room v1)**. Do not infer dictionary data approval or completion of INT-2 from this closeout.
