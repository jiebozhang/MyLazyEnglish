# ADR 0029: LLM Settings and Secure Connection Test

- Status: Accepted
- Date: 2026-09-28
- Scope: E8-T2

## Decision

The parent-only LLM settings route is nested inside the existing authenticated parent navigation state. It is not a child tab destination. Provider, base URL, model, timeout, and enabled state are stored in app-private preferences; credentials are stored only through `SecureSecretStore` backed by Android Keystore AES-GCM. The UI loads key metadata only and masks the saved value. Typed key buffers are cleared after save/test and when the route leaves composition. No credential or provider request/response content is sent to logs.

The provider-neutral adapters remain the only network boundary. Connection testing sends a one-token `Reply OK` probe, accepts only a successful HTTP response, uses a maximum 15-second timeout, and maps authentication, missing model, quota/rate-limit, timeout, network, and response-format errors to stable messages. Tests use MockWebServer or a fake gateway and never use public endpoints.

Successful save updates the application runtime immediately. Player sessions resolve the current explanation runtime at invocation time rather than capturing an obsolete configuration when the player session was constructed.

## URL and cleartext policy

HTTPS is required for public endpoints. `BaseUrlPolicy` permits HTTP only for RFC1918 IPv4 when the selected provider is Ollama. The explicit debug loopback exception remains controlled by `lazyeng.ai.allowInsecureLocalEndpoints`; the release BuildConfig value is hard-coded false.

Android Network Security Config cannot express an arbitrary CIDR allowlist for a user-entered Ollama IPv4 address. To support arbitrary private-LAN Ollama hosts, the platform network config permits cleartext and relies on the provider URL policy as the application-level gate. At this revision, `core:network` is the only app-owned HTTP client and all its calls pass through that policy. Any future HTTP client must adopt the same policy; a native host allowlist would be preferable if Android adds support for private-address rules.

## Provider defaults

Defaults are editable and not treated as a compatibility guarantee: DeepSeek uses `deepseek-flash`, OpenAI uses `gpt-5-mini`, Claude uses `claude-haiku-4-5-20251001`, and Ollama uses `llama3.2` on an RFC1918 sample address. A successful connection probe remains the authority for a user's account/model availability.

## Verification

- Unit tests cover provider defaults, input constraints, HTTPS/public HTTP/private Ollama URL policy, release/debug loopback flag, one-token probes, stable HTTP error mapping, and logging redaction in the existing provider tests.
- Parent feature ViewModel tests cover loading/success, cancellation on provider change, and key clearing after save/test.
- Device Compose test uses a fake gateway to verify settings, masked saved-key state, and connection state without external network access.
- Keystore encryption/decryption and fail-closed behavior are covered by the existing `KeystoreSecureSecretStore` tests and device test.

## Consequences

- No Room migration is needed; secret values are never stored in Room or preferences.
- The saved credential is staged in Keystore before a connection probe because provider implementations accept an opaque secret reference. A failed probe can therefore leave a securely stored but inactive key; reopening settings shows only its mask.
- The Android platform cleartext permission is necessarily broader than the Ollama app-level allowlist. This is an explicit tradeoff, not a claim that Network Security Config itself enforces CIDR restrictions.
