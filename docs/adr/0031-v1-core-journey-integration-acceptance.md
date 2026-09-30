# ADR 0031: V1 Core Journey Integration Acceptance

## Status

Implemented; core and companion device evidence recorded below. Full INT-1 acceptance remains incomplete because cold-install onboarding was not run against an empty application data directory.

## Context

The INT-1 checklist defines eight scenarios across onboarding, SAF import, subtitle publication, playback, local dictionary lookup, offline AI fallback, vocabulary/review, lookup deduplication, profile isolation, process restoration, and protected parent management. Existing device tests cover several platform-specific edges independently; INT-1 adds a single shared-database journey to detect cross-feature contract drift without opening or clearing the installed family's database.

## Test design

`EndToEndCoreJourneyDeviceTest.profileImportPlaybackLookupReviewRestartIsolationAndParentControlsStayConsistent` creates a uniquely named Room database and two synthetic child profiles on the device. It uses:

- The production `VideoImportCoordinator`, Room v6 repositories, subtitle parser/publisher, and import transaction. The content source is an in-test `ImportDocuments` fake to avoid a system picker and arbitrary user documents.
- The packaged ECDICT asset and application lookup API for a real `running → run` lookup, pronunciation/definition assertions, and a measured sub-300ms lookup.
- The production `LookupEventCoordinator` and SQLite event repository for three concurrent same-key requests, asserting a single underlying lookup and one persisted event.
- The production `PlayerViewModel`, `PlayerScreen`, `VocabularyRepository`, `VocabularyScreen`, `FlashcardReviewViewModel`, and `FlashcardReviewScreen`; only the playback engine is deterministic fake media state. The AI callback deliberately returns unavailable to assert offline fallback without a network request.
- Separate Profile A/B repository reads, persisted import/progress/review/event checks after closing and reopening the disposable database, and the production `ParentSession` authorization plus `VideoCatalog` child-visibility filter. A wrong deletion PIN is verified to leave both profiles intact.

The test always closes view models/scopes/database and deletes only its randomly named test database. It does not uninstall the app, clear app data, or touch the production `family.db`.

## Companion platform coverage

The continuous journey intentionally does not claim to automate the system DocumentsUI picker or cold first-run through `MainActivity`: doing so would either require flaky real UI automation or risk resetting the user's installed household. Existing tests remain the platform-specific evidence for those boundaries:

- `OnboardingFlowTest` covers onboarding and relaunch restoration under explicit opt-in arguments.
- `ImportDeviceTest.importViaSystemPicker` exercises real DocumentsUI/SAF, production resolver and media probing; `recoverAfterProcessDeath` is a separate force-stop/relaunch stage.
- `PlayerScreenTest.bundledDictionaryResolvesRealEntriesAndPreservesCapturePayload` exercises the real packaged dictionary-to-capture path; other PlayerScreen tests cover rendering and controls.
- `ProfileIsolationDeviceTest` and `ParentManagementDeviceTest` cover cascade isolation and parent management UI.

These companion tests must be included in the INT-1 device acceptance run where their prerequisites are available. A single green core-journey test is not by itself proof that all eight checklist scenarios have passed.

## Decisions and limitations

- Test fixtures use a deterministic household, SRT cue, and fake `ImportDocuments`/`PlayerEngine`; they are not real family content or a real video decode test.
- The journey proves Room persistence across database close/reopen, not Android process death inside this one method. Process-death restoration requires the opt-in two-stage `ImportDeviceTest` procedure.
- The parent stage proves PIN-gated deletion rejection and the authorized hide/query contract. Successful child deletion and UI cancellation remain covered by profile/parent tests; they are not silently conflated with a database restart.
- AI is explicitly offline in the journey. No external provider or real key is used.

## Acceptance evidence

On Xiaomi 10S (`52072092`), using manual APK installation followed by AppOps restoration, `am start -W`, and direct `am instrument` (no Gradle connected task):

- `EndToEndCoreJourneyDeviceTest#profileImportPlaybackLookupReviewRestartIsolationAndParentControlsStayConsistent`: **1/1 passed** in 3.61s. Assertions cover READY import and durable ImportJob, real local `running → run` dictionary lookup under 300ms, one event from three concurrent same-key requests, player capture/progress, offline AI fallback, vocabulary context and `OpenPlayer(videoId, timestampMs)` effect, flashcard review scheduling, profile separation, wrong-PIN deletion rejection, visibility filtering, and Room close/reopen integrity.
- `ImportDeviceTest#importViaSystemPicker`: **1/1 passed** in 25.107s with the real DocumentsUI picker and generated local fixtures.
- After `am force-stop com.lazyeng.family`, `ImportDeviceTest#recoverAfterProcessDeath`: **1/1 passed** in 1.581s; persisted URI access and import state survived, and explicit permission release mapped to the permission-revoked error.
- `ProfileIsolationDeviceTest`: **1/1 passed**; deletion purged one profile's rows while sibling records remained intact.
- `ParentManagementDeviceTest`: **4/4 passed** for protected member removal UI, visibility, storage summary, and destructive video confirmation.
- Final `testDebugUnitTest` and `lintDebug`: both **BUILD SUCCESSFUL**. Debug and AndroidTest APK assembly also succeeded. `connectedDebugAndroidTest` was not run.

The app's existing installation/data was not cleared to stage a cold first-run onboarding journey. `OnboardingFlowTest#setupMismatchDraftRecreationThrottleResetAndHome` explicitly requires a previously unused app data directory, so it was deliberately not invoked. This is the remaining INT-1 acceptance gap; the passing integration and companion tests do not imply a fresh-install onboarding pass.
