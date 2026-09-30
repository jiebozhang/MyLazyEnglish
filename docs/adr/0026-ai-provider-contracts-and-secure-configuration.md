# ADR 0026: AI Provider Contracts, Transport Security, and Credential Storage

- Status: Accepted for the E6-T1 provider/network foundation.
- Scope: Provider-neutral requests, OpenAI-compatible and Anthropic wire adapters, endpoint policy, credential storage, and safe transport diagnostics. Settings UI, live connection UI, generated explanation schema/cache, Room changes, and real-provider traffic are out of scope.
- Requirements: PRD v2.2 sections 8.18, 9, 15, and 17; `SecureSecretStore` and `AiProvider` contracts in `core/common`; AES-GCM/Keystore fail-closed conclusions in ADR 0005.

## Branch and task identity

This repository already has a different parent-dashboard task committed under the identifier E6-T1. The current task also arrived labeled E6-T1, but explicitly defines Provider contracts and network security. This ADR records the identifier collision; implementation follows the provider task text and does not alter the parent dashboard.

## Decisions

- `OpenAiCompatibleProvider` and `AnthropicProvider` implement the same `AiProvider` contract. `AiProviderFactory` chooses an adapter without changing use-case call signatures.
- Adapters retrieve a `CharArray` only via `SecureSecretStore` and submit it as the protocol-required header. The provider configuration contains an opaque `SecretReference`, never key material.
- OpenAI-compatible requests use the chat-completions wire shape; Anthropic requests use `/v1/messages`, `x-api-key`, and `anthropic-version`. Both map the agreed explanation JSON into the existing provider-neutral `ContextExplanation` result. No new persistence, AI-output DTO versioning, or cache is introduced here.
- API bases accept HTTPS and only the known empty or `/v1` base path. Plain HTTP is rejected except loopback hosts when the explicit Gradle property `lazyeng.ai.allowInsecureLocalEndpoints=true` is set for Debug. The Debug manifest cleartext allowance uses the same opt-in. Release BuildConfig hard-codes the switch to false and has no Debug cleartext manifest contribution.
- `RedactingLoggingInterceptor` logs only fixed event labels and response status. It never reads or records URL, headers, request/response bodies, or exception messages. No raw response or credential is put into diagnostic errors.
- `KeystoreSecureSecretStore` stores a single AES-GCM encrypted JSON record in app-private `noBackupFilesDir` via `AtomicFile`; the AES-256 key is held in Android Keystore. Reference keys are SHA-256 digests. Initialization/encryption/decryption/storage errors return `AI_SECRET_STORAGE_FAILED`; plaintext persistence fallback does not exist. The caller remains responsible for clearing its input `CharArray` after use.
- HTTP status mapping: 401/403 -> `AI_AUTH_FAILED`; 404 -> `AI_MODEL_NOT_FOUND`; 429 -> `AI_BUDGET_REACHED`; timeout -> `AI_TIMEOUT`; malformed or schema-invalid body -> `AI_RESPONSE_FORMAT_INVALID`; transport failures use the existing offline/temporary-network codes.

## Verification

- Unit tests use MockWebServer bound explicitly to `127.0.0.1`, `Proxy.NO_PROXY`, and a conspicuously fake test credential. No external provider URL or real network is used.
- Adapter tests compare provider-neutral result fields, request paths and auth-header placement; they cover authentication, model-not-found, quota, timeout, invalid response, HTTPS policy, and log non-disclosure.
- Secure-store JVM tests inject cipher and atomic-record-file fakes to verify encrypted payload round-trip and fail-closed behavior. The app instrumentation test uses the actual application's `targetContext` and private no-backup directory. Run `aWriteFakeSecretToKeystoreEncryptedStorage`, force-stop `com.lazyeng.family`, relaunch it, then run `bReadAfterApplicationForceStopAndRemoveFakeSecret` to verify persisted ciphertext and Keystore access across a real app process restart.
- Debug insecure-endpoint exception defaults off. Only the explicit property turns it on, and only for loopback hosts. Release generation is verified separately: BuildConfig value false; Release merged manifest must not set `usesCleartextTraffic=true`.

## Risks and follow-up

- This task does not make provider calls by default and does not add a settings screen. E8-T2 owns parent-facing configuration and connection feedback; E6-T2 owns explanation schema validation/cache and related workflow.
- Xiaomi 10S (ADB serial `52072092`) verification completed: the first instrumentation invocation wrote the fake credential and confirmed it was absent from the app-private file; after `adb shell am force-stop com.lazyeng.family` and relaunch, the second invocation decrypted the same value successfully and removed the test record. No real key material was used.
- Do not ship a Release artifact with local HTTP opt-in; release policy remains HTTPS-only.
