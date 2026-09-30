# AI settings factory compatibility fix

## Observed failure

The Xiaomi 15 Ultra crash buffer reports `NoSuchMethodError` for
`KeystoreSecureSecretStore.Companion.create(Context): SecureSecretStore`.
The parent settings factory reaches this method through `LazyEngApplication.secureSecrets`.
Startup AI configuration restoration uses the same factory inside `runCatching`;
failure there leaves the explanation runtime unavailable instead of crashing startup.

At diagnosis the installed version was `1.0.0` / code `2` with `DEBUGGABLE` set,
not the previously accepted minified Release. The exception identifies an ABI
mismatch between a caller compiled against the context-only method and the
updated security module whose factory gained a default injectable file argument.
It is not evidence of an invalid API Key or a provider outage.

## Fix

Add `@JvmOverloads` to retain the original context-only JVM signature as well as
the injectable two-argument method. A regression unit test reflects both methods
and checks the original return type. Credential alias, encrypted filename,
encryption and fail-closed behavior are unchanged; no credentials or data reset.

Rebuild and install the same-signature Release instead of modifying the secure
store or disabling security.

## Validation

- `:app:assembleRelease testDebugUnitTest lintDebug`: BUILD SUCCESSFUL (5m22s).
- Security store suite: 3 tests, failures=0, errors=0, including the ABI regression.
- Signed Release `adb install -r`: Success; restored AppOps 10021; MainActivity
  COLD start: TotalTime 131ms, WaitTime 135ms. No app data clear or key replacement.
- Opened parent settings on-device: screen rendered and existing Key mask appeared,
  unlike the original crash. Private screenshots are not committed.
- Saved configuration also showed a malformed HTTPS prefix and a model differing
  from the earlier requested compatible-provider configuration. Address correction
  was started in the UI without replacing the stored Key; further phone operations
  stopped when the owner reported "测试通过了". No claim that the agent independently
  saved/tested a provider configuration or observed a successful real AI response.
- Owner reports device testing passed. Network/model/Key permissions remain external
  requirements, separate from the repaired JVM linkage and settings crash.

The patched household build retains version 1.0.0/code 2. The prior ADR artifact
hash documents the original acceptance binary, not this rebuilt patch.
