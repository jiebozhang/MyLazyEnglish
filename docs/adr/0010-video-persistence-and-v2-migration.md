# ADR 0010: Video persistence, readiness gates and Room v2

## Scope and baseline

E2-T1 only, based on `codex/e1-t3-profile-management` at `725d71f`, on branch `codex/e2-t1-video-model-state-machine`. Sources: complete PRD v2.2, especially 8.3, VID-01 through VID-05, data model I, error handling and local import constraints; checklist 4.2, 7.1, E2-T1 and the Epic E3 ordering note.

Room version **2** adds only `videos`, `video_assets`, `import_jobs` and `watch_progress`. Family/Profile tables and the exported `1.json` fixture are unchanged. No UI, media import, subtitle tables, worker, real recommendation algorithm, or downstream deletion integration is implemented.

## Decisions and assumptions

### Models, fields and ownership

- Reuse T0-3 Video, VideoAsset, WatchProgress, IDs and closed VideoStatus state machine. Add ImportJobId and a minimal ImportJob envelope: id, videoId, status, idempotencyKey and optional errorCode. PRD does not enumerate ImportJob fields; these are explicitly the minimal local resource/async-task and idempotency interpretation, not claimed as a verbatim PRD table. The `(video_id, idempotency_key)` index rejects duplicate envelopes; request resumption belongs to E2-T2.
- Persist Video's source/checksum, metadata, status fields, playable flag, rights and repair fields. `family_id` is an explicit storage ownership column required by checklist 4.2, derived from the requesting active Profile, never guessed from a filename. VideoMetadata and VideoRights use structured JSON converters; scope and lifecycle are separate relational columns. Cover IDs must resolve to an asset of the same video. Uploader attribution can reference a same-family tombstoned Profile, so deleting an uploader does not make shared content uneditable.
- Add optional `VideoMetadata.levelSource` (MANUAL/ESTIMATED), the minimal missing T0-3 contract for PRD's explicit grading-origin requirement. Null means origin is unavailable; it must not be displayed as an official or inferred grade. No grading algorithm is implemented.
- VideoAsset maps the PRD's id/video_id/local_uri/checksum/codec/bytes directly. References remain opaque, nullable strings; no SAF resolution, path traversal, copy, delete or network access occurs here. Platform-reference import is rejected for V1.0, even though the future domain enum remains available.
- WatchProgress uses `(profile_id, video_id)` as its primary key. Every read/write explicitly takes profileId. Reads join the active Profile's household to the Video; invalid/foreign/deleted scope returns null or an empty list. Writes reject a mismatched embedded profileId or foreign video. Older timestamps cannot overwrite newer progress; exact Instant precision is retained. No all-Profile progress method exists.
- Family, video, asset, job and progress foreign keys use RESTRICT, not implicit destructive cascading. Actual deletion and future learning-event snapshots are intentionally not implemented. The unused `deleteVideoAndDetachMedia` declaration is retained in a separate `VideoDeletionRepository` contract for its owning task, rather than supplying a fake or prematurely destructive implementation in this repository.

### State machine and error contracts

The unchanged T0-3 transition graph exactly follows PRD 8.3:

```text
DRAFT -> PROCESSING -> READY -> ARCHIVED
                   -> NEEDS_SUBTITLE
                   -> FAILED
```

PRD does not specify recovery edges from FAILED/NEEDS_SUBTITLE or restoration from ARCHIVED. None are invented. Those states require an explicit future contract decision before retry/recovery transitions can be added. Entering a failure state requires both errorCode and actionable repairAdvice. Import lifecycle is mirrored on transitions; generic Video writes cannot independently introduce a contradictory lifecycle.

`VideoCatalog` in core/common extends the existing model repository and supplies `writeVideo` and `transition` returning the established `AppResult`. Putting this result-bearing extension in common avoids a model/common dependency cycle. T0-3's existing Unit-returning save/archive signatures remain available as compatibility bridges; failures throw the classified `VideoOperationException` containing the same AppError, not an unclassified exception. New use cases should consume the result-bearing methods directly.

New extensible codes follow existing style: VIDEO_INVALID_TRANSITION, VIDEO_INVALID_RECORD, VIDEO_SCOPE_UNAVAILABLE and VIDEO_STORAGE_FAILURE. NO_SUBTITLE, VIDEO_UNAVAILABLE and STORAGE_LOW reuse existing PRD errors. Cancellation is rethrown, not presented as an ordinary failure. No raw exception text, URI, secret or media filename is copied into errors/logs.

All writes and transitions use a Room transaction. Generic saves create only DRAFT, cannot change status/playable/failure fields, and cannot bypass the transition guard. READY metadata saves also rerun readiness checks. Asset references cannot be changed under READY/ARCHIVED; a future relinking use case must explicitly invalidate/revalidate readiness instead of silently changing media beneath a ready record. Concurrent competing transitions cannot both commit from the same old state.

### Forward readiness dependency

`SubtitleReadinessChecker.hasPublishedEnglish(profileId, videoId)` is the E3-T2 integration point. `UnavailableSubtitleReadiness` returns **false** until a real adapter is installed. It does not pretend the subtitle tables exist.

`MediaReadinessChecker.isAccessible(profileId, video, assets)` is the E2-T2 integration point. Its default also returns **false**. Caller-supplied `playable` or `SubtitleStatus.Available` alone is never sufficient. Transition to READY requires both checks to succeed and then records playable/available state atomically. Test-only injected checkers verify the positive/negative combinations; none are registered as production success defaults. Real adapters should be bounded local checks, not lengthy network operations while the transaction is held.

`recommendations(profileId)` selects only READY/playable records. Child recommendations additionally omit adult/unknown/missing age suitability. `getVideo(profileId, videoId)` still returns same-household archived records so history remains resolvable. Existing WatchProgress is not deleted on archive. Parent age-override authorization/auditing, live permission rechecks during playback and actual recommendation ranking remain later work.

## Migration and verification

`VideoMigration.MIGRATION_1_2` only creates four tables and their indices/foreign keys. `FamilyDatabase.open` registers it. There is no destructive fallback. Version 1's schema fixture is unchanged and version 2 is exported by Room.

Host migration tests build an actual SQLite v1 file from the retained exported schema, populate two families and active/deleted Profiles with all fields and timestamp precision, close it, then open it through generated Room v2 with the migration. Room performs full schema validation. Tests assert all old values and tombstones are preserved, cross-family isolation still holds, new tables work, and new progress survives reopening. A second test omits the migration, verifies refusal without resetting v1, then recovers through the proper migration.

The initial host fixture loader assumed every exported entity had an `indices` array; Room omits empty arrays. That test helper was corrected to treat omission as empty, without modifying the v1 fixture or migration.

Executed locally, using the configured JDK 17 and cached dependencies:

```text
./gradlew :app:assembleDebug :core:database:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
```

Final result: **BUILD SUCCESSFUL**, 17 seconds, 881 actionable tasks; **82 host tests passed, 0 failures/errors/skips**. Eight video repository cases cover the entire illegal-transition matrix, readiness combinations, direct-save bypass attempts, failure advice, archive/history behavior, age filtering, metadata/asset/job round-trips, ownership, duplicate keys, progress isolation/staleness, cancellation and competing transitions. Two host migration cases verify real upgrades and missing-migration safety. Database Lint reports no issues.

The ordinary signed database test APK was installed on the Xiaomi 10S (API 31). Its isolated test package does not open or upgrade the installed main app's household database. Android `MigrationTestHelper` creates v1 from exported assets and runs `runMigrationsAndValidate(..., 2, true, MIGRATION_1_2)`: **1 passed, 0 failed**, 0.152 seconds. [Full device output](evidence/e2-t1/migration-device.txt).

```text
adb -s <serial> install -r core/database/build/outputs/apk/androidTest/debug/database-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.core.database.VideoMigrationDeviceTest com.lazyeng.family.core.database.test/androidx.test.runner.AndroidJUnitRunner
```

## Acceptance and remaining scope

All E2-T1 acceptance items pass: illegal transitions preserve state with explicit errors; missing published subtitles deny READY; archive removes recommendations but preserves ID lookup; populated v1 upgrades without data loss; build/unit tests/Lint pass. No visual screenshots or Compose tests are needed for this data-only task.

The READY defaults deliberately remain closed until E3-T2/E2-T2 supply real adapters. No actual subtitle/media readiness is claimed. ImportJob processing, SAF import, failure retry policy, actual media deletion and downstream Profile cleanup remain their owning tasks. INT-2 still owns the wider API/device matrix. A v2 database must not be opened with an old v1 APK; no downgrade or data-reset fallback is provided.

Next task: **E3-T1**, then E3-T2 before E2-T2. Do not start E2-T2 directly.
