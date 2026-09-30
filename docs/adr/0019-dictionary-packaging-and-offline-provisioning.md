# ADR 0019: Dictionary Packaging and Offline Provisioning

## Status

Accepted for E5-T4, with a CEFR-data limitation documented below.

## Context and data decision

E5-T3 correctly showed that a clean installation has no private ECDICT file. E5-T1 pinned ECDICT revision `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b` (source CSV SHA-256 `1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`) and recorded the product owner's explicit decision to accept its rights/provenance risk for private household sideloading. This package follows that bounded decision and includes the ECDICT MIT notice. It is not approval for public or commercial redistribution; see ADR 0016 and ADR 0004.

No trusted CEFR dataset is included in ECDICT. NGSL licensing remains unresolved in ADR 0004, so no NGSL-derived labels were added. Results continue to expose `cefr = null`; the request's example “A1” label is intentionally not fabricated. A future CEFR package requires a separately verified source/license and its own version/hash.

## Package construction

`tools/import_ecdict.py` verifies the pinned CSV and creates the full intermediate SQLite outside the checkout. `tools/build_ecdict_mini.py` then takes the 20,000 most frequent rows with a positive BNC or FRQ rank (ordered deterministically by the smaller available rank, then case-insensitive headword), and explicitly retains 14 commonly supported phrasal verbs plus `run` and `jump`. The result has **20,014 entries**, preserves the seven-field schema and indexed `COLLATE NOCASE` primary key, and embeds the ECDICT revision, source hash and license metadata. The pinned source is never committed.

Measured generated sizes:

- Full source CSV: 65,933,428 bytes.
- Full intermediate SQLite: 68,526,080 bytes (kept outside Git).
- Mini SQLite: 4,726,784 bytes (4.51 MiB).
- APK asset `ecdictionary.sqlite.gzip`: 2,156,351 bytes (2.06 MiB). The unusual suffix avoids AAPT's automatic `.gz` expansion/renaming behavior; `AssetManager` supplies the original gzip stream to the provisioner.
- Uncompressed database SHA-256: `3f9a0542ec1375d64b8d5068014be62ec1b9589191fdee81d4a0cbec1232e6b4`.
- Compressed asset SHA-256: `f84cc9e1672af873772c35892d78e981b5dd194cf9ffaa914b532b9818eda591`.

The package generator is deterministic (gzip timestamp is fixed) and has host tests for reproducibility, required entries and rejection of unpinned inputs. Generated output includes `dictionary-package.json`, `ecdictionary.sqlite.sha256`, and `ECDICT-NOTICE.txt`.

## Runtime provisioning

`DictionaryAssetProvisioner` is owned by application assembly and writes to `files/dictionary/ecdictionary.sqlite`. All checksum, decompression and SQLite integrity work runs on the injected IO dispatcher. A mutex serializes concurrent requests. It verifies the asset's SHA-256 and SQLite `user_version`/`integrity_check`, writes a sibling `.part`, then atomically replaces the target only after validation. Failed/cancelled runs never mark Ready and remove the partial file; a later call repairs missing/corrupt/incomplete targets from the immutable APK asset. A per-process verified file fingerprint avoids rehashing the database on every subsequent word lookup.

The provisioning state is exposed as a `StateFlow` (`NotStarted`, `Preparing`, `Ready`, `Failed`). The player's first offline lookup awaits provisioning; a provisioning failure is reported through the existing package-unavailable message, not as a word-not-found result. The repository remains read-only and no Room schema is changed.

`tools/provision_ecdict.ps1` is an optional root-only developer shortcut for copying an already-built database into the test app's private directory without an APK reinstall. It is not used by app code or normal household installs.

## Validation

- Host Python generator tests (5 total across importer and mini-package suites) verify deterministic output and pinned-revision validation. Dictionary-module Robolectric tests (14 total) cover first install, repeat/idempotent access, serialized concurrent calls, corrupt/incomplete target recovery, manifest mismatch, SHA-256 validation and SQLite integrity.
- Xiaomi 10S (`M2102J2SC`, API 31) instrumentation passed all 11 `PlayerScreenTest` cases. The bundled-asset test provisions the APK asset, renders real `running` → `run` pronunciation/Chinese senses, checks real `jumped` → `jump` and `give up` hits, and verifies the vocabulary capture retains dictionary entry, surface, lemma, sentence, video/profile scope and timestamp.
- Device warm lookup benchmark selected 10,000 random entries from the installed package: **10,000/10,000 hit**, P50 **0.782813 ms**, P95 **1.944218 ms**. Measurement uses `elapsedRealtimeNanos` around the composed local UseCase and excludes first-install decompression, sentence selection parsing, and full Compose rendering. Instrumentation elapsed time was 9.711 s.

The database stores frequency ranks, but this task does not display them as a level. The warm 10,000-query result does not include first-install decompression; provisioning is asynchronous and occurs on first lookup before the local result is shown. On device, the installed database's SHA-256 matched the packaged manifest after provisioning.

## Consequences and remaining risk

The compact package makes offline lookup available on a fresh install at a modest APK size increase. It pins data and integrity but does not fetch updates. To update, manually review/pin a new source revision, rerun the import/build checks, inspect quality and rights, then ship a new APK/package; preserve quarterly upstream review as described in ADR 0016.

ECDICT's declared MIT notice does not prove the rights chain for every upstream-derived entry. The accepted scope remains personal household use only. Public distribution needs a new licensing/provenance decision. CEFR display remains unavailable until an independently cleared and reliable level source is selected.
