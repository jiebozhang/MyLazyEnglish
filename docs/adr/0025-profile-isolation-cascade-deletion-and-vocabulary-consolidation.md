# ADR 0025: Profile Isolation, Cascade Deletion, and Vocabulary Consolidation

- Status: Accepted
- Date: 2026-09-27
- Scope: E1-T4 / EP-T1

## Context

Profile-owned learning data had been split across `family.db` (profiles, watch progress, lookup events) and `vocabulary.db` (vocabulary items, contexts, review attempts). SQLite/Room cannot provide one atomic transaction spanning those two database files. A failure while deleting a Profile could therefore leave private vocabulary data behind, or delete the Profile while another store still contained its learning history.

The Product Requirements require explicit `profileId` scoping, isolation between children, and complete removal of a deleted child's private learning data. Video, subtitle, import, and dictionary content is family/shared content and is not Profile-owned.

## Decision

1. Consolidate vocabulary items, vocabulary contexts, review attempts, and the legacy-import marker into `family.db` schema version 5. `FamilyVocabularyMigration.MIGRATION_4_5` adds the tables without destructive migration.
2. On first vocabulary access, import existing `vocabulary.db` rows into the new Room tables inside a single `family.db` transaction. Preserve IDs and stored review fields. A marker makes the operation idempotent; validate foreign keys before commit. On failure, the transaction rolls back and the legacy database is retained for retry. After commit, remove the legacy file; if the process stopped after commit and before cleanup, a subsequent access sees the marker and removes the leftover file.
3. Add `ON DELETE CASCADE` Profile foreign keys to vocabulary items and review attempts; context rows cascade from their owning vocabulary item. Watch progress and lookup events are explicitly deleted by `RoomProfileCleaner`, then the Profile row is physically deleted, all through the `ProfileDeletionCoordinator`'s Room transaction.
4. Profile-private tables and repository operations remain explicitly scoped by Profile ID. Shared videos, video assets, subtitles, dictionary data, and family records are not deleted with a child Profile.
5. After the Room transaction commits, `ProfileCoordinator` clears a stale active selection and selects the first remaining valid Profile, or clears the selection if none remain. Subscribers receive `ProfileChanged` so feature state can discard the prior scope and reload.

## Atomicity and interruption

All database cleaners registered with `ProfileDeletionCoordinator` execute in the same Room transaction. Any cleaner failure or cancellation rolls back all database writes. The active Profile pointer is stored separately in DataStore and cannot be enlisted in the Room transaction. It is therefore reconciled after commit; if the process is interrupted before reconciliation, `DataStoreCurrentProfileStore` validates the pointer against the Profile repository and clears an ID that no longer exists. It never substitutes another Profile's data under the stale ID.

External file cleanup is intentionally excluded: Profile-owned media is not defined by the current schema; videos/media remain family-scoped and must be managed through video-level deletion policies.

## Verification

- Room v4-to-v5 migration test validates the added tables and indexes, foreign keys, and preservation of existing v4 rows.
- Legacy import tests verify repeated import is idempotent, stored word/context/review data survives, and failed imports roll back while retaining the source database for retry.
- Cross-Profile database test creates separate watch progress, vocabulary/context, review attempt, and lookup event rows. Deleting Profile A removes A's records, preserves Profile B's records, and an injected later-cleaner failure proves rollback.
- `ProfileCoordinator` tests cover active Profile fallback to a sibling and clearing selection when the last Profile is removed.
- Device instrumentation test repeats Profile-scoped data creation and deletion against Room on Android.

## Consequences and risks

- `family.db` moves from schema v4 to v5; the exported v5 schema is checked in for subsequent migrations.
- The former `vocabulary.db` is a one-time import source only. New application writes go to `family.db`.
- Legacy rows referring to a Profile absent from `family.db` fail import rather than being silently reassigned or dropped; the original database remains available for diagnosis/retry.
- `ProfileRepository.deleteProfile` retains its existing tombstone semantics for non-destructive workflows. Product deletion must go through the authenticated `ProfileDeletionCoordinator` with the registered `RoomProfileCleaner`.
- No cloud synchronization, remote deletion, or family-shared media deletion is implemented here.
