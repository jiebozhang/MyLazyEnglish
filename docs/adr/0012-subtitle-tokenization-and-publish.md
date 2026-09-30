# ADR 0012: Timeline validation, token ranges and immutable publication

## Scope and baseline

E3-T2 is based on E3-T1 `f5a76cd`, on `codex/e3-t2-timeline-tokenizer-publish`. Sources are PRD 8.6/8.10 and data model I, checklist E3-T2, and ADRs 0010/0011. This task implements pure timeline/token logic, offset preview, publication and the real subtitle readiness adapter. It does not implement SAF import, UI, a dictionary or linguistic lemmatization.

Room stays **v3**. No columns, SQL triggers, migrations or exported schema files are changed. Immutability is enforced in Repository/DAO transactions as permitted by the task, rather than silently changing the already-exported schema or consuming the next task's schema version.

## Timeline decisions and assumptions

`TimelineValidator` accepts domain SubtitleLine values and returns a deterministic TimelineReport. Findings contain severity, issue, line ID, sequence and (for overlaps/order warnings) a related line ID. They expose the established AppError/code/message-key shape without including subtitle body text in diagnostics. Serious errors block publication; warnings remain in the publication result and do not silently become errors.

- Errors: empty timeline, negative time, end before start, zero duration, duplicate/invalid sequence or duplicate line ID, and blank text. Zero duration is an explicit implementation decision: a cue with no display interval is not playable learning content.
- Warnings: short/long duration, long text, sequence order disagreeing with start-time order and overlapping intervals. PRD does not prescribe numeric thresholds; configurable advisory defaults are **300 ms**, **10,000 ms**, **120 Unicode code points**, and **0 ms overlap tolerance**. Threshold equality is not a warning. These are adjustable engineering defaults, not invented mandatory product rules. The default publisher uses this policy; no warnings cause hidden splitting, shortening or time repair.
- Validate in sequence order without mutating input. Overlap checking uses a start-time sweep retaining the furthest active end, so nested cues are detected. Each affected cue reports one representative conflict, not every possible pair (avoids quadratic output). Touching endpoints do not overlap. Invalid negative/reversed intervals are excluded from overlap arithmetic but retain their errors.
- `previewOffset` creates a copy, preserving text and IDs for comparison. Negative results are returned with blocking findings, never clamped. Checked addition detects overflow and returns the unchanged input copy plus a blocking report instead of a partly shifted result. Persisting calibration requires the caller to assign **new version and line IDs** and save/publish a new draft. Old versions remain addressable; preview itself performs no I/O.

TimelineValidator is pure Kotlin/JDK. It uses no Android APIs, clock, database, network or randomness.

## Token contract

`SubtitleTokenizer` uses the JVM regular-expression engine to segment Latin-script words, dotted abbreviations, numbers and other non-whitespace characters. A SubtitleToken contains `surface`, `normalized`, `start`, `end`, and kind WORD/NUMBER/PUNCTUATION/OTHER.

- Ranges use **UTF-16, end-exclusive** indices, matching Kotlin String and Compose text selection. Always `text.substring(start, end) == surface`, including supplementary characters preceding the word and combining marks inside it. Normalization never recalculates the source offsets.
- Internal apostrophes keep contractions and possessives together: I'm, don't, Alex's, James's. Straight/curly/modifier apostrophes normalize to a straight apostrophe. Internal ASCII/nonbreaking Unicode hyphens keep well-known as one word and normalize to ASCII hyphen. Dotted abbreviations such as U.S.A., U.K and e.g. retain their dots. Surrounding punctuation is a separate token, so it does not contaminate word lookup ranges.
- WORD normalization is NFC plus Locale.ROOT lowercase and the punctuation substitutions above. It does **not** expand ambiguous contractions, remove possessives, infer proper-name status or guess a lemma (children/went remain unchanged). Actual dictionary-backed word-form resolution belongs to the dictionary task. This is an exact surface-to-lookup-normalization mapping, not a false claim of morphological analysis.
- Whitespace is omitted; punctuation/number/other tokens retain source ranges for rendering and hit-testing. No automatic multiword phrase merging occurs; future selection can use the unchanged original range. Empty/whitespace-only input produces an empty list. Common letter-based abbreviations, contractions, possessives, decimal numbers, accents and emoji offsets have explicit tests; this is not a universal multilingual NLP tokenizer.

## Publication and immutability

`SubtitleCatalog` now implements the pre-existing SubtitlePublisher contract. Its new result-bearing `publish` returns either the published domain object plus the full validation report, or a classified AppError plus an optional validation report. The compatibility `publishDraft` method returns the domain version or throws SubtitleOperationException; new presentation/use-case code should consume `publish` to retain warnings/line findings. `deleteDraft` returns AppResult and takes explicit profileId.

The publishing transaction:

1. Resolve the version within the active Profile's household and load its ordered lines.
2. Validate the timeline and require nonblank source hash, parser version and source encoding.
3. Update only DRAFT -> PUBLISHED with the caller-supplied Instant (future use cases obtain it from the injected Clock).
4. Select that version as the track's current published version in the same Room transaction.

Any failure rolls back both state and pointer. A repeated call for an already-published version returns its original timestamp and **does not** reactivate it if a newer version is now current. New publication does not edit/delete any old version or line, so historical version/line references remain resolvable.

Drafts support replacing/adding/removing their lines, including an empty intermediate draft, and deleting the draft with its lines atomically. Empty drafts cannot publish. Published writes/deletes fail with **SUBTITLE_VERSION_IMMUTABLE**, not a successful no-op. Invalid timelines return **SUBTITLE_TIMELINE_INVALID** and detailed findings.

DAO raw version updates and line inserts/deletes are protected. Public draft mutations validate scoped DRAFT state inside @Transaction; the publication UPDATE contains a DRAFT predicate, so a later call cannot alter a published timestamp. Repository mutation also checks state, and keeps all ownership/ID collision guards from E3-T1. Publication/edit races are serialized by Room transactions. Cancellation is propagated; expected validation/storage failures reuse classified errors. Direct arbitrary SQL by a privileged debugger/root process is outside this application-level protection; no resistance to device-owner database tampering is claimed.

Track metadata is distinct from immutable version metadata; the existing household-scoped track editing contract remains unchanged. Generic repository track writes still cannot change video ownership or the current-version pointer. Only publication selects a new current version. Parent PIN authorization belongs in the future import/publishing use case, not a new PIN bypass or UI in this data layer.

## Readiness integration

`RoomSubtitleReadinessChecker` implements ADR 0010's `hasPublishedEnglish(profileId, videoId)`. It considers only tracks belonging to that video and active household scope, with an English language tag (`en` or hyphenated English tag), whose **current** version belongs to that track, is PUBLISHED, has a publication timestamp, and has a nonempty/error-free timeline. Warnings are allowed. Historical unbound versions, drafts, empty/corrupt legacy records, non-English tracks, missing videos and foreign/deleted Profile scope do not qualify.

`FamilyDatabase.videoRepository` now uses this real checker by default. Media accessibility remains the E2-T2 dependency and still defaults to false; publishing subtitles alone does not claim a video is READY. Tests inject accessible media and verify the normal PROCESSING -> READY path actually uses the published subtitles. Explicit checker injection remains available for existing tests/adapters.

## Verification

Focused and full checks used the project's configured JDK 17 and cached dependencies:

```text
./gradlew :core:common:test :core:database:testDebugUnitTest --offline --console=plain
./gradlew :app:assembleDebug :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
```

Full result: **BUILD SUCCESSFUL**, 26 seconds, 851 actionable tasks. **116 host tests passed, 0 failures/errors/skips**, including 22 new tests: TimelineValidatorTest 7, SubtitleTokenizerTest 6, SubtitlePublishTest 9. Database Lint reports **No issues found**. Local full output is in ignored `app/build/reports/e3-t2-validation.log`; [checked-in verification summary](evidence/e3-t2/verification.txt).

Coverage includes valid/reversed/negative/zero timelines; threshold edges; nested overlap/order warnings; immutable offset preview and overflow; precise substring offsets, punctuation/contractions/hyphens/possessives/abbreviations, emoji/combining marks, whitespace and Turkish-locale independence; draft CRUD; immutable metadata/lines through Repository and DAO; foreign/deleted scope; current English readiness; invalid legacy records; replacement/history and idempotence; forced pointer-write failure rollback; competing edits/publications; and persistence/readiness/immutability after closing and reopening a real file-backed Room database. Existing host migration tests still pass. Exported schemas 1/2/3 are byte-unchanged.

No device instrumentation or visual test was run for this task: no UI/schema change was introduced, and the required new coverage is pure JVM plus Robolectric native-SQLite repository tests. E3-T1's prior device migration evidence is not presented as a new E3-T2 run. Device-matrix validation remains INT-2.

## Outcome and next dependency

E3-T2 acceptance is complete within this scope. Warning thresholds remain product-tunable assumptions; actual linguistic lemmatization and UI warning presentation remain downstream work. Next is **E2-T2**, which can now integrate SAF import, subtitle preview/publication and the real media readiness adapter. No next-task implementation, remote push or PR creation is included.
