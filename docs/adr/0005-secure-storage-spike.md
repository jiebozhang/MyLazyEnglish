# ADR 0005: Secure Storage and PIN KDF Spike

- Status: Accepted as a technical spike; formal production implementation remains owned by E1-T2 and E6-T1.
- Scope: T0-5 Spike 2 only. No production UI or credential storage abstraction is introduced.
- Research date: 2026-09-25.

## Requirements Reviewed

PRD v2.2 section 17 requires API keys to use system secure storage, PINs to use salted hashes and rate limiting, and sensitive material not to leak. Development checklist E1-T2 fixes PIN derivation to PBKDF2-HMAC-SHA256, a fresh random salt of at least 16 bytes, parameters stored with the verifier, at least 210,000 iterations calibrated toward 250 ms, and lockout after five failures for 30 seconds with exponential increases capped at 15 minutes. Failure count and `lockUntil` must survive app restart; successful verification resets both.

## Device and Method

The requested Xiaomi 15 Ultra serial was unavailable for this run. Per the user's approval, validation used the currently connected physical Xiaomi `M2102J2SC` (`thyme`), API 31, serial `52072092`. It is a substitute device, not an equivalent-performance claim for Xiaomi 15 Ultra/API 36. The spike was installed as a separate debug application with the project's JDK 17 and Android SDK. No root commands or signature workarounds were used.

`apps/android/spikes/secure-storage/` contains the runnable harness. Its synthetic API-key payload and PIN input are generated randomly in memory for each run; neither is printed, logged, or persisted. Reports contain only booleans, algorithm/provider metadata, counts, and durations. The in-memory failing cipher factory test asserts the vault throws before any storage write, proving the spike path does not fall back to plaintext.

## Keystore Results

- AES-256-GCM encrypt/decrypt passed. The synthetic plaintext byte sequence was absent from app-private files; only ciphertext and its SHA-256 comparison digest were persisted.
- Android `KeyInfo` reported `hardware-backed=true`, security level `1 (TEE)`. Hardware-backed TEE isolation is available on this device.
- A StrongBox-backed key generation request failed with `StrongBoxUnavailableException`; StrongBox is not available on this device. No StrongBox support is claimed.
- After `am force-stop` and a fresh Activity launch, the existing Keystore key decrypted the retained ciphertext and matched its stored digest.
- Before uninstall, the encrypted payload and digest were held outside app data. After uninstall/reinstall, the app reported the old alias absent; the old key could not be recovered. This is expected Android Keystore app-identity behavior.
- Simulated Keystore initialization failure: storage write count remained zero and no plaintext write path was reached.

## PBKDF2 Device Calibration

Algorithm: `PBKDF2WithHmacSHA256`, JCA provider `BC`, 256-bit derived output, random 16-byte salt. A warm-up derivation was followed by seven timed samples at the E1-T2 minimum; the median informed a rounded estimate constrained to never fall below 210,000. Seven more timed samples measured the selected iteration count. Timings are wall-clock elapsed time from `System.nanoTime()` around one derivation.

- 210,000 iterations samples: `[1768.50, 1770.31, 1774.42, 1780.64, 1782.10, 1787.96, 1792.31]` ms; median **1780.64 ms**.
- Selected iterations: **210,000** (the hard minimum; the unconstrained 250 ms estimate is below the minimum and therefore invalid).
- Selected-count samples: `[1765.58, 1767.99, 1769.28, 1770.59, 1771.15, 1771.79, 1774.36]` ms; median **1770.59 ms**, range **8.78 ms**.
- 250 ms target comparison: this device's minimum-compliant count is about **7.1× slower** than target. No permitted iteration count can meet 250 ms on this device using the measured provider.

Recommendation: retain 210,000 as the minimum and do not lower the security floor to chase latency. E1-T2 must run derivation off the UI thread and should repeat calibration on the intended Xiaomi 15 Ultra before release. If API 36 hardware also has an unacceptable delay, resolve the product/performance conflict explicitly rather than storing a weaker verifier. This device result must not be extrapolated to the newer target.

## Verifier and Failure Throttle Results

- The harness stores only random salt, derived hash, algorithm iteration count, failure count, and `lockUntil`; the generated synthetic PIN is checked against serialized verifier fields and was not present.
- Failure delays observed with the persisted SharedPreferences store and an injected clock: `[30, 60, 120, 240, 480, 900, 900]` seconds. This verifies the five-failure threshold, doubling, and 15-minute cap including a further failure at the cap.
- A newly constructed limiter reloaded the saved count/deadline and remained locked. A real `am force-stop` followed by fresh app launch reported `Persisted failures=5` and a future `lockUntil` (`lockRemainingMs=941453` in the final run).
- The schedule test advances its injected clock so it does not sleep through the lock periods. It writes those timestamps to real app-private SharedPreferences; the subsequent process restart verifies actual persistence. Its remaining-time number is therefore a persistence proof, not a measured wall-clock lock duration.
- Successful verification reset both persisted values.

## Decision and Risks

Android Keystore AES-GCM is viable for protecting provider credentials on the tested API 31 device, with TEE isolation but no StrongBox. On Keystore failure the application must reject the operation; plaintext fallback is prohibited. Use one-way PBKDF2-HMAC-SHA256 verifier records with random salt and persisted parameters, plus durable rate-limit state.

The mandatory minimum iteration count takes about 1.77 seconds on this device, not about 250 ms. Keep the minimum; evaluate on Xiaomi 15 Ultra before setting the final measured E1-T2 value. This spike's UI harness, in-memory failure stubs, and SharedPreferences limiter are not production code and must not be reused as the final E1-T2/E6-T1 architecture without review.

Reproduction instructions are in [the spike README](../apps/android/spikes/secure-storage/README.md). The debug APK and captured device screenshot are build artifacts and are not committed.

## Follow-up: PBKDF2 Calibration Ownership

The final PBKDF2 iteration-count performance decision is deferred to E1-T2 on the formal target device (Xiaomi 15 Ultra or an equivalent current flagship). The current development device, Xiaomi 10S, is older; its measured latency is not representative enough to set the production target, so this follow-up does not repeat the measurement. The Keystore encryption/decryption, persistent failure throttling, and fail-closed/no-plaintext-fallback conclusions above remain verified and unchanged.
