# ADR 0023: Lookup Event Deduplication and Cancellation

- Status: Accepted
- Date: 2026-09-27
- Branch baseline: `codex/e6-t1-parent-dashboard` at `e28c001`

## Context

PRD 8.10 requires repeated taps for the same profile, lemma, and subtitle line to create only one lookup event, while allowing the dictionary sheet to reopen. PRD 16 defines lookup events as append-only analytics events. The selected baseline contains the Family Room database at schema version 3. The task list assigns LookupEvent to schema v4; E7 vocabulary persistence is held in a separate database and does not advance this database's version.

## Decisions

1. Add `lookup_events` in the Family database migration from the observed schema v3 to v4. The event stores UUID, profile, surface word, normalized lemma, subtitle line, video, and occurrence time. The profile foreign key uses `RESTRICT`; profile-scoped reads require a profile ID.
2. Enforce durable deduplication with a unique index on `(profile_id, lemma, line_id)`. The lemma is trimmed and lowercased with `Locale.ROOT` before insertion. This is a permanent per-line deduplication rule, not a time window: PRD specifies the tuple but does not define an expiry interval. Subtitle lookups supply a non-null line ID. The model permits a null line ID for non-subtitle callers, for which SQLite unique-index semantics do not deduplicate nulls; such callers must not use this event path until a stable line key exists.
3. `LookupEventDao` exposes insertion with conflict-ignore and profile-scoped reads only. There are no update or delete methods, preserving append-only semantics. Repository validation ensures that the event's profile matches the explicitly requested profile.
4. `LookupEventCoordinator` shares a compatible in-flight lookup for the same profile and subtitle line, and records the event only after a successful lookup returns. Reopening a sheet after completion may query again, but the database uniqueness constraint prevents a duplicate event.
5. Each request captures its originating profile. Activating a different profile cancels that profile's outstanding lookup jobs. Before persistence, the coordinator checks both its active profile and the current-profile provider; it never rewrites an old result to the newly active profile. Cancellation is propagated to the caller.

## Migration and Compatibility

The task list's ownership table says this work is database v4. The actual database version at the selected baseline was v3, so the implementation adds and registers `MIGRATION_3_4` and exports schema 4. A v3 schema fixture verifies existing Family, Profile, Video, ImportJob, WatchProgress, SubtitleTrack, SubtitleVersion, and SubtitleLine rows survive unchanged, while the new table and foreign key are created. No destructive migration is used.

## Validation

- Unit migration test upgrades the checked-in v3 fixture and verifies old rows, new schema, foreign key behavior, event deduplication, and profile scoping.
- Coordinator tests verify equivalent simultaneous taps share one query/event and profile switching cancels an in-flight request without appending an event.
- Device instrumentation migration coverage is included for direct `am instrument` execution after manual installation; do not use Gradle's `connectedDebugAndroidTest` on the MIUI device because it reinstalls packages and resets AppOps.

## Consequences

Lookup analytics are idempotent per subtitle line for the lifetime of the local database. If product analytics later requires repeated intentional lookups to count, the event identity rule must be revised explicitly (for example, with a defined dedup window or session key) and migrated; callers should not silently bypass the unique index.
