# ADR 0017: Morphology and bounded dictionary selection

## Status

Accepted for E5-T2.

## Context and decision

PRD 8.10 requires the clicked surface form and complete sentence to remain available while attempting local lookup by lemma. E3-T2's `SubtitleTokenizer` remains the source of truth for UTF-16, end-exclusive offsets. This task consumes its normalization/ranges and delegates every actual dictionary read to the E5-T1 `DictionaryRepository`; it does not introduce a second SQLite query path.

`DictionarySelectionParser` accepts one token or a bounded phrase of at most four lexical tokens. A multiword selection is queryable only when its normalized form is in a small, explicit list of common phrasal expressions. Other multiword ranges remain intact as `UNSUPPORTED_PHRASE`; ranges longer than four words return `TOO_LONG`. This prevents a long-press over arbitrary prose from becoming an invented dictionary headword. Accepted selections retain exact source text, sentence, and source offsets for later contextual lookup.

`EnglishLemmaCandidates` is deterministic pure Kotlin. Lookup normally checks the surface spelling first, then a bounded set of common irregular forms and conservative suffix rules (plural, `-ing`, `-ed`, `-er`/`-est`). For regular `-ed` forms, and `-ing` forms immediately preceded by a be-verb auxiliary, derived candidates are tried before the exact surface entry; the surface remains a fallback. E5-T4 found that ECDICT's independent `running` noun/adjective entry could shadow the contextual verb lemma. A candidate is used only when the existing dictionary repository returns an entry. Contractions try the exact form before a deterministic base-form fallback; ambiguous forms such as `it's` preserve the original surface and may fall back to `it`/`be`, so the UI must continue to label/display the original form in context. Possessives strip a final possessive marker only for lookup, never from the saved surface. Likely proper nouns are queried once with their original case and are not stemmed; this is a conservative heuristic, not named-entity recognition.

No external lemmatizer or language-rule package was added, so this task introduces no third-party license. ECDICT remains the only data source under ADR 0016. Numeric selections are retained and not queried. There is no fuzzy spelling correction: a local miss returns the original selection and full sentence with `spellingMayBeIncorrect=true` and `NOT_FOUND`; it does not silently substitute a candidate. This preserves a future AI context hook without presenting an unverified correction.

## Consequences and limits

- Results expose both `surface` and `matchedLemma` plus `entry`, miss reason, source offsets, and complete sentence. A miss is still a usable result for E6 context explanation.
- Regular morphology is intentionally a compact English-focused ruleset, not a general linguistic analyzer. Ambiguous derivations can produce a candidate only if that exact candidate exists in ECDICT; corpus quality remains relevant.
- Phrase support is explicitly bounded and maintained locally. Unknown expressions are not guessed; E5-T3 can offer a phrase-selection affordance using the accepted/rejected selection result.
- The underlying dictionary lookup is exact indexed ECDICT lookup. Existing indexed-query performance evidence in ADR 0016 is from E5-T1; E5-T2 must separately benchmark this composed use case on the Xiaomi 10S before claiming its device acceptance.

## Verification

Fixture-driven JVM tests cover regular and irregular forms, contractions, capitalization/proper-noun handling, numeric selections, possessives, hyphenated words, supported phrasal verbs, arbitrary and overlong selections, exact offsets (including a supplementary Unicode character before the selection), and preservation of surface/sentence on a miss. Coroutine test scopes use the shared `TestDispatchers` scheduler wrapper. No Room, network, or UI dependency was introduced.

On 2026-09-27, the composed lookup UseCase was measured on Xiaomi 10S (`M2102J2SC`, API 31) with the pinned ECDICT revision above. The instrumented test selected 10,000 random existing dictionary headwords and measured surface candidate resolution, exact indexed repository access, and domain-result mapping using `elapsedRealtimeNanos`. All 10,000 returned entries. Warm in-process result latency was P50 **0.543021 ms** and P95 **1.051927 ms**; the instrumentation test took 6.76 s. Database provisioning, character-range parsing and UI rendering are outside the timed interval. This is a local lookup measurement (not a full rendered-card measurement); it is comfortably below PRD 8.11's 300 ms local-result target. The source CSV and generated SQLite were temporary validation inputs, and neither is tracked or committed.
