# ADR 0016: ECDICT Import and Exact Lookup

## Status

Accepted for E5-T1 under the product owner's explicit private-household-use decision. No ECDICT data file is committed to this repository.

## Context and Decision

PRD v2.2 selects ECDICT as the offline base dictionary. The owner has confirmed acceptance of the personal household-use risk and instructed this task to use ECDICT. This supersedes ADR 0004's engineering hold for this scope; it does not erase the documented upstream provenance gap or constitute legal advice. ECDICT's declared MIT notice is preserved in `docs/dictionary/ECDICT-NOTICE.txt` and must accompany each generated package. Public/commercial redistribution remains outside this decision.

The raw CSV is pinned to commit `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b` and SHA-256 `1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`. A standard-library Python importer builds an independent SQLite database with schema version 1, source metadata and a case-insensitive primary-key index. Source rows with blank headwords are skipped; case-insensitive duplicates use last-row-wins in source order, matching the earlier spike importer. Invalid optional `bnc`/`frq` values are stored as SQL `NULL` and counted in the import report. The Android adapter opens the database read-only, verifies `user_version`, performs exact indexed lookup and maps rows to the existing `DictionaryEntry` domain model. It does not expose cursors or add data to Room.

The seven source fields are retained in SQLite. The current domain contract has no numeric frequency property, and E5-T1 does not own CEFR estimation; therefore lookup results set `cefr` and `frequencySource` to `null` rather than imply an unsupported rating. Translation lines provide the current `senses` list; phonetic and part of speech remain nullable when blank.

## Package and Update Policy

The source CSV and generated database stay outside Git. Build the database using `tools/import_ecdict.py`; deliver a separately versioned package only through a controlled data release, including the MIT notice and source version/hash. Do not download updates at runtime. Review upstream manually quarterly, pin and checksum any candidate update, validate it separately, and retain the previously working package until replacement passes validation. No predictable upstream cadence was found in ADR 0004.

## Validation

- Importer unit tests use synthetic CSV fixtures to verify schema, metadata, blank-headword handling, duplicate replacement and required-column validation.
- Pinned-source import observation: 770,611 source rows and unique words; blank headwords `0`, replaced duplicates `0`, invalid `bnc` `0`, invalid `frq` `0`.
- Android repository tests verify exact case-insensitive lookup, misses, domain mapping, read-only access and schema-version rejection.
- `EcdictLookupBenchmarkTest` selects 10,000 random existing words from the generated database on-device, times repository lookups with `elapsedRealtimeNanos` (excluding assertions), and reports nearest-rank percentiles via instrumentation status.
- Xiaomi 10S (`M2102J2SC`, API 31) result: 10,000/10,000 exact hits; warm indexed lookup P50 `0.571511 ms`, P95 `1.981093 ms`; instrumentation test elapsed `8.531 s`. This meets PRD v2.2's `<300 ms` local lookup target. This is a warm, in-process, single-connection measure and does not include cold database provisioning/startup. The generated SQLite package was 68,526,080 bytes.

## Risks

ECDICT's own repository declares MIT but does not establish a rights chain for each upstream-derived corpus/annotation. This task follows the owner's explicitly bounded family-use decision and retains attribution; any broader distribution needs a new rights review. The 65+ MiB generated data package is not bundled in source or a debug APK by default, so a controlled installation/provisioning step is required for device use.
