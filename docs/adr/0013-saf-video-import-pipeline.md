# ADR 0013: SAF video import pipeline

## Scope and sources

E2-T2 starts from E3-T2 `09dbcba` on `codex/e2-t2-saf-video-import`.
Sources: PRD v2.2 sections 8.3, 8.4 (IMP-01/02/06), 8.6 and 17; the
E2-T2 checklist; ADRs 0006, 0010 and 0012. The latest task instruction also
requests optional explicitly selected subtitles to pass through parsing and
publication. This does not implement E2-T3's sibling-directory discovery,
replacement, rollback, or encoding-selection UI. No unselected directory is read.

Status: host and Xiaomi 10S device acceptance completed, including per-Uri SAF
grant revocation and the actionable recovery UI. E2-T2 has no open acceptance item.

## Boundary and authorization

- `ImportDocuments` is the platform boundary. `SafImportDocuments` implements it
  using the application ContentResolver. The screen launches OpenDocument, not a
  filesystem path picker. No broad storage permission or media copy is used.
- The import route has its own E1-T2 ParentSession/PIN gate. A role label is not
  authorization. The application factory captures the active profile and rechecks
  the live PIN session, selected profile and live profile record before work and
  inside the save transaction. Every repository call receives that profile ID.
- ViewModel state, events and effects are separate; ImportScreen is stateless.
  Opening the system picker temporarily backgrounds the app without cancelling
  the selection. Returning from it still checks authorization. Ordinary background
  or leaving the route clears the PIN, selection and preview and revokes the session.
  Expensive reads expose READING/bytes read; verification and saving disable actions.
- Family/profile persistence, PIN derivation and subtitle publication are reused;
  there is no second security/database implementation or production test bypass.

## Metadata, files and permissions

Read grants are persisted with takePersistableUriPermission. Only grants newly
acquired by this session are candidates for cleanup; pre-existing grants are not
revoked on cancellation. Recording grant ownership is protected across cancellation
at the IO dispatcher boundary. A successfully saved video keeps its source grant.
The selected subtitle grant can be released after its parsed text is saved in Room.

Video bytes are streamed into SHA-256, never accumulated or copied. Metadata is
read directly from the SAF URI with MediaMetadataRetriever (duration, resolution,
container MIME); MediaExtractor supplies video codec and audio-track MIME types.
A decoded frame checks more than container headers, but is not a claim that every
frame of an arbitrary movie is decodable. API 27+ uses a small scaled frame; API 26
uses the available unscaled frame API. Cursor, streams, frame, retriever and extractor
are closed/recycled on all paths. `release()` is the API-26-compatible retriever close.

PRD does not specify a maximum video duration. `ImportPolicy.maxDurationMs` therefore
defaults to absent, rejects non-positive durations, and can enforce an explicit
caller policy. There is no invented global time limit. Subtitle input is capped at
4 MiB as a bounded-memory safety limit; this is not a video-size limit. Estimated
additional space is a configurable 1 MiB metadata reserve plus four times the
subtitle source bytes; it is an estimate, not a guarantee against subsequent disk
exhaustion. Space is checked before preview and immediately before saving.

Errors preserve the existing AppError/error-code convention. Missing file is
`SOURCE_FILE_MISSING`, not `PERMISSION_REVOKED`; a provider denying access while a
persisted grant remains is `SOURCE_ACCESS_DENIED`. Invalid container, invalid
duration, IO, low storage, changed source and expired scope have distinct actionable
messages. No raw exception, URI, PIN or file body is logged or shown as diagnostics.
Cancellation is rethrown, not labelled media corruption.

## Preview, duplicate detection and transaction

Preflight has no database writes. It reads the video, optional SRT/VTT, validates
the timeline, estimates space and queries checksum matches in the authorized
household scope. Matches in READY or NEEDS_SUBTITLE offer reuse or a separate new
import. Reuse also checks the existing source is accessible. Archived/failed content
is not silently reused. The parent explicitly confirms rights and the first ten
subtitle cues; the UI identifies this input as English or bilingual, not automatic
language detection. Absent subtitles never produce READY.

On confirmation the video is re-probed to reject a changed source. A single Room
transaction creates DRAFT Video/ImportJob, moves to PROCESSING, creates VideoAsset,
optionally writes and publishes an English SubtitleTrack/version/lines through
E3-T2, and finally stores READY or NEEDS_SUBTITLE with an actionable NO_SUBTITLE
reason. READY uses the real media and published-subtitle readiness guards. ImportJob
holds its final durable status; the existing schema has no separate transition-history
table, and this task does not invent one.

This short commit and its grant-ownership handoff are non-cancellable. Cancellation
before commit writes nothing; once committed, cancellation cannot revoke the source
of the durable video. A process death rolls back an unfinished SQLite transaction or
leaves a complete import. There is no independently persisted PROCESSING row or
background worker needing recovery. The completed record/job and persisted grant
are reusable after restart; an unconfirmed preview must be selected again.

A save failure rolls back assets/subtitles/intermediate status. For an explicitly
confirmed **new** import, the coordinator then attempts a separate atomic FAILED
Video/ImportJob receipt carrying a classified error and repair advice. This receipt
is not a playable half-import and owns no media asset/grant. If storage or current
scope also prevents the receipt, UI reports the error with `failureRecorded=false`;
it does not pretend the receipt exists. Preflight, cancellation and failed reuse do
not create these receipts. Repeated UI confirmation after completion is a no-op.

## Schema and model decision

Room remains **v3**: no tables, columns, migrations or exported schema changes.
The existing `metadata_json` stores optional `VideoTechnicalMetadata` (display name,
resolution, container MIME, audio MIME types); VideoAsset already stores codec and
byte count. This is a minimal domain extension for IMP-02's actual metadata, not a
parallel model. Legacy JSON without `technical` still reads as null; missing audio
metadata reads as an empty list. Existing migration/legacy converter tests still run.
Unknown age suitability remains `unknown`; this task does not invent age/CEFR
classification or override the child's recommendation filter.

## Reproducible verification

From `apps/android`, using the project's configured JDK and local SDK:

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :core:model:test :core:common:test :core:testing:test lintDebug --offline --console=plain
```

Offline mode used cached dependencies and avoided network retries. When networking
is needed, the local caller loads only whitelisted proxy variables from its ignored
dotenv file into process environment; no proxy value is committed or printed.

Latest host result: BUILD SUCCESSFUL, **129 tests, zero failures/errors, zero skipped**
(13 new import tests and 116 pre-existing tests). Debug APK, instrumentation APK
and all requested Lint tasks built successfully. Local full output is
`apps/android/app/build/reports/e2-t2-checks.log`. Exported Room schemas and the
frontend are unchanged.

`VideoImportCoordinatorTest` uses native SQLite via Robolectric and controlled
document/PIN fakes. It covers ready publication and metadata roundtrip, no-subtitle
state, duplicate reuse/new import, preflight cancellation, specific file errors,
space/duration rejection, invalid subtitles, changed-file failed receipt, expired
authorization, rollback after publication failure, mandatory consent, foreign-scope
read rejection and ViewModel loading/reset/picker-expiry behaviour. These are host
tests, not claims of device file-provider behaviour.

Device reproduction (normal signed debug install, no root/signature bypass):

```text
powershell -File feature/importmedia/tools/New-ImportFixtures.ps1
adb -s <device> push feature/importmedia/build/device-fixtures/. /sdcard/Download/
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <device> shell am instrument -w -r -e class com.lazyeng.family.ImportDeviceTest#importViaSystemPicker com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
adb -s <device> shell am force-stop com.lazyeng.family
adb -s <device> shell am instrument -w -r -e class com.lazyeng.family.ImportDeviceTest#recoverAfterProcessDeath com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
powershell -File feature/importmedia/tools/New-ImportFixtures.ps1
adb -s <device> push feature/importmedia/build/device-fixtures/<unique-missing-fixture>.mp4 /sdcard/Download/<unique-missing-fixture>.mp4
adb -s <device> shell am instrument -w -r -e missing_fixture <unique-missing-fixture>.mp4 -e class com.lazyeng.family.ImportDeviceTest#persistGeneratedSourceForRemoval com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
adb -s <device> shell rm -- /sdcard/Download/<unique-missing-fixture>.mp4
adb -s <device> shell am instrument -w -r -e class com.lazyeng.family.ImportDeviceTest#removedOriginalFileReturnsSourceMissing com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
adb -s <device> shell am instrument -w -r -e class com.lazyeng.family.UriGrantRevocationTest#revokedReadyUriReturnsPermissionErrorAndRecoveryGuidance com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

The device test drives the real system picker and production screen/pipeline in an
isolated fixture household and named acceptance database. Its authorization callback
is a test fixture; it is not an end-to-end real PIN gate test. It never clears the
installed family's database or PIN. Screenshots use 1.3x font scale. The MP4 is
READY (5016 ms, 320 x 240, `video/mp4`, audio `audio/mp4a-latm`), its SRT publishes,
and subtitle lines persist. MKV is 5023 ms at 320 x 240 (`video/x-matroska`, audio
`audio/mp4a-latm`) and correctly becomes NEEDS_SUBTITLE. Corrupt 43-byte and empty
files report MEDIA_INVALID without import rows. A separate `am force-stop` followed
by instrumentation relaunch preserved the Room record, checksum, metadata and
persisted video read permission. The earlier SAF/Media3 Spike on the same Xiaomi
10S verified first-frame rendering, play, pause, seek and playback after a real
force-stop on its selected generated clip (ADR 0006). Android 12's
PackageManager shell has no command for revoking one persisted SAF Uri grant
(`cmd uri_grants` returned `No shell command implementation`; `pm help` only
offers runtime permission grant/revoke). Broad storage app-ops are not equivalent
to SAF Uri grants. The prior recovery instrumentation used the exact per-Uri
`ContentResolver.releasePersistableUriPermission` API to remove this system-held
grant; this follow-up separately ran `UriGrantRevocationTest` against the same
Ready record with that grant absent. Production `VideoImportCoordinator.prepare`
returned `PERMISSION_REVOKED`, distinct from `SOURCE_FILE_MISSING`, without
changing the Ready record or crashing. The test asserted the exact actionable
message `文件访问授权已失效，请重新选择文件。`; `ImportScreen` passes that message
to `ErrorState` and exposes `重新选择`. The Settings UI was not navigated, but the
system-held per-Uri permission state and production response were exercised
directly; no unrelated permission or app-op was changed.
For SOURCE_FILE_MISSING, a uniquely named generated clip was selected with SAF and
its persistent grant verified, that exact generated file was removed, then probing
the still-authorized URI returned SOURCE_FILE_MISSING. No other Download item was
removed.

All four original device instrumentation methods passed individually:
importViaSystemPicker, recoverAfterProcessDeath, persistGeneratedSourceForRemoval,
and removedOriginalFileReturnsSourceMissing. The new headless
`UriGrantRevocationTest#revokedReadyUriReturnsPermissionErrorAndRecoveryGuidance`
also passed on this device (1 test, 0 failures). Its app-private report recorded:
`Coordinator phase=ERROR; error=PERMISSION_REVOKED`, `Distinct from
SOURCE_FILE_MISSING`, the actionable message, and `Ready record preserved=true; no
crash`. 1.3x screenshots and original measurements are in
`apps/android/app/build/reports/device-evidence-20260926/` (local build output,
not tracked); the new report is `files/import-evidence/uri-grant-revocation.txt`
inside the app sandbox and was pulled to
`apps/android/app/build/reports/uri-revocation-evidence/uri-grant-revocation.txt`
(ignored build output). Runner logs are alongside this report.

Current generated fixtures: MP4 196245 bytes, MKV 195038 bytes, SRT 121 bytes,
deliberately corrupt MP4 43 bytes and empty MP4 0 bytes. They contain only generated
test patterns and silence; generated media remains in ignored build outputs.

## Remaining work and device limits

Whole-movie decoding, other providers/API26 and grant reconciliation after process
death during an unconfirmed preview remain future integration considerations, not
open E2-T2 acceptance items. The exact per-Uri permission revocation and its
production error/recovery response are verified above. A failed grant release is
retained for another in-session cleanup attempt; there is no persistent
permission-sweeper in this task.

After E2-T2 acceptance the dependency DAG permits **E2-T3**, not the player or
the cross-feature Profile deletion acceptance task. No remote push or PR is performed.
