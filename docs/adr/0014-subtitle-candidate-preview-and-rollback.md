# ADR 0014: Subtitle candidate preview, publication and rollback

## Scope and sources

E2-T3 builds on E2-T2 `0fc58a0`, E3-T2 `09dbcba`, PRD v2.2 IMP-03..05 and
the checklist `SubtitlePreviewScreen` baseline. It adds no Room table or schema
version. Parsing, timeline validation, tokenization and immutable publication
remain owned by E3-T1/E3-T2.

## Candidate discovery and authorization

`ACTION_OPEN_DOCUMENT_TREE` is offered only after a video is selected. The user
explicitly grants the selected directory; `SafImportDocuments` enumerates only
its immediate children and matches a case-insensitive filename stem plus `.srt`
or `.vtt`. Results are sorted deterministically. No neighboring or otherwise
unselected directory is inspected. Persisted tree grants authorize matching
child document URIs; cancellation releases only grants acquired by the current
session. A filename match is a candidate, never an association: the parent must
select it, inspect its parsed preview, acknowledge the media/subtitle selection,
and confirm publication.

Manual subtitle selection remains available from preview. Encoding choices
reuse `SubtitleParser` (`auto`, UTF-8, GBK, UTF-16 LE/BE); undecodable or malformed
input stays visible as a structured parser message and cannot be published.
The UI shows up to ten cues, time ranges, all current timeline findings and the
detected encoding. It does not silently drop malformed cues.

## Timeline calibration

The preview exposes a parent-adjustable overall offset from -5,000 to +5,000 ms
in 100 ms steps. These bounds and step size are UI calibration affordances, not
PRD-mandated limits. Applying an offset re-runs E3-T2 `TimelineValidator.previewOffset`;
negative/overflow or otherwise blocking results disable publication. The
adjusted cue timestamps are saved only after explicit confirmation under the
newly published version's IDs. The source remains unchanged.

## Replacement and rollback

Selecting "reuse existing" while a valid subtitle is selected replaces/attaches
it to the selected video inside the existing import transaction. The current
English track is reused where present; publication always creates a new draft
and a new immutable published version. Existing versions and lines are not
updated or deleted. After publication the active import session offers one
rollback action:

- If this publication replaced a current subtitle, rollback copies the previous
  published content into a fresh draft and publishes that as a new current
  version. The replaced version remains intact and addressable.
- If this was the first association, rollback atomically clears the track's
  current-version pointer and restores the video to `NEEDS_SUBTITLE`. The
  published version is retained as history. The action is compare-and-set on
  the expected current version; after success the session consumes its rollback
  snapshot, so repeating the action is a no-op.

The rollback affordance belongs to the active in-memory import session; leaving
or recreating that session ends the one-action rollback window. PRD specifies
one rollback but no time window or cross-process recovery semantics. A durable
rollback window would need explicit product policy and persistent operation
metadata, so this task does not invent either.

## Initial verification (superseded by device follow-up below)

JVM/Robolectric coverage exercises deterministic candidate presentation without
writing rows, invalid-encoding preview rejection, offset persistence, immutable
old lines, creation of a replacement version, restoration as a new version, and
initial-association rollback. Three Compose tests were added for explicit
candidate selection/confirmation, malformed-preview blocking and the single
rollback action. They compile successfully.

On the Xiaomi 10S (`52072092`), `:app:connectedDebugAndroidTest` started
`candidateRequiresExplicitSelectionAndParentPublishConfirmation`, but the
instrumentation emitted no test-finished status and remained at 0/3. After about
3m26s the run was stopped. The device's current window was MIUI Home;
`always_finish_activities` returned `null` (not enabled), and no permission dialog
or crash appeared in the captured logcat. The report is marked `TEST_INTERRUPTED`,
so no Compose test is claimed as passed on-device. The failure is consistent with
the device/MIUI Activity launch limitation, but the precise cause remains
unconfirmed. No permission settings were changed. Room remains v3; no migration
or exported schema was changed.

## Decision and remaining checks

The flow is feasible without broad storage permissions or copying subtitle/video
files. Its security boundary is the parent-gated E2-T2 route plus the specific
SAF tree grant. Providers that do not support listing children for a tree URI
can return no candidates or an actionable provider error; manual OpenDocument
selection remains the fallback. Device-provider behavior should be confirmed on
other SAF providers during device-matrix work. The follow-up below closes this
task's Xiaomi 10S verification.

## Device follow-up: 2026-09-26

After both APKs were installed with user confirmation and the approved MIUI
auto-start/background-launch appops were reapplied, the installed runner could
start the test Activity. Gradle Connected reinstalls APKs and can reset those
settings; its cleanup can also remove the app. The successful run therefore used
the installed AndroidJUnitRunner directly, as in ADR 0008. This is not a claim
that the unfiltered Gradle Connected suite passed.

The first completed three-test run exposed two real harness failures: the
stateless screen fixture did not feed the rights checkbox event back as state,
and an assertion targeted content outside the viewport. Neither failure proved
the proposed system-file-picker deadlock. Screen tests now feed back the rights
state and scroll before interaction. Manual file/directory selection tests only
collect UiEvent callbacks: they cannot launch a real picker or permission dialog.

Screenshots at font scale 1.3 exposed clipped encoding options. Production now
uses FlowRow instead of a fixed Row for those chips; all five labels remain
visible. Five Compose tests cover selection, explicit rights/confirmation,
encoding/error display, rollback, manual replacement and the first ten of twelve
cues. All use synthetic static inputs.

A separate opt-in SubtitleCandidateDeviceTest deliberately exercises the real
system OpenDocumentTree provider. It selects only Download/LazyEng-E2T3-Fixtures,
using generated 5-second 320x240 video and a two-cue synthetic SRT. It runs the
production SAF adapter and import/publish/rollback coordinator against an isolated
in-memory Room database. Its authorization fixture bypasses the parent gate;
this test does not claim to revalidate PIN authentication. Observed assertions:

- A real tree grant discovers one same-name candidate; no subtitle is selected
  automatically and no video row exists before explicit confirmation.
- Selecting and inspecting the candidate displays its actual parsed text and
  millisecond timestamps. Publication creates READY content.
- Rollback restores NEEDS_SUBTITLE, removes the rollback affordance, preserves
  the published version and all old lines, and a repeated request is a no-op.

The final combined run on Xiaomi 10S / API 31 completed **6 tests, 0 failures,
0 skips in 15.349 seconds**, with runner timeout_msec=120000. System picker button
waits are independently bounded at 15 seconds. The host build, test APK build,
pure-Kotlin tests, testDebugUnitTest and lintDebug passed; aggregate host XML
reports contain 133 tests with zero failures/errors/skips (including up-to-date
tasks). Room remains v3 and schema exports are unchanged.

Reproduction commands are in apps/android/README.md. Captures were exported to
apps/android/app/build/reports/e2-t3/ and visually inspected, including
encoding-error-font-1.3.png, first-ten-cues-font-1.3.png,
real-tree-candidate.png, real-tree-parsed-preview.png, real-tree-published.png,
and real-tree-rolled-back.png. real-tree-result.txt records the checked outcomes.
Generated evidence/media are local build artifacts, not committed binaries.

E2-T3 acceptance is complete on this development device. Other provider/target
device coverage remains INT-2 work, not an unexecuted E2-T3 check. The old
unfiltered Connected run is still not a passing result; older opt-in device
acceptance scenarios require their own fixture/process-death/removal setup.
