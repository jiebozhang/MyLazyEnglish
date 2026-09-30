# ADR 0021: Vocabulary review scheduling

## Status

Accepted for E7-T3.

## Context

PRD 8.15 specifies the MVP intervals: Forgot after 10 minutes, Uncertain tomorrow, Remembered after 3 days, then gradually longer for consecutive cross-day successes. E7-T1 stores vocabulary in an independent `vocabulary.db` so it does not claim a missing FamilyDatabase schema version.

## Decisions

- `ReviewSchedule` is pure Kotlin and receives the review `Instant`; it never reads the device clock. Newly captured items with no scheduled time are due. Future-due and PAUSED items are excluded.
- FORGOT returns the item to LEARNING/stage 0 for 10 minutes, increments lapse and review counts, and resets consecutive remembered count. UNCERTAIN sets REVIEW with a one-day interval and resets the remembered streak. REMEMBERED advances 3, 7, then 14-day intervals; the third consecutive success marks MASTERED. Later mastered successes extend 28/56/112-day intervals, capped at stage 6.
- Review history is append-only. `VocabularyRepository.recordReview` writes an attempt and its updated item snapshot in one transaction. `(profile_id, event_uuid)` is unique so a repeated event cannot apply the schedule twice. Profile scope is validated at the repository boundary.
- The separate `VocabularyDatabase` moves from version 1 to 2, adding review/lapse/streak counters and last-review time with defaults that preserve existing rows. It does not consume FamilyDatabase v7/v8; this follows the E7-T1 independent-store decision in ADR 0020. The prior schema remains the migration fixture.
- Reduced-motion mode uses an immediate front/back change instead of the 3D flip animation. Exiting a session asks for confirmation; completed ratings remain persisted and unfinished due items stay due.

## Consequences

This is a deterministic, explainable MVP schedule, not SM-2/FSRS. Intervals are centralized in one pure function so a later policy change can be tested without UI or Room dependencies. Device wall-clock adjustments can affect due eligibility because persisted review times are instants; no monotonic-clock representation is used across process restarts.

## Verification

- Unit tests cover the fixed intervals, stage/status transitions, due filtering, profile-scoped review persistence, and duplicate event UUID behavior.
- `VocabularyMigrationDeviceTest` upgrades the exported vocabulary schema v1 to v2 and checks prior attempt/item data and default counters.
- `FlashcardReviewScreenTest` covers front/back rendering, TTS callback, ratings, empty queue and completion summary on device.
