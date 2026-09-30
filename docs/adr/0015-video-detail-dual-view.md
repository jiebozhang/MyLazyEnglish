# ADR 0015: Video detail child and parent views

## Scope and sources

E2-T4, based on E2-T3 f01294d on codex/e2-t4-video-detail-dual-view.
Sources: complete PRD v2.2, especially 8.3, VID-01..05 in 8.8, data model I,
17; checklist 3.1 and video-detail state matrix; ADR 0010/0014; prototype
frontend/src/pages/LibraryPage.tsx. Room remains v3 with no schema changes.

## Mapping and boundaries

| Prototype / requirement | Compose / state | Events / effects | Acceptance |
| --- | --- | --- | --- |
| LibraryPage video card, level and availability | ChildVideoDetailRoute / ChildVideoDetailScreen; ChildVideoDetail projection | Refresh, Watch; Watch(videoId, positionMs) | Ready guard, progress, explicit estimated/manual level |
| PRD parent detail (no full prototype) | ParentVideoDetailRoute / ParentVideoDetailScreen; ParentVideoDetail projection behind PIN | Verify, Repair, RequestArchive, ConfirmArchive | Source, version history, rights, archive confirmation |
| Existing import preview | ImportRoute / ImportScreen, repairVideoId | OpenDocument, Confirm | Existing version publication, bound repair target |

Screens are distinct stateless composables, not an isParent conditional inside
one screen. Route binds ViewModel, lifecycle and effects. Repositories return
domain models; no UI references Room entities or DAOs. The library module owns
detail projections and state management. Application assembly supplies repositories,
live SAF checks and an independently revocable ParentSession.

The import completion screen opens child details. The child's parent-management
entry opens the separate PIN-gated route. Repair reuses E2-T3 import/preview with
the selected video's source prefilled only after PIN authentication; confirm is
bound to DuplicateChoice.Reuse for that video. It cannot create or choose another
duplicate. Existing immutable publication and rollback logic is unchanged.

The Watch effect carries the validated video ID and Profile-specific resume
position. E4-T1 owns the player; until that task supplies its destination, app
assembly explicitly says the learning player is not yet integrated. This is not
a claim that playback is implemented or tested. EH owns the persistent library
navigation/list, not this task. No synthetic videos are inserted into production.

## Data and safety decisions

- A source-free child projection contains title, duration, topics, labelled level,
  progress and a friendly availability enum only. No URI, version ID, internal
  error code or rights metadata is passed to the child Screen.
- READY alone is insufficient: playable, actual current published English
  readiness and live source access must all pass. Watch rechecks before emitting.
  Archived content remains resolvable but has no Watch action.
- SafImportDocuments.checkAccess uses the existing classified SAF access path
  and closes its descriptor. It does not hash/copy the video. PERMISSION_REVOKED
  and SOURCE_FILE_MISSING remain distinct. Child messages omit diagnostics;
  parent advice explains reauthorization/reselection and retaining history.
- The existing state machine only allows READY -> ARCHIVED. This UI does not
  invent a FAILED/NEEDS_SUBTITLE -> ARCHIVED transition. Archive confirmation
  explains preserved progress, versions and original files. Failed/unready media
  can be reselected/imported; replacing the source reference beneath READY is
  not introduced through an unguarded metadata write.
- Add only SubtitleCatalog.listVersions(profileId, trackId), implemented by a
  household-scoped read query ordered by publication time and ID. It includes
  immutable historical and draft domain models without selecting a new current
  version. No table, column, migration or version-number field is invented.
- Display real version IDs (the model has no numeric revision counter). English
  word count uses WORD tokens from the current published English version in
  200-line pages; it does not trust potentially stale Video metadata counts.
- usageNote is shown verbatim for the authorized parent, or 'not provided'. The
  existing ownerType and rights acknowledgement are shown separately; neither
  is treated as a legal certification. Missing cover uses an explicit local-video
  placeholder. Missing progress stays absent, not a fabricated statistic.
- LevelSource controls manual/estimated wording. Unknown source hides the grade;
  this task does not implement an estimation algorithm or imply official CEFR.
- Profile scope is checked before/after reads and before writes; ProfileChanged
  invalidates the loaded projection. Route stop/disposal revokes the parent
  session, cancels reads and clears both projections and PIN memory. A session
  expiry ticker removes parent data; PIN throttling reuses PinService.

## Tests and reproduction

Host tests cover the readiness/status matrix, grade provenance, real Room-backed
word/history/progress projection, archive retention, scope loss during a read,
separate missing/revoked errors, PIN loading and background clearing. The scoped
version-history test verifies draft and published history without changing the
current version. DetailRepairChoiceTest rejects creating/selecting a different
duplicate from a repair session.

VideoDetailScreenTest runs at font scale 1.3 with synthetic inputs. It checks
child privacy and availability, parent PIN/loading, history/rights, archive
confirmation, missing-source advice, and ACTION_OPEN_DOCUMENT interception via
Espresso Intents. The picker test creates a temporary synthetic SRT and returns
its URI without opening DocumentsUI; it verifies the shared launcher/result
contract, not persistent permission acquisition (owned by the prior SAF tests).

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

On MIUI, reapply the owner-approved settings in ENV_NOTES.local.md after both
installations, then run the installed runner, with no additional Gradle reinstall:

```text
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.VideoDetailScreenTest -e timeout_msec 120000 com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

Generated screenshots go to files/video-detail-evidence/ in the target package;
export into app/build/reports/e2-t4/. They contain synthetic fixtures only.
Production FLAG_SECURE is unchanged. No app uninstall or data reset is needed.

## Execution results (2026-09-26)

- Full build/unit-test/Lint command above: BUILD SUCCESSFUL in 1m55s, 910 tasks.
  Aggregate host XML reports: 140 tests, zero failures/errors/skips, including
  up-to-date tasks. Five new library tests, one history test and one repair-choice
  test account for the seven added host cases.
- Espresso Intents 3.6.1 was initially missing from the offline cache. It was
  fetched through the pre-existing local proxy without storing proxy settings in
  the repository. Both APKs subsequently built offline and installed with -r.
- Xiaomi 10S / API 31: final VideoDetailScreenTest run passed 6/6 in 12.339s,
  including the intercepted picker. SubtitlePreviewScreenTest regression passed
  5/5 in 10.887s. Both use the installed AndroidJUnitRunner after MIUI appops
  preflight, with timeout_msec=120000; this is not an unfiltered Gradle Connected
  pass. No test required manual picker interaction.
- Initial detail run passed 5/6; the remaining failure was screenshot root
  ambiguity with an open dialog, after the interaction assertions had passed.
  A root-only capture then used the wrong window coordinates. Dialog evidence
  now uses UiAutomation's whole-screen capture on the synthetic test Activity;
  other screenshots retain Compose root capture. No production secure-window
  flag is disabled. The corrected dialog image was visually inspected.
- Captures in app/build/reports/e2-t4/: child-ready-font-1.3.png,
  child-unavailable-font-1.3.png, parent-gate-font-1.3.png,
  parent-history-font-1.3.png, parent-missing-font-1.3.png and
  archive-confirmation-font-1.3.png. Text and primary actions fit at 1.3 font scale;
  long pages scroll. device-run-final.txt and import-regression.txt contain raw
  runner output. Media/screenshots/APKs remain local generated artifacts.

| E2-T4 acceptance | Result |
| --- | --- |
| READY with published English and live access exposes Watch/Continue | Passed: host matrix/projection and child Compose action |
| Processing, no subtitle, failed or missing source has no Watch/diagnostic leakage | Passed: exhaustive host matrix and non-ready Compose states |
| Estimated/manual grade clearly identified | Passed: provenance unit test and visible Compose assertion |
| Parent source/history/word count/status/rights/repair/archive | Passed: Room-backed projection, PIN clearing, scoped history and Compose confirmation |
| Build, tests, Lint; picker interactions stubbed | Passed: commands and counts above |

E2-T4 is complete within its task boundary. Actual playback remains E4-T1 and
the library navigation remains EH; neither is claimed as implemented here.
Wider API/provider coverage remains INT-2. Next task: EH-T1, not started.
