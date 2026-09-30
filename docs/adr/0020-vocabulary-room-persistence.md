# ADR 0020: Vocabulary Room persistence and player capture

## Status

Accepted for E7-T1.

## Context

E7-T1 owns the vocabulary persistence contract and the Room schema slot identified as v6 in the project task list. The checked-in `FamilyDatabase` at the E5-T4 base is still schema v3. The task list assigns v4 to E5-T4 LookupEvent and v5 to E6-T2 AI records; neither schema is present on this branch. Creating a v6 migration from v3 would skip owners and invent their tables, while allocating v4/v5 here would conflict with the version ownership table.

## Decisions

- Store vocabulary in a separate `vocabulary.db`, Room schema version 1. It is a local mutable learning store, separate from the read-only, versioned ECDICT database and from `family.db`. The FamilyDatabase version remains unchanged; its exported schema is not altered by E7-T1.
- Every item, context and review event carries `profile_id`; all repository reads, writes and deletes require a `ProfileId`. The separate database cannot declare a SQLite foreign key into `family.db`, so the player host checks that the captured Profile remains the active, live Profile before saving and the repository scopes data by the indexed profile key. A future profile-deletion coordinator must delete that Profile's vocabulary records.
- `vocabulary_items` is unique on `(profile_id, lemma)`. A first capture creates a LEARNING item at stage 0. Inserting the same source occurrence is idempotent through a unique context index on Profile, lemma, video, subtitle line and timestamp. Another sentence/time remains a separate `VocabularyContext`, satisfying VOC-02 while presenting one lemma item.
- Capture stores the clicked surface form, normalized lemma, pronunciation, first selected dictionary sense when available, sentence snapshot, video, subtitle line, playback timestamp and capture time. Misses remain saveable with nullable pronunciation/definition.
- Repeated capture uses `INSERT OR IGNORE` and does not reset an existing review status or stage. ReviewAttempt has no update/delete API and is not cascaded when a vocabulary item is removed, preserving append-only review history. Item deletion is Profile-scoped and removes its contexts only.
- The PRD defines no numeric vocabulary item cap; none is invented here. E7-T3 owns review scheduling behavior; `next_review_at` is nullable until that workflow assigns it.
- The application maps `PlayerVocabularyCapture` into the domain capture and only reports success after the Room transaction succeeds. Each callback revalidates active Profile scope; the player's existing UI state changes to saved only on callback success.

## Consequences

- The independently exported `VocabularyDatabase/1.json` is the initial schema fixture for this file. No migration is needed for its first creation. No FamilyDatabase v3-to-v6 path is claimed. Before any future merge of vocabulary tables into `family.db`, reconcile E5-T4 v4 and E6-T2 v5, then create the task-list-owned v5-to-v6 migration and test against the actual v5 fixture.
- The E5 dictionary package remains read-only and is not copied into this database. Captured definitions are immutable context snapshots so later dictionary updates do not rewrite a learner's saved context.

## Verification

- Robolectric Room tests cover atomic capture, idempotent duplicate occurrence insertion, distinct context preservation, state preservation, Profile-scoped reads and deletion.
- `PlayerScreenTest.bundledDictionaryResolvesRealEntriesAndPreservesCapturePayload` uses the bundled ECDICT and a disk-backed vocabulary database; it verifies the UI save action, persisted item/context fields, then closes and reopens the database to prove persistence.
- Main unit tests, lint and the Xiaomi 10S direct instrumentation result are recorded with E7-T1 delivery evidence.
