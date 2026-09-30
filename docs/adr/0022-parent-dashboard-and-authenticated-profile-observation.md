# ADR 0022: PIN-Gated Parent Dashboard and Profile-Scoped Observation

## Status

Accepted for the supplied E6-T1 parent dashboard task.

## Context

The supplied task card requests a parent overview, while the repository checklist has no E7-T4 and names its own E6-T1 "Provider contracts, network security and configuration storage". Implementation scope follows the explicit parent-dashboard task card and does not implement that separate AI-provider task.

The parent route already uses `ProfilesViewModel` and `ParentSession` for PIN verification. Authorization is memory-only, expires after five minutes without touch, and is revoked when the parent route stops or is disposed. The current `WatchProgress` record stores only a latest playback position, completion fraction, and update instant; it is not a playback-duration event. `LearningSessionRepository` is currently only a contract and has no durable implementation in the active schema.

## Decisions

- Keep PIN verification and authorization ownership in `ProfilesViewModel`/`ParentSession`; the dashboard is rendered only in the authenticated `OVERVIEW` phase. It receives no persisted authorization flag. Returning from the route disposes the gate and requires PIN again.
- The parent may select a profile locally to inspect its data. This selection never writes `CurrentProfileStore`, publishes `ProfileChanged`, or changes the child session.
- All data reads are scoped by the selected `ProfileId`. The application adapter obtains and checks `ParentAuthorizedScope`, verifies the profile is live and in the same family, then checks the scope again after repository reads.
- Streak days derive from dates with real saved watch-progress records. Vocabulary counts come from that profile's vocabulary repository. Recent activity joins real progress to authorized family video records and displays the stored completion fraction.
- Today/week watch-minute metrics remain explicitly unavailable until actual foreground playback sessions are persisted. A seek position or completion value is never converted into elapsed watch time.
- Time-limit controls, content-management screens, and PIN change are reserved actions with a clear unavailable message; video import links to the existing parent import flow.

## Consequences

The dashboard gives accurate profile-isolated counts and recent activity without creating a new database migration or claiming unsupported duration analytics. A later progress task should persist foreground active seconds, exclude paused/background time, and then populate today/week duration.

## Verification

JVM tests cover per-profile reload and recoverable data errors. `NavigationParentGateTest` asserts the authenticated dashboard and metrics render, the recent-activity item appears, selecting another child refreshes its vocabulary count, the child bottom bar is absent, and saved-state restoration/backgrounding returns to the PIN gate. The test was run directly with `am instrument` on the Xiaomi 10S after manual APK installation and the documented MIUI AppOps setup.
