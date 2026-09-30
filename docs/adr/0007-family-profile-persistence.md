# ADR 0007: Family/Profile persistence and Room v1

- Status: Accepted for E1-T1; host validation passed on 2026-09-25.
- Requirements: PRD v2.2 PRO-01 through PRO-04, sections 8.1, 11 and 14; checklist sections 4, 4.1, 4.2 and 7.1.
- Scope: Data layer only; no PIN, UI, downstream private tables or cleanup workers.

## Model and schema ownership

Room v1 contains only `families` and `profiles`. The schema is exported by the pinned Room Gradle plugin to `apps/android/core/database/schemas/com.lazyeng.family.core.database.FamilyDatabase/1.json`. There is no prior schema and no migration. E2-T1 owns v2 and its 1-to-2 migration. No destructive fallback is configured.

PRD section 11 explicitly labels its field table simplified. Section 8.1 supplies the complete Profile list already represented by T0-3. `level`/`preferences` in the simplified table map to `english_level`/`ui_preferences` in the detailed definition; they are not duplicate columns. No domain fields were added or removed.

| Entity | Stored columns |
| --- | --- |
| Family | `id`, `settings`, `created_at` |
| Profile | `id`, `family_id`, `nickname`, `avatar_id`, `role`, `age_mode`, `english_level`, `ui_preferences`, `created_at`, `deleted_at` |

T0-3's nullability is preserved: only avatar and deletion timestamp are nullable. Map values are JSON objects of strings, parsed with the platform structured JSON API. Instants are ISO-8601 TEXT, preserving the full domain Instant precision; these tables currently need only null checks on deletion time, not SQL chronological ordering. Roles retain T0-3 enum names. Age mode, level, settings and preference values retain T0-3's open string representation; this task invents no additional defaults or categories. IDs and timestamps come from the caller. ID values must be nonblank on save.

Profile IDs remain globally unique. An explicit unique `(family_id, id)` index provides the scope boundary for future references, and a restrictive foreign key prevents orphan profiles. Writes use insert/update transactions rather than REPLACE. Creation time and family ownership cannot be changed through save.

## Minimal contract refinement and isolation

T0-3's ProfileRepository had only `profileId`, which cannot express an expected family and therefore cannot enforce the requested cross-family null result. Its three existing methods now also require `familyId`; no unscoped overload remains. The domain types and repository responsibilities are preserved. There were no existing callers or concrete implementations to migrate.

Every Profile DAO read and update matches both IDs. A missing, deleted or mismatched read returns null. A mismatched deletion is a no-op. Wrong-scope writes, duplicate global IDs and orphan inserts fail without changing another record. Repository returns remain domain objects; entities/DAOs/converters are internal to `core/database`. Family settings access is explicitly family-scoped. No all-family/profile private-data API exists.

`CurrentProfileStore` is a new pure Kotlin preference contract in `core/model`. Its restoration method returns only the validated `ProfileId` pointer for the requested family, not Profile data. This is the bootstrap operation before a caller knows the selected profile ID. Actual Profile reads still require both IDs. No production code depends on `core/testing`.

## Deletion and recovery

Deleting a Profile stamps PRD's `deleted_at`, hides it from live reads and retains its fields for audit. Repeated deletion preserves the original timestamp; saving cannot resurrect it, and a timestamp before creation is rejected. Family has no PRD deletion field or repository delete method, so this task adds neither a Family tombstone nor a physical Family-delete API. PIN confirmation and cleanup coordination belong to E1-T2/E1-T3; downstream event/job cleanup belongs to E1-T4.

The non-sensitive current pointer is an atomic pair of family/profile IDs in Preferences DataStore, never a Room table. Selection validates an active Profile first. Restoration validates against Room on each call. Missing, deleted or forged cross-family pointers return null and clear the stale matching pair; a request from another family cannot clear its selection. No first-profile fallback exists. A mutex serializes operations on the application-owned store facade. DataStore and Room have no shared transaction: if deletion wins after selection is written, the next restoration still rejects it. Later UI must resolve selection on activation/switching and not cache old Profile content across switches.

The context-backed factory creates one DataStore per application context via the delegate and clears only corrupted selection preferences through the corruption handler. Ordinary I/O/database exceptions propagate; cancellation is not swallowed. No database-reset recovery exists. The application composition and picker UI are later tasks; callers own one database/store facade for the application lifetime.

## Validation

Host tests use real generated Room DAO code with Robolectric/native SQLite, not an in-memory fake Repository. Synthetic fixtures cover full field round trips, two-family and sibling isolation, mismatched reads/deletes/writes, foreign-key rejection, immutable creation/ownership, and retained tombstones. Tests assert the exact initial table columns and inspect the exported v1 schema.

Disk-backed integration tests stop DataStore's coroutine scope and close Room, recreate both instances against the same files, then check restored selection and data. They also cover deleted/missing/cross-family pointers, invalid selection preserving an existing selection, scoped clear, and atomic family/profile switching. This models storage reopening after process loss on the host; it does not claim device force-stop or UI verification. No device acceptance or v1 migration is required for this task.

Repeatable commands (from `apps/android`, with the local JDK 17/Android SDK environment):

```text
./gradlew :core:database:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest :core:model:test :core:common:test :core:testing:test testDebugUnitTest lintDebug
```

Executed using `gradlew.bat` on Windows/JDK 17. Both commands passed. The full verification completed with 836 actionable tasks (782 executed, 54 up-to-date).

| Acceptance | Evidence/result |
| --- | --- |
| PRD field alignment / v1 export | `FamilyRepositoryTest.v1SchemaHasExactlyPrdColumnsAndNoCurrentProfileOrPinTable`; v1 JSON contains exactly the two owned entities |
| PRO-02 persistence / PRO-04 isolation | 10 FamilyRepositoryTest cases passed, including two-family/sibling isolation and cross-scope null reads |
| Auditable deletion | Tombstone retained across repeated deletion; creation time protected; racing edits cannot restore deleted records |
| PRO-03 restoration | 9 CurrentProfilePersistenceTest cases passed against disk-backed Room/DataStore, including stale/forged selection and database failure |
| API scope | All ProfileRepository methods require both IDs; there is no list-all private-data query; domain source contains no Room/network-framework import |
| Build and tests | App debug APK and existing test APK build tasks passed; 35 host unit tests total, zero failures/errors/skips, including the 19 new data-layer tests |
| Lint | `core/database` and `core/datastore`: no issues. Full Lint passed with zero errors; app reports 14 dependency-version notices (including the pinned Robolectric version), design system has one existing modifier-order warning, secure-storage spike has one existing API-annotation warning |

Local reports: `apps/android/core/database/build/reports/tests/testDebugUnitTest/index.html`, `apps/android/core/database/build/test-results/testDebugUnitTest/TEST-*.xml`, and each module's `build/reports/lint-results-debug.*`. Build products/reports remain ignored and are not committed. No device/UI acceptance was run for this data-layer task; the test APK task is build-only and is not claimed as executed instrumentation. The React prototype and local environment notes are unchanged.

References: [Room schema export](https://developer.android.com/training/data-storage/room/migrating-db-versions), [Robolectric setup](https://robolectric.org/getting-started/), [DataStore](https://developer.android.com/topic/libraries/architecture/datastore). Project versions stay pinned in the Version Catalog; these links are implementation references, not a reason to upgrade the existing Room version.
