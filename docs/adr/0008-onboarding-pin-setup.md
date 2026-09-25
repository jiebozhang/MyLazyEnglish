# ADR 0008: Recoverable onboarding and parent PIN

- Task: E1-T2; PRD v2.2 PRO-01 and security section 17; checklist sections 3.1, 8 and E1-T2.
- Baseline: E1-T1 `5afff6d`, branch `codex/e1-t2-onboarding-pin-setup`.
- Status: E1-T2 accepted on the development Xiaomi 10S (follow-up branch 3a); host, Compose, visual/large-font and device credential restart checks passed. Flagship KDF recheck remains assigned to INT-2.
- Date: 2026-09-25.

## Boundaries and decisions

Add `core/security` for Android Keystore storage and PIN verification. This is a minimal documented module extension: `core/common` remains platform-independent and `core/datastore` continues to own non-sensitive preferences. The UI depends on neither Room entities nor HTTP DTOs. Application assembly owns the Room database, repository/store facades and one serialized PinService. The onboarding coordinator calls E1-T1 repositories with explicit family/profile IDs. Room v1 and its exported schema are unchanged; no PIN table, migration or destructive recovery is introduced.

`feature/onboarding` separates UiState/UiEvent/UiEffect, ViewModel, coordinator and stateless Screen; Route handles lifecycle and state collection. Navigation Compose connects onboarding to a guarded home placeholder only. Profile editing/switching/deletion and real home content remain out of scope.

The three user-visible steps are family/PIN, child Profile, then home. PIN confirmation is a substate of step 1, not an additional page. A new process during step 2 requires the parent PIN again, without discarding the saved child draft. Rotation/background retain the step's ViewModel and non-secret fields, but clear PIN buffers (confirmation returns to PIN entry). Draft IDs, creation time, nickname, avatar and level are atomically persisted to DataStore before the corresponding UI value is updated. A process killed before an in-flight write completes may lose that not-yet-acknowledged edit, not the last displayed saved value.

Family has no PRD name field, so no invented household-name input/column is added. Avatar IDs are presentation-only sun/moon/star choices; nickname has a 24-character UI bound, not a new domain constraint. Child mode/role and the initial editable Power Up 2 level follow the target audience. Completion is derived from a configured verifier plus a live, scoped Profile and successful current-selection write, not a saved navigation flag. This bootstrap selects its first child only; later E1-T3 owns multi-profile selection.

PIN commit precedes idempotent Family creation. If interrupted between them, reopening requires PIN verification and then completes the missing Family write. A missing verifier for an existing Family, malformed draft, invalid credential metadata, failed Keystore access or bad GCM tag fails closed with safe retry/error UI. No silent database reset, fallback family, recovery code or plaintext-storage path exists. The setup screen explicitly warns that a forgotten PIN requires clearing app data and loses local records.

## Credential and throttle storage

- PBKDF2-HMAC-SHA256, fresh SecureRandom 16-byte salt, 256-bit verifier, per-record algorithm/version/iterations. Constant-time verifier comparison.
- New records use 210,000 iterations based on the actual measurements below; existing records use their own persisted parameters.
- Raw PIN buffers exist only in memory, not UiState, SavedStateHandle, DataStore, files, logs, effects or navigation arguments. Buffers and PBEKeySpec passwords are cleared after use/background/disposal. Managed memory cannot promise forensic zeroization of all runtime copies.
- Only the one-way verifier record is encrypted using Android Keystore AES-256-GCM. Encrypting this verifier is not reversible storage of the PIN. `noBackupFilesDir/parent-pin.enc` uses AtomicFile; app backup remains disabled. No plaintext fallback on initialization/decryption failure.
- Failures 1-4 have no wait; failure 5 locks 30 seconds, then 60/120/240/480/900 seconds, capped at 900. Count and epoch-millisecond deadline are authenticated within the same encrypted record. A successful comparison durably resets both.
- Reserve each attempt durably before expensive KDF work so killing the process cannot erase an in-flight guess. A completed wrong attempt refreshes its full delay after computation. An interrupted valid attempt may conservatively count as failed; it never grants access without successful persistence.
- Verification runs off the UI thread with disabled submitting controls and progress text. Locked UI announces remaining seconds and disables digits/submit. Window FLAG_SECURE protects PIN entry and recent-task images.

The single-process application owns one PinService mutex. Root-level file rollback/system-clock tampering is outside this local-app throttle's protection; no claim of tamper-proof security on a rooted OS is made. Cross-process services must not instantiate competing writers in later tasks.

## Xiaomi 10S calibration

Physical M2102J2SC / thyme, API 31. Reused the T0-5 harness with a calibration-only mode, synthetic in-memory input, random salt and Android's default provider selection:
`SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`.

Contrary to the assumption that default selection necessarily avoids BC, this device actually reports **BC** when no provider is specified. Production code does not explicitly request BC. No desktop timing is substituted.

Each run uses one warm-up, seven baseline samples, then seven selected-count samples. Median-based scaling is clamped to the required 210,000 floor. Durations are elapsed milliseconds around derivation; lists below are sorted, as emitted by the harness.

| Run / iterations | Samples (ms) | Median (ms) |
| --- | --- | --- |
| 1 baseline / 210000 | 1236.04, 1246.01, 1282.77, 1323.94, 1400.73, 1483.92, 1585.26 | 1323.94 |
| 1 selected / 210000 | 1723.35, 1763.29, 1766.14, 1768.68, 1770.37, 1778.00, 1791.22 | 1768.68 |
| 2 baseline / 210000 | 1665.74, 1760.41, 1762.12, 1762.64, 1764.45, 1770.04, 1777.12 | 1762.64 |
| 2 selected / 210000 | 1759.54, 1760.17, 1762.26, 1763.06, 1764.04, 1766.27, 1766.32 | 1763.06 |

The first run drifted, so a second real-device run was taken after ADB reconnection. The second selected sample range is 6.78 ms. All selected samples remain about seven times the 250 ms aspiration, even at the mandated minimum. **Temporary 10S-calibrated count: 210,000.** No permissible count meets 250 ms here. Keep the security floor, run asynchronously, and remeasure with a signed Release build on Xiaomi 15 Ultra/equivalent during INT-2; adjust new-record parameters if warranted without invalidating old records. These numbers are not flagship performance evidence.

## Verification and evidence

Host tests cover algorithm test vectors, per-record parameters, random salts, thresholds/doubling/cap, lock reconstruction, reset, interrupted derivation, scope/metadata rejection, encrypted serialization/tamper rejection and fail-closed cipher failure. ViewModel/coordinator tests cover no-skip gating, mismatch, PIN clearing, restored drafts, interrupted Family creation, missing credentials and locked UI. Disk-backed DataStore tests reopen the persisted draft and reject incomplete state without replacement.

The initial ViewModel test run stalled in virtual-time draining because its recurring countdown outlived the test body. A thread dump identified `TestCoroutineScheduler.advanceUntilIdleOr`; the test fixture now clears its ViewModelStore in the test body's finally block, before runTest drains pending work. The real UI countdown was not removed or weakened.

Device acceptance code is in `app/src/androidTest`:

- OnboardingScreenTest: 1.3x font, reachable actions, lock semantics, retry, fixture screenshots.
- OnboardingFlowTest: opt-in unused-installation flow, mismatch, actual PIN setup, draft recreation/background, five failed attempts, real 30-second wait, successful reset and home.
- PinProcessRestartTest: opt-in two separate instrumentation processes around adb force-stop, using the production Keystore store in an isolated test directory and a fixed clock. Does not claim a real-time duration from that controlled-clock persistence test.

The first install attempt rejected both APKs with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. After the user unlocked the phone, ordinary `adb install -r` succeeded for both signed debug APKs. No root/install/signature workaround was used.

The non-UI device tests then **passed**: `PinProcessRestartTest#prepare` in 11.434 s (actual production KDF, random PIN, five wrong attempts, Keystore-encrypted disk record), and `#verify` in 0.072 s after force-stop (same persisted count=5/deadline=controlled time+30000, verification still locked). To additionally prove a live process was killed, MainActivity was launched with adb (`Status: ok`, cold start 351 ms, PID 26109), force-stopped, and pidof returned empty; a further fresh instrumentation process passed `#verify` in 0.092 s. The controlled clock isolates persistence from operator delay and is not a measured wall-clock lock interval. No production data directory was cleared; the PIN test record uses its own isolated cache directory.

Compose's first test stalled before rendering. A single-method reproduction identified `OnboardingScreenTest.failureOffersRetryWithoutExposingDiagnosticInformation`; a test-only thread diagnostic shows `android.app.Instrumentation.startActivitySync` -> `InstrumentationActivityInvoker.startActivity` -> `ActivityScenario.launch` -> `ActivityScenarioRule.before`. Main thread is idle in `MessageQueue.nativePollOnce`. This screen contains no infinite animation and had not yet been composed. The original run was stopped after more than three minutes without progress; a bounded single-method retry also stalled at launch, not at a UI assertion. The foreground remained the pre-existing spike Activity. Read-only appops output recorded rejected `SYSTEM_ALERT_WINDOW` / ignored `MIUIOP(10021)`; MIUI window/background-launch permission is a suspected cause, not yet a proven fix. The user was asked to inspect that permission; no appops grant or security bypass was performed.

The optional `DiagnosticListener` can be enabled with `-e listener com.lazyeng.family.DiagnosticListener`; it writes stack frames only to app-private `files/test-threads.txt`. The captured full stack is retained locally at `apps/android/app/build/reports/e1-t2/activity-start-thread-dump.txt`. A shell UI hierarchy capture also failed with `could not get idle state`; no hierarchy/screenshot success is claimed. MainActivity cold-start success alone is not full onboarding UI acceptance.

Executed host verification (Windows wrapper, project JDK 17):

```text
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug
```

Result: BUILD SUCCESSFUL, 876 actionable tasks. The initial full run had 52 passing host tests; two additional fail-closed tests (reservation-write failure and success-reset-write failure) also passed, bringing the host total to **54, zero failures/errors/skips**, including 19 new tests (10 security, 7 onboarding, 2 disk-backed drafts). Security/onboarding Lint reports contain no issues; full Lint has no errors and retains dependency notices and prior unrelated module warnings. XML reports are in each module's ignored `build/test-results/`; UI test APK compilation is not execution evidence.

| Acceptance | Current evidence / conclusion |
| --- | --- |
| No skip before family/PIN/child | Coordinator/ViewModel assertions and real full-flow Compose test passed |
| Resume without persisting raw PIN | Disk-reopening and ViewModel clearing passed; device recreation/background and separate force-stop/home restoration passed |
| Mismatched confirmation rejected | ViewModel and real device-flow assertions passed |
| Five failures / escalating lock | Host threshold/doubling/cap passed; real five-failure UI lock and 30-second wait passed |
| Restart cannot erase lock | Production Keystore two-process force-stop test passed; actual locked-screen Activity close/relaunch passed |
| Successful verification resets counter | Host and production device-flow assertions passed |
| Provider/count evidence | Two real 10S calibration runs above; floor retained with explicit latency limitation |

The initial pending conclusion is superseded by the completed follow-up below.

## Follow-up: loading feedback and device acceptance (3a)

The existing busy state already displayed a progress indicator and disabled input/submit. The submit button now also says "验证中…" during verification and "设置中…" during setup, so feedback remains visible even when lower content is outside the viewport. Three deterministic unit tests suspend a PIN-store write with CompletableDeferred, assert busy immediately, reject duplicate input/submission, then release the operation and assert busy clears on success, wrong PIN and storage exception. A fifth screen test verifies the visible loading label and disabled controls at font scale 1.3.

The requested device checks were performed in order:

| Step | Observation / action |
| --- | --- |
| a. Don't keep activities | `always_finish_activities` returned `null`, not 1; it was not changed |
| b. Foreground dialog | Window focus was MIUI Launcher, not a runtime-permission dialog; no unrelated permission was granted |
| c. MIUI permissions | Both app/test packages had RUN_ANY_IN_BACKGROUND, MIUI auto-start (10008) and background Activity launch (10021) ignored; these three operations were allowed for only those two packages under the user's authorization |
| d. Awake/unlocked | Screen timeout set to the requested 600000 ms; power state Awake and keyguard showing=false confirmed |

The MIUI operation mapping is corroborated by [Telegram's XiaomiUtilities source](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/XiaomiUtilities.java). This is a vendor-specific device test prerequisite, not an Android production permission requirement. No root command, signature bypass, global security-policy change or overlay permission grant was used.

After these changes, the first four screen tests passed in 12.574 s. Installing the updated APKs reset those MIUI operations to ignored, reproduced the launch wait and showed a fresh 10021 rejection. Reapplying the same authorized operations **after installation** resolved it again. Subsequent tests ran normally; no unrelated diagnostic methods were attempted.

Final device execution used ordinary AndroidJUnitRunner over adb:

```text
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.OnboardingScreenTest,com.lazyeng.family.OnboardingFlowTest -e onboardingAcceptance true com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am force-stop com.lazyeng.family
adb -s <serial> shell am instrument -w -r -e class com.lazyeng.family.OnboardingFlowTest#restoresHomeAfterExternalProcessDeath -e onboardingRestoration true com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

- Five screen tests plus the full flow: **6 passed, 0 failed**, 68.824 s. The flow performed real setup/mismatch, child draft entry, Activity recreation, background/foreground, a new Activity requiring PIN, five real wrong verifications, locked-screen relaunch, a real 30-second countdown, correct verification with persisted failure count reset to zero, Profile save and home.
- A separate home restoration test was then added and executed after actual force-stop: **1 passed, 0 failed**, 2.263 s. An external live-process kill also observed PID 30406 disappear and a new launch receive PID 30483; the subsequent test asserts home content rather than relying on launch status alone.
- Full Gradle build, test APK build, all host tests and Lint passed again: **57 host tests, zero failures/errors/skips**; 876 actionable tasks. Onboarding/security Lint has no issues; existing unrelated warnings remain.
- Tests ran with real device system font scale 1.3; screen fixtures also explicitly use 1.3 where named. Original system font scale 1.0 was restored afterward. The requested screen timeout and scoped test-package MIUI grants remain enabled.

### Screenshots

All screenshots below were captured on the physical 10S and visually inspected for truncation/overlap; primary actions, keyboard and labels remain reachable. PIN images are deterministic no-secret Screen fixtures. The actual flow screenshots contain only the synthetic Profile/home; the test temporarily clears FLAG_SECURE solely on those non-PIN states and restores it in finally. Production PIN protection was neither removed nor disabled.

- [PIN setup, 1.3x](evidence/e1-t2/pin-setup-font-1.3.png)
- [Verification feedback, 1.3x](evidence/e1-t2/pin-verifying-font-1.3.png)
- [Actual restored child draft, 1.3x](evidence/e1-t2/flow-profile-restored.png)
- [Actual home after process death, 1.3x](evidence/e1-t2/flow-home-after-process-death.png)
- [Six-test execution output](evidence/e1-t2/compose-acceptance.txt) and [separate restoration output](evidence/e1-t2/compose-process-restoration.txt).

Other local captures remain under `apps/android/app/build/reports/e1-t2/screenshots/`. Shell uiautomator dump still cannot obtain idle state on this device; it is not used as a substitute for successful Compose assertions or screenshots. This limitation does not block the now-working Compose test route.

## Remaining scope

Recheck target-device calibration during INT-2. No API-26 emulator retry, Profile-management UI, parent console, cloud recovery, provider-secret implementation or later Room schema is included. E1-T2 can now be marked complete with the already-agreed temporary 10S calibration note. E1-T3 is the next task; it has not been started.
