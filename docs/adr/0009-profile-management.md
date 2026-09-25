# ADR 0009: Profile management and scoped lifecycle coordination

## Scope and sources

E1-T3 only. Baseline: `codex/e1-t2-onboarding-pin-setup` at `0766fa8`; working branch: `codex/e1-t3-profile-management`.

Sources: the full PRD v2.2 in `docs/specs/`, PRO-02 through PRO-06, data model I and parent-session requirements; checklist sections 3.1, 4.1, 4.2 and E1-T3; ADR 0007 and 0008. Room stays at **v1**, with exactly the existing Family/Profile entities, unchanged exported schema and tombstone semantics. No downstream progress, lookup, vocabulary, review, statistics or worker implementation is added.

## Decisions and assumptions

### Directory versus private data

`ProfileDirectory.choices(familyId)` is a deliberately limited picker projection: ID, nickname, avatar, role and English level. Children need these labels to choose a member, as required by the picker baseline. It does not expose preferences, age mode or learning records. It always requires a household and excludes tombstones. It is not an unscoped all-Profile repository method.

All full Profile reads/writes retain the established `familyId` and explicit `profileId` repository signatures. The parent overview use case verifies a memory-only `ParentAuthorizedScope` before and after scoped reads. Overview displays only Profile-owned fields, never fabricated learning counts. Domain models, not Room rows, enter UiState.

### Parent privileges and fresh confirmation

Role is descriptive metadata, never authorization. Opening management always verifies the existing family PIN using E1-T2's production PinService, encrypted verifier and persistent throttling. Creating/editing requires an active scope. Deletion requires a second fresh PIN, even immediately after management unlock. PIN digits are memory-only and wiped on submission, cancellation and backgrounding. Busy state disables duplicate submission and displays verification feedback during PBKDF2.

Scope tokens are identity- and generation-bound, cannot be constructed outside `core/security`, expire after five minutes of inactivity and are revoked on background/exit/switch. Revocation while an asynchronous check is pending cannot grant a late session. No recovery code, new KDF, iteration change or role-based bypass is introduced.

### State and switching

`ProfilesUiState` / `ProfilesUiEvent` / `ProfilesUiEffect`, a ViewModel, lifecycle Route and stateless Screen implement UDF. Before suspension, switching replaces the entire state with Loading: previous nickname/avatar/settings, member list and editor are absent. Cancellation checks prevent abandoned async reads from publishing late UI data.

The current pointer is still E1-T1 DataStore. After successful selection, `ProfileChanged(familyId, previousId, profileId)` is published through an application-scoped SharedFlow. The stream supports multiple subscribers, has no replay of old transitions, and uses explicit nullable destination IDs when a selected member disappears. Future subscribers must initially resolve the durable current pointer and clear old state before loading a new scope. E1-T4 will integrate those subscribers; this task tests the event contract without pretending they exist.

### Atomic extensible deletion

`ProfileDeletionCoordinator` registers uniquely named cleaners and serializes requests. The application currently registers only the Profile-body cleaner, which calls the existing tombstone repository method. All registered cleaners execute inside the same Room transaction. Cancellation, an exception, or an authorization check failing before commit rolls back all database writes, including an already-written tombstone. SQLite transaction recovery supplies process-death atomicity; tests explicitly exercise cancellation and failure after the body cleaner.

This contract is for transactional database cleanup, **not arbitrary external effects**. Future filesystem/WorkManager cleanup must record durable cleanup intent in a later task's owned schema, then process it after commit. Do not perform irreversible I/O from a cleaner and claim cross-system rollback. No outbox table or later schema is created here.

Room and DataStore do not share a transaction. After deletion, the existing selection store revalidates and clears a stale pointer. If interrupted between the tombstone and pointer cleanup, next startup does the same validation and returns the safe picker, never a deleted/default Profile.

### Completed initialization and available choices

A non-sensitive `completed` boolean is added to the existing onboarding DataStore. Existing initialized E1-T2 installations mark completion after checking the original Profile, without overwriting a valid selection. Once marked, deletion of the first or last Profile never resurrects onboarding or a deleted child; zero available members shows an empty picker with a parent-gated create action. Missing PIN/family still fails closed. The completion flag persists independently of the selected member and does not require a Room migration.

PRD does not close the value sets for `age_mode` or English level. They remain editable nonblank strings; existing unknown values are retained. New drafts start with the existing onboarding defaults `child` and `Power Up 2`. Roles reuse the existing CHILD/ADULT/PARENT enum. Avatar choices reuse onboarding's sun/moon/star; null or unknown existing IDs are preserved unless explicitly changed. The nickname limit remains the existing 24-character onboarding convention. Editing a Profile preserves its creation timestamp and preferences.

No dedicated avatar card existed in T0-4. A generic token-based avatar/card was added to `core/designsystem`, alongside extraction of onboarding's keypad into a shared visual component. No second PIN verifier or separate styling system was created. Keyboard targets remain at least 48 dp, cards use existing large rounded shapes, content scrolls with insets/IME support, and no phone-shell UI is drawn.

## Verification

Instrumentation fixtures use real Room repositories, DataStore, Keystore and PinService in isolated test storage, generated in-memory PINs, and synthetic member labels. They never overwrite the installed family's record. ComponentActivity hosts the production Route/Screen and ViewModel; this is an integration harness, not a claim that a test-only page is the application's MainActivity.

### Host results

Executed with the existing JDK 17 and SDK, offline:

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug --offline --console=plain
```

Result: BUILD SUCCESSFUL, 25 seconds, 881 actionable tasks. **72 tests passed; 0 failures, 0 errors, 0 skipped.** This includes seven Profile coordinator/ViewModel cases, four real Room deletion/directory cases, three parent-session cases, plus onboarding completion/recovery and prior regression tests. Lint passed; no Room v1 schema or frontend changes.

### Device acceptance status

The first install attempt on the online 10S was rejected with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` while the window was AOD/keyguard. `always_finish_activities` returned `null`, not 1. Screen timeout was set to 600000 ms. The user was asked to unlock and allow USB installation; no root/signature bypass was attempted. Device Compose results and visual evidence remain pending until installation is permitted; host success alone does not complete this task.

## Remaining boundaries

No downstream module cleanup or statistics are claimed. E1-T4 remains deferred until its downstream dependencies exist. INT-2 still owns API-26/target-device matrix checks and flagship PIN calibration. Unsaved Profile edits are memory-only; backgrounding revokes parent authorization and safely discards that edit rather than persisting an unauthorized draft.

Next permitted task after acceptance: **E2-T1**. Do not start it automatically.
