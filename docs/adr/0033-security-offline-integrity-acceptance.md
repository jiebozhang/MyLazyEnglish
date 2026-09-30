# ADR 0033: Security, Offline, and Data Integrity Acceptance

## Status

Accepted for the INT-3 family-use scope. This is a focused audit, not a formal penetration test or a multi-device certification.

## Findings and Decisions

### Cleartext network policy

The shipped main manifest previously set `usesCleartextTraffic=true` and referenced a network-security configuration that permitted cleartext globally. That contradicted the E6-T1 decision that public endpoints use HTTPS and that any local HTTP exception is explicit, Debug-only, and absent from Release.

- The main manifest now denies cleartext and no longer references the permissive XML configuration; that configuration was removed.
- Debug cleartext is denied by default. The explicit Gradle property `lazyeng.ai.allowInsecureLocalEndpoints=true` enables the Debug manifest placeholder only. Release BuildConfig and manifest placeholders remain hardcoded `false`.
- The app's endpoint validation remains responsible for limiting the opt-in to supported private/local endpoints; enabling the build switch does not change Release policy.
- Verified default Debug and Release merged manifests both deny cleartext. With the explicit property, the merged Debug manifest allows it while Release still denies it. Debug and Release BuildConfig generation also showed `true`/`false` respectively for that explicit-property build.

### Prototype credentials

- The React prototype `PinGate` contained a universal `2580` check and displayed that value in its instructions. This was a real client-side gate bypass, not a test fixture. The literal and hint are removed. Verification is now an injected dependency; the current prototype supplies a rejecting verifier and remains locked until a real secure verifier is wired in.
- The prototype AI settings form previously initialized its API-key field with `sk-demo-family-key`. The field now starts empty. It was a fake value, but keeping credential-shaped defaults is unnecessary and risks being copied as if usable.

## Audit Inventory

The audit searched tracked files with `git grep` and the working tree with `rg --hidden`, excluding generated `.git`, Gradle/build output, and `node_modules` directories. It covered source, tests/fixtures, docs, manifests/build files, and text-based CI/config files. Binary screenshot files were enumerated and the PIN setup/verifying evidence reviewed visually; these show keypad digits and masked circles, not an entered PIN or credential. No OCR pass was available, so binary-image screening is visual rather than exhaustive OCR.

All suspicious textual matches and dispositions:

- `docs/adr/0008-onboarding-pin-setup.md`, evidence under `docs/adr/evidence/e1-t2/`, plus other PIN UI evidence: synthetic onboarding/keypad examples; screenshots display keypad labels and empty/masked dots, not a submitted PIN.
- `家庭版LazyEng-Codex开发任务清单-完善版.md:71`: the example `2580` is a security-audit instruction explicitly identifying a demo PIN, not runtime code or a credential. The only executable frontend occurrence was removed.
- `apps/android/core/network/.../AiProviderAdapters.kt`: `Authorization: Bearer` is the production protocol header construction. Secret values are supplied at runtime from `SecureSecretStore`; this is not a literal credential.
- `apps/android/core/network/.../AiProviderAdaptersTest.kt`: `test-only-fake-key` is an obvious fake used by MockWebServer tests; the test asserts the expected request header and separately asserts that logs do not contain the fake secret.
- `frontend/src/lib/api-client.ts`: assigns an Authorization header from a caller-provided token. It contains no token value; runtime logs are checked below.
- `apps/android/app/src/androidTest/.../LlmSettingsDeviceTest.kt`: `fake****1234` is a masked synthetic UI fixture, not an actual saved key.
- `ParentManagementDeviceTest.kt` and `ParentManagementViewModelTest.kt`: `1234` is a synthetic PIN used only by isolated test state. No production fallback PIN exists.
- Numeric `1234`/timestamps in migration and route tests are fixture values, not credentials.
- The device's global log buffer contained three `Authorization: Bearer ...` lines. Their PID mapped via `adb shell ps -A` to the unrelated installed process `com.nowcasting.activity` (PID 14048), not either LazyEng package. Token values were redacted and not inspected. The current LazyEng instrumentation PID had no matching Authorization lines.
- Fixed searches for `ghp_`, `github_pat_`, long `sk-` keys, Google/AWS key shapes, `C:\`, `D:\`, `/Users/`, and `/home/` found no credential or absolute-path value in tracked text. `ENV_NOTES.local.md` and `工作要求.md` are external local-only files and were not modified or staged; the environment note was checked and contained no plaintext credentials.

## Runtime and Offline Verification

- Xiaomi 10S (`52072092`, `M2102J2SC`): manually installed the Debug and test APKs, restored AppOps `10021` and `10008` for both packages, and foregrounded `MainActivity` with `am start -W`. The device was already in airplane mode (`settings get global airplane_mode_on` returned `1`); its mode was left unchanged.
- `EndToEndCoreJourneyDeviceTest#profileImportPlaybackLookupReviewRestartIsolationAndParentControlsStayConsistent` passed. This isolated test uses a random disposable Room database and synthetic import documents, removes its database in `finally`, and exercises the bundled offline dictionary (`running` → `run`, real phonetic and Chinese sense), save/capture, vocabulary context, review scheduling, profile isolation, and persisted reopen. Its playback engine is a fake; real Media3 playback is covered separately below.
- `PlayerScreenTest#media3PlaysSeeksAndPausesLocalSyntheticVideo` passed in airplane mode using the bundled synthetic MP4, verifying local playback, seek, and pause.
- `NavigationParentGateTest#parentDeepLinkSavedStateAndBackgroundNeverRestoreAuthorization` passed. It covers direct parent deep-link routing to the PIN phase, saved-state restoration returning to PIN, and background revocation returning to PIN. It does not simulate reopening from the Android Recents UI; that path remains a manual follow-up.
- `InstalledDatabaseIntegrityDeviceTest` reads only aggregate counts for `families`, `profiles`, `videos`, `watch_progress`, `subtitle_lines`, `lookup_events`, `vocabulary_items`, and `review_attempts`, plus SQLite integrity/foreign-key checks. Before and after `am force-stop` + `am start -W`, counts were identical: families=1, profiles=1, videos=1, and 0 in each listed learning-data table. `integrity_check` returned `ok`; `foreign_key_check` returned no rows. No field values were selected, exported, or displayed.
- Host `VideoMigrationTest#realV1FileUpgradesThroughRoomAndPreservesEveryExistingField` opens the checked-in v1 schema fixture through the complete Room migration chain to v6, preserves the fixture Family/Profile data, and verifies foreign-key integrity. No destructive migration is configured in the chain.
- Runtime log scan covered the exercised operations and searched for credential-shaped key prefixes, Authorization bearer text, and known fake-key markers; no matching LazyEng app log entries or LazyEng fatal-exception headers were found. Three global Authorization lines belonged to the unrelated process described in the audit inventory. Test-only credential matches reported above are source fixtures, not emitted LazyEng logs.
- Release manifest and BuildConfig cleartext policy were checked independently of the Debug runtime APK. No API key or PIN was read from the database; the schema keeps PIN material in the Keystore-protected, non-backed-up file store and provider secrets in the encrypted secret store, not Room. E8-T3 export code filters setting names containing key/secret/token/PIN/password and serializes only the documented family/profile/vocabulary fields.

## Verification Commands

- Android: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`; no `connectedDebugAndroidTest` task was run.
- Device: manual `adb install -r`, AppOps setup, `am start -W`, and direct `adb shell am instrument` invocations as listed above.
- Frontend: `pnpm install --frozen-lockfile`, `pnpm run build`, and `pnpm run lint`.
- Database upgrade: v1 schema fixture → Room v6 using the normal ordered migrations; no destructive fallback.

## Limitations

- The family-use scope skips performance benchmarks and the full network fault-injection matrix.
- Airplane-mode instrumentation uses synthetic local media for actual Media3 playback and synthetic import/document adapters for the longer cross-feature journey; it does not claim playback of a household video file.
- Parent gate coverage exercises deep link, saved-state restore, and background revocation in instrumentation, but not an interactive Recents-task relaunch.
- No private database fields were inspected. The device integrity test emitted aggregate counts only; secret absence is assessed from storage boundaries, schema, export code, and filtered logs rather than dumping household data.
- After instrumentation had completed, the device idled into AOD/notification shade. A foreground launch intent was accepted but the final window-focus snapshot remained on the notification shade; no lock-screen bypass or further unlock attempt was made. This occurred after test completion.
- TalkBack, tablet/foldable, split-screen, and multi-Android-version tests are outside this family-use scope.

## Full INT-3 Follow-up (Xiaomi 15 Ultra, 2026-09-30)

This follow-up extends the earlier family-use audit with device measurements and crash-resilience checks. It does not replace the earlier Xiaomi 10S evidence. The source branch contains the later player and AI URL fixes. The 15 Ultra reported no accessible `su` command, so privileged inspection and a literal shell `kill -9` were unavailable.

### Performance

- Five Release cold starts used `am force-stop` followed by `am start -W MainActivity`: `TotalTime` 173, 136, 122, 128, and 145 ms. A subsequent signed Debug launch after instrumentation was 414 ms. These are Activity startup times, not a measured fully populated home-screen first frame; all are below the task's 1,500 ms Activity-start threshold. The PRD's published startup target is 3 seconds, so the task-card threshold is stricter.
- `PerformanceIntegrityDeviceTest#bundledDictionaryLookupReportsP50AndP95`: 1,000 warm lookups of the packaged `running` entry yielded P50 0.241094 ms and P95 0.298437 ms, below both the PRD 300 ms limit and the task-card P50 1 ms/P95 2 ms targets. This is a single-entry warm-path benchmark, not a representative distribution of every dictionary entry. The measurement exposed repeated SHA-256/SQLite validation: `DictionaryAssetProvisioner` had set `Preparing` before checking its ready fingerprint. The ready fast path now remains in `Ready`; a unit test asserts no state churn on reuse.
- `PerformanceIntegrityDeviceTest#fiveHundredVocabularyRowsRemainScrollable`: a 501-row synthetic `LazyColumn` scrolled to the final card in 132 ms. This establishes reachability, not 60 fps or frame-jank compliance. Long-subtitle 60 fps and long-play memory-leak targets remain unmeasured.

### Offline and interruption

- The device was temporarily put in airplane mode with Wi-Fi disabled. Direct `am instrument` runs passed `PlayerScreenTest#media3PlaysSeeksAndPausesLocalSyntheticVideo`, `PlayerScreenTest#aiFailureShowsRetryableFallbackWithoutHidingOfflineDefinition`, and the INT-1 end-to-end journey. These cover local Media3 playback, AI fallback, dictionary lookup, vocabulary capture, and review using synthetic/isolated fixtures. Airplane mode and Wi-Fi were returned to their original settings afterward. Household video playback and parent management were not exercised in this offline pass.
- `InstalledDatabaseIntegrityDeviceTest` passed before and after force-stop/restart. Aggregate counts stayed families=1, profiles=1, videos=1, watch_progress=1, subtitle_lines=95, lookup_events=16, vocabulary_items=1, review_attempts=0. `PRAGMA integrity_check` was `ok` and `foreign_key_check` had no rows. No private field values were selected or printed.
- `PublicCleartextIntegrityDeviceTest#publicCleartextIsBlockedAndIsolatedRoomTransactionRollsBack` passed on the installed signed Release: public cleartext blocked, synthetic in-memory transaction rolled back, SQLite integrity valid. It is separate from the Debug-only Compose host test so Release can instantiate it.
- `IsolatedWalCrashDeviceTest` opened only `int3-isolated-wal.db`, committed a seed record, and held a second transaction uncommitted. Its log recorded `INT3_WAL_PENDING isolated_transaction_open`. Direct `adb shell kill -9` returned `Operation not permitted`; `am force-stop` was used at that exact point. On process restart, the second test passed: `INT3_WAL_RECOVERY committed=retained pending=rolled_back integrity=ok foreign_keys=ok`. This proves force-stop recovery of the isolated WAL fixture, not an actual SIGKILL of the household database. The fixture and its marker were retained; no backup was deleted.
- The destructive two-phase WAL test is opt-in: use `am instrument -e int3CrashPhase hold -e class ...#holdUncommittedFixtureTransactionUntilProcessIsKilled`, then interrupt the process, and use `-e int3CrashPhase recover -e class ...#reopenFixtureAfterKillRetainsCommittedRowsAndRollsBackPendingRow`. Without these phase extras both methods skip, so ordinary full-suite runs cannot accidentally hang or interrupt the app.
- An intermediate rerun missed the former 120-second hold window and failed before force-stop, so it was not counted as a crash recovery pass. The fixture's timeout path now throws *inside* the transaction to guarantee rollback. A final single-host-script orchestration detected the new marker for app PID 10151 and force-stopped immediately; `recover` then passed (1/1). Only the isolated fixture was affected by the failed attempt.

### Security

- A repeated text scan with `rg -l --hidden` across `apps/android`, `docs`, `frontend`, and `.github`, excluding `.git`, build, Gradle, and node_modules output, searched `ghp_`, `github_pat_`, long `sk-` patterns, `Bearer `, `Authorization:`, `2580`, Windows drive paths, `/Users/`, and `/home/`. Matches were confined to protocol header construction, mock-network assertions, the prototype's token variable, and audit/release documentation describing rejected demo values. No newly exposed literal credential was found. The earlier audit inventory above lists each category and its disposition.
- `NetworkSecurityPolicy` on the installed Debug APK rejected public HTTP. PIN failure reservation, lockout, and memory zeroization are covered by `PinServiceTest` and the current ViewModels, not a device brute-force run against the household PIN.
- The pre-existing `KeystoreSecureSecretStoreDeviceTest` used the target application's `ai-secrets.enc` and deleted it. That was unsafe on a device with real AI configuration. Instrumentation cannot write the test APK sandbox under its target UID, so `KeystoreSecureSecretStore.create` now accepts an injected record file. The test uses only `int3-keystore-fixture.enc` in the target sandbox and never reads or deletes the production `ai-secrets.enc`. After signed Debug/test APK installation and AppOps restoration, the write/encryption test passed (1/1); after `am force-stop` and foreground restart, the read/delete test passed (1/1).
- The ordered Keystore test class also passed as a whole against the installed signed Release (2/2). Its fixture cleanup is confined to the injected file.
- Because the 15 Ultra has no accessible root, a byte-level scan of all private app files and the live encrypted key record was not possible. The evidence supports the storage design and filtered logs, but cannot assert absolute absence of arbitrary plaintext in every private file. No household credential or PIN was read for this audit.
- A final signed Release cold start produced 191 logcat lines scoped to its current app PID; a count-only scan for bearer/Authorization text, long key prefixes, and the known test PIN returned zero matches. This is a scoped startup-log check, not a claim about every historical log entry.

### Final build and installed package

- JDK 17 `:app:assembleDebugAndroidTest`, `:app:assembleDebug`, `:app:assembleRelease`, `testDebugUnitTest`, and `lintDebug` completed with `BUILD SUCCESSFUL` in the final combined run. A temporary local Maven mirror was used only to replenish missing Gradle plugin artifacts; `settings.gradle.kts` was restored afterward and has no committed diff.
- The rebuilt Release APK passed `apksigner verify` (v2 signature), was installed with `adb install -r`, and cold-started on the 15 Ultra with `am start -W` (`TotalTime` 155 ms, then 117 ms after the final check). AppOps 10021/10008 were restored. `InstalledDatabaseIntegrityDeviceTest` also passed against the installed Release (1/1); the final aggregate counts were unchanged. The device was left on the signed Release rather than the temporary Debug main package.

### Decision and gaps

The measured startup and warm dictionary goals pass, and the exercised offline and WAL recovery paths pass. Full INT-3 acceptance remains **partial**: 60 fps long-subtitle playback, prolonged-play memory release, a frame-time benchmark for 500+ vocabulary rows, privileged private-file scanning, literal `kill -9`, and an offline pass of household video plus parent management are still unverified. None of these should be reported as passed by inference from synthetic tests. No device backup or original media file was removed.
