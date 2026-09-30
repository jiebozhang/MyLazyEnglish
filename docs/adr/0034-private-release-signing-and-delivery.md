# ADR 0034: Private Release Signing and Delivery

## Status

Accepted for household sideloading.

## Decision

- Create a dedicated RSA 3072-bit release key with a 10,000-day certificate validity period.
- Keep the JKS keystore outside all Git worktrees. Signing values are provided through local process/user environment variables and are never stored in project files or source control.
- Configure the Android Release build to consume these values and fail configuration for `assembleRelease`/`bundleRelease` when any signing input is missing. Debug builds do not require release secrets.
- Do not reuse a debug signing identity. Keep the same release key for future in-place upgrades; loss of the key prevents signed in-place updates.
- Release's cleartext policy remains disabled (`usesCleartextTraffic=false`, `ALLOW_INSECURE_LOCAL_AI_ENDPOINTS=false`). The Debug-only local endpoint opt-in does not flow into Release.

## Validation Record

- `:app:assembleRelease` succeeded with the configured local signing key. `apksigner verify --verbose --print-certs` verified APK Signature Scheme v2; signer is RSA 3072-bit, certificate SHA-256 `3e41a30dabd3b1cb0293f28cffdd5cc6724d891f1f2f878d813763a46c03394d`.
- Artifact manifest inspection reported `usesCleartextTraffic=false`. Generated Release `BuildConfig` reported `DEBUG=false` and `ALLOW_INSECURE_LOCAL_AI_ENDPOINTS=false`.
- Xiaomi 15 Ultra (`25019PNF3C`): `adb install -r` returned `Success`; AppOps `10021` and `10008` were restored; `am start -W -n com.lazyeng.family/.MainActivity` returned `Status: ok`, `LaunchState: COLD`, and `TotalTime: 179 ms`. The resumed window was `com.lazyeng.family/.MainActivity`.
- The first-run UI hierarchy exposed the onboarding first step (family/PIN creation). The device owner completed PIN setup and child-profile creation on-device; the current UI hierarchy confirms the child home, all four tabs, parent entry, and the empty-library state. No PIN or profile name was recorded.
- Xiaomi's `FLAG_SECURE` behavior blanks screenshots. A screenshot was not retained as visual evidence; the install/launch transcript and non-sensitive accessibility hierarchy observation are recorded in `docs/adr/evidence/rel-1/device-validation.txt`.
- APK hygiene scans found no `2580`, `sk-demo-family-key`, `test-only-fake-key`, or fake connection-success string in any primary DEX string table. Raw byte grep can match DEX debug/source metadata; the string-table scan distinguishes that from an actual compiled string. INT-3's source audit also records removal of the prototype PIN bypass. The main and merged Release manifest deny cleartext; Release BuildConfig is not debuggable and has the local HTTP exception disabled.
- JKS history check: no `*.jks` or `*.keystore` path is tracked in Git history. The generated private keystore remains outside the repository.
- `testDebugUnitTest`, `lintDebug`, and `assembleDebug` succeeded after the Release signing change. `assembleRelease` succeeded.

No password, private-key material, or API credential belongs in this document.

## Risks

This is a private household signing setup, not Play App Signing or a public release process. The ECDICT source-provenance caveat and household-only risk decision remain as recorded in ADR 0004 and ADR 0016.

## V1.0 Final Household Delivery (2026-09-30)

This section supersedes the earlier artifact/version measurements, not the historical validation record. Baseline is `521c58d`, branch `codex/rel-1-v1-release-delivery`; no Room schema change. The requested final ADR filename redirects here rather than allocating the already-used number again.

### Packaging Decisions

- Version name `1.0.0`, code `2`, minimum API 26, target/compile API 36. Same RSA 3072 household signer; no replacement key or data clear.
- Enable R8 minification and resource shrinking with optimized Android rules. Room database names/generated implementations retain the reflection contract; persisted enum constants/lookup methods remain stable. Room, Media3, Compose and transitive serialization use library consumer rules. Business JSON fields are explicit `org.json` mappings, not reflective entity/DTO serialization; no blanket package keep.
- Pin releaseRuntimeClasspath through the strict Gradle lockfile in addition to the existing version catalog. Missing signing inputs fail direct release builds and packaging aliases; signing secrets remain environment-only.
- v1/v2/v3 signing enabled. Default `apksigner verify --verbose --print-certs` verifies v2/v3 with the application's API 26 floor and reports v1=false (not selected for that floor). Additional `verify --verbose --min-sdk-version 23` verifies **v1=true, v2=true, v3=true**. This verification floor does not change the app's minimum Android version. Legacy v1-only verification warns about META-INF metadata; v2/v3 verification covers the APK.
- Certificate SHA-256 remains `3e41a30dabd3b1cb0293f28cffdd5cc6724d891f1f2f878d813763a46c03394d`. `git log --all --full-history -- '*.jks' '*.keystore'` returned no paths.

### Artifact Measurements

Local output: `apps/android/app/build/outputs/apk/release/app-release.apk`; APK and mapping remain ignored, not committed. Retain mapping locally for diagnosing this exact release.

- Size: **5,309,659 bytes (5.064 MiB)**, versus earlier unminified 15,081,675 bytes (about 64.8% smaller).
- SHA-256: `069890ea4743c85c9c220d5a47b4fd20d01d95f9d4d11ae7612c1f747a17918d`.

| ZIP category | Compressed bytes | Uncompressed bytes |
| --- | ---: | ---: |
| DEX | 2,492,884 | 4,810,172 |
| Assets | 2,169,420 | 2,169,774 |
| Resources | 18,634 | 40,216 |
| Native libraries | 73,584 | 73,584 |
| Manifest / metadata / other | 467,170 | 588,219 |

Category sums exclude ZIP headers, alignment padding and APK signature blocks. The ECDICT gzip is present (2,156,351 bytes; ZIP compressed 2,156,692), together with checksum and notice. Gzip's additional ZIP layer can be slightly larger; this is not a second full dictionary copy.

### Host Validation and Hygiene

- JDK 17: `:app:assembleRelease testDebugUnitTest lintDebug --offline --no-daemon --max-workers=1` succeeded (1m41s); 4 GiB Gradle heap / 1 GiB metaspace. Additional `:app:assembleDebug :core:common:test :core:model:test` succeeded (44s). Cached task results were reused where unchanged; this is not a claim that every test reran from scratch.
- Release manifest inspected with `aapt2 dump xmltree`: `usesCleartextTraffic=false`, no debuggable flag. BuildConfig has `DEBUG=false`, local HTTP exception=false; R8 removes the app BuildConfig class/constant fields when inlining. An additional `:app:testReleaseUnitTest` attempt failed because this project exposes no such task; it is not recorded as a test pass. Source-level Debug unit tests do not exercise obfuscated DEX; the installed minified Release smoke provides that runtime check.
- Parsed DEX string tables (21,384 strings): no literal demo PIN `2580`, demo Key, test-only fake Key, FakeNetwork or debug rotation host. Two MockWebServer **logger-name strings** remain (`okhttp.MockWebServer`, `okhttp3.mockwebserver.MockWebServer`), not executable mock classes. R8 mapping has no mockwebserver class definitions. These are OkHttp Android logger mappings, not mock connection-success paths.
- Gradle repository mirrors were temporary cache reuse only and are removed from the submitted settings. Local environment notes and work requirements were not modified or staged.

### Xiaomi 15 Ultra Release Smoke

Installed with `adb install -r`: **Success**; restored AppOps 10021, launched `am start -W`: Status ok, COLD, TotalTime **158 ms**, WaitTime **162 ms**. Actual installed package version is checked separately in the evidence file.

| Smoke step | Observed result |
| --- | --- |
| Home after signed upgrade | Existing Profile and continue-watching position still displayed, no onboarding reset |
| Offline video | Real local household video advanced from its saved position; English/Chinese transcript and active sentence visible |
| Landscape subtitle | Bottom overlay visible with bilingual text; player controls hid during playback; portrait uses transcript without overlay |
| Offline lookup | Tapped a transcript word; phonetic, local definition and source sentence rendered; AI showed offline fallback with retry |
| Vocabulary | Existing one-word collection displayed with definition and source-context playback entry |
| Parent console | Existing PIN accepted; dashboard rendered with no child bottom tabs; after returning, re-entry required a fresh empty PIN prompt |

No test words were added/deleted and no household files were removed. Playback legitimately advanced watch progress and lookup may append its normal audit event; this is not a byte-for-byte database snapshot comparison. Private screenshots were viewed locally only; family names, credentials and unrelated status-bar notifications are not committed.

During smoke: airplane mode enabled, Wi-Fi and mobile data disabled by ADB. Afterwards restored airplane=0, Wi-Fi=1, mobile-data=0, automatic rotation=1. Current app-process logcat sample: 2,129 lines, zero FATAL/reflection/serialization/database-corruption matches and zero tested credential-pattern matches. This bounded scan is not proof that every possible private file/log contains no secret.

### Release Checklist and Remaining Gates

The family guide provides T0/E1/EH/E2/E3/E4/E5/E6/E7/E8/INT coverage and exclusions, grounded in prior ADRs rather than marking every official task complete. Current minified Release passed the above upgrade smoke. Fresh-install minified Release E2E was **not** run on the real device because doing so would risk household data; earlier onboarding evidence does not automatically certify the current obfuscated binary.

INT-2 pending: flashcard-back physical rotation and speed/loop preservation across rotation; its failed automated rotation observation is not replaced by this smoke. TalkBack/tablet/foldable/split-screen remain excluded for household use. INT-3 pending: long-play leakage, 60fps/large-grid measurements, literal write-time kill -9 and whole-private-storage secret scan. There is no root access on this device. No new claim of all official P0/P1 gates being comprehensively audited is made.

**Conclusion:** signed V1.0 household installation and this release smoke are accepted; official all-matrix REL-1 completion remains conditional on the unresolved gates. Delivery is private household trial use, not public distribution. Guides: `docs/delivery/v1.0-family-delivery-guide.md` and install-guide pointer.
