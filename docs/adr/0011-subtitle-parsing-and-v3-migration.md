# ADR 0011: Subtitle parsing, scoped storage and Room v3

## Scope and sources

E3-T1, on `codex/e3-t1-subtitle-parsing`, based on E2-T1 `162b108`. Sources: complete PRD v2.2 (especially 8.4, 8.6, data model I and section 15), checklist 7.1/E3-T1/E3-T2, the T0-3 contracts and ADR 0010. This task adds only subtitle models/storage, byte decoding, SRT/VTT parsing and migration. No UI, SAF import, tokenization, timeline calibration or publishing service is implemented.

## Model and storage decisions

- Reuse SubtitleTrack/SubtitleVersion/SubtitleLine and their strong IDs. The existing DraftSubtitleVersion and PublishedSubtitleVersion types now expose explicit DRAFT/PUBLISHED status. Draft/published types cannot report each other's status. Published objects retain the existing no-copy shape.
- Room v3 adds `subtitle_tracks`, `subtitle_versions`, `subtitle_lines`. All PRD model-I fields are present. `source_encoding` is the minimal addition required by PRD 8.6's encoding preservation rule; `published_at` preserves T0-3's already-established publication timestamp contract. These are not claimed as columns explicitly listed in the abbreviated model-I table. Encoding is nullable at the domain/schema boundary for legacy/external metadata; new repository draft writes require a nonblank value. Published timestamps are nullable only because drafts have not been published.
- Foreign keys use RESTRICT, not destructive cascades. Track points to Video; Version points to Track; Line points to Version. `(version_id, seq)` is unique. A deferred composite FK `(track.id, current_version_id) -> (version.track_id, version.id)` prevents a track pointing at another track's version while permitting the creation/publication order E3-T2 needs.
- Every read requires profileId and joins an active Profile through the owning Video household. Missing, deleted and foreign-family scope returns null/empty. Subtitles are household-shared content, not private child records. Writes validate ownership in a Room transaction; global ID collisions cannot overwrite another household's content. A failed line replacement rolls back the version and all lines.
- `SubtitleCatalog` in core/common extends the existing model Repository with result-bearing write methods, following VideoCatalog's established dependency direction. Compatibility Unit methods throw only classified SubtitleOperationException for expected validation/storage failures. New codes: SUBTITLE_INVALID_RECORD, SUBTITLE_SCOPE_UNAVAILABLE and SUBTITLE_STORAGE_FAILURE; full storage uses existing STORAGE_LOW. Cancellation is rethrown. Raw exception text, source names and body text are not logged or copied into errors.
- Minimal T0-3 contract additions: saveTrack/getDraft, and a separate `SubtitlePublisher` containing the existing publishDraft signature. This avoids a fake or premature publish implementation. Generic track writes cannot change currentVersionId; writes accept draft models only. E3-T2 still owns the publication transaction, full immutable-version enforcement, time validation and readiness adapter. No E3-T2 completion is claimed from the defensive draft-only repository checks.
- `SubtitleReadinessChecker` is unchanged and **still defaults to false**. A test seeds a published read model directly in its isolated database and proves it does not silently enable READY. Production publication is not exposed in this task.

## Parser and encoding policy

`SubtitleParser` in core/common is a pure Kotlin/JDK byte-to-cue operation, with no Android, Room, files, network, time or generated IDs. CharsetDecoder uses REPORT, not replacement characters. The parser returns all cues or one structured first-error report; it never returns a partial successful document. UI can use the AppError code/message key and the separate safe Chinese parent-facing advice plus physical file line. No original subtitle text is included in diagnostics.

Encoding decisions:

1. Recognize UTF-8 BOM and UTF-16 LE/BE BOM; remove only that leading marker before decoding. An explicit encoding conflicting with BOM fails.
2. Without BOM, strictly validate UTF-8 first, as PRD 8.4 prefers. ASCII is the compatible UTF-8 subset.
3. Invalid UTF-8 is not automatically labeled GBK. A strict GBK decoder identifies GBK as a *candidate*, but other legacy encodings may also decode those bytes. Return SUBTITLE_ENCODING_UNCERTAIN with an explicit choose-encoding/re-export action. Explicit GBK selection decodes the actual GBK fixture correctly. This is the PRD's manual-encoding fallback, not guessed text repair.
4. Illegal byte sequences under an explicit encoding, conflicting BOM, embedded BOM/replacement characters and disallowed control characters return SUBTITLE_ENCODING_INVALID. UTF-32 BOM is unsupported and reported as uncertain; there is no broad encoding heuristic or silent fallback.

The result retains SHA-256 of the **original bytes**, detected/selected encoding, BOM presence and parser version `srt-vtt-1`. The import caller will assign IDs and persist sourceHash/sourceEncoding/parserVersion through the draft repository; no import orchestration is added here.

SRT timestamps require hours/minutes/seconds/comma/three-digit milliseconds. WebVTT accepts hours optional and dot milliseconds. Minutes/seconds must be 0..59; hours are elapsed duration, not time of day, and may exceed 23. Overflow is rejected. Millisecond conversion never adjusts values. SRT sequence numbers are positive, unique and sorted; WebVTT identifiers are arbitrary labels, so cues receive stable file-order sequence numbers. LF/CRLF/CR and extra blank separators are accepted; multiline text keeps its internal newline. Missing separators cannot swallow another valid timing line as dialogue.

Markup policy is **strip supported styling tags, preserve body text**: i/b/u/font, including quoted font attributes. The same policy applies to both formats; markup is never executed. Unknown or incomplete tag starts return SUBTITLE_MARKUP_UNSUPPORTED, rather than dropping unknown body content. Entity text is preserved verbatim. WebVTT NOTE blocks are explicitly comments, not spoken cues. Positioning settings, STYLE/REGION/header metadata and unsupported cue tags are rejected with SUBTITLE_VTT_LAYOUT_UNSUPPORTED or SUBTITLE_MARKUP_UNSUPPORTED and an export-to-plain-subtitles action. This is a deliberately documented plain-text learning subset, not a claim of full WebVTT layout rendering. Future expansion must retain fixture-backed, non-silent behavior.

End-before-start, overlaps, long-line thresholds, token character mapping and version publication remain E3-T2. A reversed-time fixture proves E3-T1 does not silently rewrite the timeline. Successfully parsed drafts are not yet approved for playback.

## Fixtures and tests

All 24 subtitle fixture files are original synthetic examples, not real household/copyrighted subtitles. They cover SRT/VTT, BOM, UTF-16 BOM, actual CRLF bytes, multiline, bilingual, excess whitespace/out-of-order sequence, styling, malformed timestamp shape/ranges/arrow, duplicate sequence, missing separator, unsupported tags/layout, comments, overflow, reversed time, actual GBK bytes and invalid bytes. `Generate-EncodingFixtures.ps1` mechanically reproduces encoding/newline variants of the synthetic UTF-8 source. `.gitattributes` prevents checkout newline conversion corrupting byte-specific fixtures.

Parser tests verify exact text/millisecond outputs, encoding decisions, hash, structured errors and 30 identical repeat parses. Repository tests cover sorting/pagination/replacement, draft/published read distinction, missing/deleted/foreign-family scope on all read APIs, cross-household ID collisions, transaction rollback, composite current-version ownership and unchanged readiness defaults.

`SubtitleMigration.MIGRATION_2_3` creates only the three tables and indices. `FamilyDatabase.open` registers both migrations; no destructive fallback is present. Exported `1.json` and `2.json` are unchanged; generated `3.json` is committed.

The host migration test constructs a real v2 file from exported 2.json, fills **all six v2 tables** for two families and a tombstoned Profile, captures every column, closes it, and upgrades through generated Room v3. It checks exact snapshots, schema/FKs, new subtitle writes, reopen persistence and scope. Opening without the migration is also proven to fail without clearing or changing v2 data. The existing v1 host regression now runs the full 1->2->3 path. Device MigrationTestHelper separately validates retained 1->2 and new 2->3 against exported schema assets.

During verification, two test setup defects were fixed: AGP 9 requires the shared Kotlin test directory on the Kotlin source set, and the synthetic tombstone's english_level must honor v2's NOT NULL constraint. No schema relaxation or production fallback was used to mask either failure.

## Executed verification

Using the configured JDK 17, SDK and cached dependencies, from apps/android:

```text
./gradlew :app:assembleDebug :core:database:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
```

Result: **BUILD SUCCESSFUL**, 27 seconds, 882 actionable tasks; **94 host tests, 0 failures/errors/skips**. Database Lint: **No issues found**. Earlier focused commands were `:core:common:test :core:database:testDebugUnitTest` and those tasks plus `:core:database:assembleDebugAndroidTest`. Local complete output is in ignored `app/build/reports/e3-t1-validation.log`.

On Xiaomi 10S, API 31, the ordinary signed standalone database instrumentation APK installed successfully. It uses isolated synthetic databases and never resets/upgrades the installed main application's household database:

```text
adb -s <serial> install -r core/database/build/outputs/apk/androidTest/debug/database-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.core.database.VideoMigrationDeviceTest,com.lazyeng.family.core.database.SubtitleMigrationDeviceTest com.lazyeng.family.core.database.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (2 tests)**, 0.191 seconds, both test status codes 0. [Complete device output](evidence/e3-t1/migration-device.txt). This is actual MigrationTestHelper execution, not inferred from compilation. No Compose/screenshots are applicable to this data-only task.

## Acceptance and remaining scope

E3-T1 is complete: required fixtures parse or return precise expected errors; stable ordering and unified milliseconds pass; tags follow one explicit policy; populated v2 migrates with every old field retained; build/unit tests/Lint and device migration tests pass.

Limits: ambiguous legacy encoding requires parent selection; advanced WebVTT styling/layout is rejected explicitly; timeline validation and immutable publishing are not yet available. The API 26/wider device matrix remains INT-2. No real SAF import/UI is introduced. Next task is **E3-T2**, followed by E2-T2 only after its dependency is fulfilled.
