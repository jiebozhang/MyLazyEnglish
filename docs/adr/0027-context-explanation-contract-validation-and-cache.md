# ADR 0027: Context Explanation Contract, Validation, and Cache

- Status: Accepted
- Date: 2026-09-28
- Scope: E6-T2 context explanation workflow

## Context

E6-T1 supplies provider-neutral context explanation requests and OpenAI-compatible and Anthropic adapters. E6-T2 adds the minimized request contract, output validation, family-scoped persistence, caching, request de-duplication, and budget accounting. The PRD's JSON contract is authoritative; this ADR does not add generated fields.

The task table anticipated Room v5 for AI records, but E1-T4 had already used v5 for vocabulary consolidation. The observed database version was therefore 5. This task adds a non-destructive 5-to-6 migration and preserves the prior v5 schema fixture and data.

## Decisions

### Minimized request and output

The request contains only `word`, `lemma`, `sentence_en`, optional `previous_line_en` and `next_line_en`, `learner_level`, and `ui_language`. It excludes profile names, learner names, and conversation or viewing history. Provider/model configuration and system instructions are adapter metadata, not learner context. Request construction tests assert the excluded fields are absent.

Output must contain exactly the PRD fields: `meaning_zh`, `why_here_zh`, `simple_example_en`, `simple_example_zh`, `part_of_speech`, `confidence`, and `safety_flag`. Parsing rejects unknown/missing fields, invalid types, out-of-range confidence, and invalid safety values. Text is normalized and control characters are removed. `meaning_zh` is limited to 24 characters and `why_here_zh` to 80. Unsafe or invalid output is never surfaced as ready content.

An invalid/unsafe response permits one stricter repair request using the same minimized context. The workflow does not loop. If the repair remains invalid, the result falls back to local meaning and is not persisted as READY. Existing READY content is retained and returned as fallback when regeneration fails.

### Family-scoped cache and persistence

AI generated content, jobs, and budget usage live in the main Room database and reference `familyId`, not `profileId`. Profile deletion therefore does not erase family-level AI cache, consistent with ADR 0025. The migration creates the records with family foreign keys and indexes; it does not rebuild or destructively migrate existing tables.

The cache key includes family ID, lemma, a hash of the current and adjacent sentence context, subtitle line version, learner level, UI language, prompt version, provider, and model. Only validated, safe, cleaned output is eligible for READY persistence or cache hits. Raw sentence text is not stored as the cache key; the input reference is a digest.

Concurrent identical requests share one in-flight operation. The shared operation is app-scoped so closing one UI waiter does not cancel work needed by another waiter. The persistent cache survives process restarts; single-flight de-duplication is process-local.

### Budget accounting

Request reservation and usage updates are transactionally scoped by family and UTC day/month buckets. Cache hits are recorded separately and do not trigger provider requests. If the provider does not report usage, token usage remains unknown rather than being estimated. Limits are configurable and unset by default until the settings/budget task supplies policy.

## Verification

- Unit workflow tests cover request minimization, strict schema and field bounds, unsafe responses, one repair only, old-success retention, cache-key dimension separation, and three concurrent same-key requests resulting in one provider call.
- Provider adapter tests use local fakes/MockWebServer with conspicuously fake credentials; no real network is used.
- Room migration tests seed a v5 database and verify family/profile/video/subtitle/vocabulary/review/lookup data survives upgrade to v6 and that new records remain family-scoped.
- A device migration test is provided for direct `am instrument` execution on the Xiaomi 10S; it must not be run through Gradle's connected test task because that reinstalls packages and resets device AppOps.

## Consequences and limitations

- Prompt-version changes naturally invalidate cache entries without deleting older successful content.
- Cache dimensions are deliberately conservative; changing any learner/context/provider dimension causes a miss.
- Budget usage without provider-reported token counts is an observed request count, not an invoice-grade cost estimate.
- Family-shared output must remain free of profile-specific information; future prompt or provider changes must preserve the minimized contract.
