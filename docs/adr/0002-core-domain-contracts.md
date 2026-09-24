# ADR 0002: Core Domain Contracts

- Status: Accepted for T0-3
- Date: 2026-09-25

## Context

The PRD defines the cross-layer data model and local repository boundaries, while Android persistence, transport, and UI are implemented by later tasks. Domain APIs must stay independent of Room entities and HTTP DTOs and must make profile ownership explicit.

## Decisions

- `core/model` owns pure Kotlin domain values, strongly typed IDs, state transitions, and repository contracts. `core/common` owns result/error types and platform capability contracts. Database, network, media, and feature modules provide later implementations/adapters.
- UDF remains `UiState` immutable and UI-facing, `UiEvent` an intent sent to a state holder, and `UiEffect` a one-time navigation/message effect. Route/ViewModel adapts domain results to UI messages; Screen receives state and event callbacks and knows neither persistence nor transport types.
- Profile-private repository operations require `ProfileId` as a method argument, including append/save methods whose value object also carries it. No all-profile query is exposed. Shared AI content and jobs require `FamilyId`; dictionary lookup is shared and profile-independent.
- Profile deletion is a domain request marked with `deletedAt`; its later implementation must require parent PIN confirmation, cancel only that profile's background work, and clean only that profile's private data. Video deletion detaches media references while preserving the minimum learning-event snapshot. Future sync uses tombstones as specified by the PRD. T0-3 defines contracts only and performs none of these operations.
- Subtitle content is versioned. A draft can be submitted to `publishDraft`; published versions have a separate immutable type, and no repository operation updates a published version. Timing correction therefore creates and publishes a new version. LookupEvent and ReviewAttempt expose append/list only; vocabulary snapshot remains mutable and is reconstructible from review events.
- `AppError` carries a stable extensible code, a handling category, a localization key for user-facing copy, and a separate optional diagnostic payload. Provider, transport, credentials, raw response, and stack details are not user copy. Error codes listed in PRD 15 are covered; provider test connection distinctions explicitly named by the PRD are also represented.

## Assumptions and boundaries

- Status fields use sealed types with exact wire codecs and an `Unknown(raw)` case. Import status reuses only the named Video pipeline lifecycle values; subtitle availability uses the explicit no-subtitle/available conditions; translation uses the documented available/stale conditions; AI jobs recognize only queued/cancelled wording, and generated content recognizes only `READY`. Other values remain losslessly represented as Unknown. Video and vocabulary state machines remain closed because their values are explicitly enumerated.
- `source_ref` is represented as an opaque reference, never as a filesystem path. The platform adapter owns interpretation and permission persistence.
- Exact repository query shapes beyond the stated Profile/family scope are initial contracts and may be refined by their owning Epic without weakening scope requirements.

## Consequences

Domain contracts compile without Room or network dependencies. UI can map localization keys without seeing diagnostic details. Concrete storage deletion, profile authorization, AI mapping, URI grants, and platform behavior remain for their assigned implementation tasks and require their own tests.
