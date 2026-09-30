# ADR 0028: AI Explanation Player State and Cancellation

- Status: Accepted
- Date: 2026-09-28
- Scope: E6-T3 player dictionary sheet

## Context

The dictionary sheet already resolves an offline dictionary entry and captures the selected word, subtitle line, and playback position. E6-T2 provides `ContextExplanationUseCase`, strict response validation, family cache, and provider adapters. E8-T2 owns provider credentials/configuration UI; this task must therefore work safely when AI has not been configured.

## Decisions

- Offline lookup and AI explanation are separate state dimensions. The selected word and subtitle context open immediately; local lookup continues independently. AI state is `NOT_CONFIGURED`, `LOADING`, `SUCCESS`, or `FALLBACK`; it never replaces or blocks local phonetic/sense data, pronunciation, or vocabulary capture.
- The player session accepts an optional context-explanation callback and supplies the selected word, resolved lemma, current line, adjacent lines when present, learner level, and UI language. The application bridge invokes E6-T2 with family ID and published subtitle version. No profile nickname or viewing history enters the request.
- Until E8-T2 supplies an authorized configuration and use case, the application callback remains absent and the sheet shows a non-blocking “not configured” message. No placeholder key, implicit provider, or network request is created. Tests inject a fake callback to exercise success and failure states.
- Safe results are displayed only if marked `safe`, with field limits and confidence checked again at the feature boundary. Display text has control characters and Markdown-like delimiters removed and is rendered as plain Compose text. Failed, invalid, or unsafe responses show a friendly offline fallback and retry action; they do not affect the local dictionary entry.
- Closing the sheet, selecting another word, leaving the player, or clearing its ViewModel cancels the local/AI jobs. E6-T2 single-flight shares work among active same-key observers and cancels the underlying work when the last observer leaves. The HTTP adapter uses cancellable OkHttp calls so coroutine cancellation cancels the socket request rather than leaving a blocking `execute()` running.
- A dismissed request is not kept alive in the background merely to populate cache. A successful active request may be cached by E6-T2; retries are explicit from the fallback card.

## Verification

- Player ViewModel tests cover local definition visibility while AI is loading, validated success state, child-safe display cleaning, dismissal cancellation, fallback, and retry success.
- E6-T2 workflow tests verify single-flight cancellation when its last observer leaves.
- Provider adapter tests use MockWebServer only, including a no-response socket case proving cancellation reaches OkHttp. No real provider or credentials are used.
- On Xiaomi 10S (API 30), after manual APK installation, restoring MIUI AppOps 10021/10008, and foregrounding `MainActivity`, direct `am instrument` execution passed both player Compose cases: local phonetic/sense remained visible through AI loading and success, dismissal cancelled the in-flight fake request, and a failed fake request showed the fallback with a retry action. MIUI accepted numeric AppOp 10021 (`allow`); AppOp 10008 reported `No operations` for both packages on this ROM.
- The first device run exposed a real stale-selection guard defect: dictionary lookup changed the selection lemma (for example, surface `running` to lemma `run`), while the AI completion guard compared the entire pre-lookup selection object and discarded the valid response. The guard now compares stable selection identity (surface, offsets, subtitle line, and captured playback position); a ViewModel regression test uses the surface form as its initial normalized input, and the device tests pass.
- Final checks: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` passed. The first combined build invocation exceeded the machine's configured 512 MiB Gradle heap and stopped due to GC thrashing; rerunning tasks separately with a 3 GiB daemon heap succeeded.

## Consequences and limitations

- Real-provider success and offline failure are testable without configuring network access on-device. The production app remains `NOT_CONFIGURED` until E8-T2 owns and persists provider settings and secure secret references.
- “Local-first” means the local lookup runs independently and is never gated on AI. Disk provisioning or a cold SQLite open can still take longer than a single frame; the sheet exposes the selected word and local lookup progress while that work completes.
- The AI explanation is not added to the vocabulary persistence payload in this task; E7 owns vocabulary schema evolution. Existing capture continues to store the ECDICT definition as its safe fallback.
