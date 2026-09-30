# ADR 0030: Parent Member, Content, and Storage Management

- Status: Accepted
- Scope: E8-T3

## Context

The E8-T3 task card asks for member management, child visibility and video removal, storage reporting, cache clearing, and a local JSON export. The checked-in task list has a narrower E8-T3 summary (content states, member overview, cache categories, export/delete, and offline storage). This implementation follows the supplied task card while retaining the PRD's data and deletion rules.

## Decisions

- All management pages are nested under the existing PIN-authorized Parent overview phase. They use the same in-memory `ParentSession`; returning to the dashboard keeps that session, while leaving/stopping the parent route continues to revoke it through the existing gate.
- Profile creation and deletion use `ProfileCoordinator`. Deletion requires an impact confirmation and a second PIN verification and delegates to the E1-T4 Room transaction. The final live child cannot be deleted. After successful deletion, the existing profile-change flow refreshes the authenticated overview and active-profile reconciliation.
- `hiddenFromChildren` is stored in the existing structured `VideoMetadata` JSON column. Older JSON without the property decodes as visible. Child `getVideo`, `listVideos`, and recommendations enforce this flag in `RoomVideoRepository`, not only in the UI. Parent visibility changes require a live authorized scope.
- Video removal is transactional for Room data: subtitle rows/tracks, import jobs, all profiles' watch-progress rows for that shared video, assets, and the video row are removed. Append-only lookup events and vocabulary contexts remain as learning snapshots, consistent with the PRD rule that removing a video must not erase a word learned in another context.
- Database and filesystem operations cannot share a Room transaction. The implementation commits catalog cleanup first, revalidates the parent authorization, then deletes only app-owned media and thumbnails. A process termination between these steps may leave an orphaned private file, but cannot delete an external/SAF original; orphan reclamation can be added to a later managed-media integrity sweep.
- E2-T2 stores SAF references and does not make a private copy. Consequently, removing an imported SAF video removes the app's catalog record and access metadata but never deletes the user's original document. Any future app-managed media file is deleted only when its canonical path is a regular file under `filesDir/media`; thumbnail cache removal is constrained to the app's own thumbnail cache directory. There is no filesystem operation on arbitrary SAF URIs.
- Storage reporting uses recursive physical file sizes for app-managed media, the offline dictionary, Room database plus WAL/SHM, and cache directories, and `StatFs` for available app-filesystem space. SAF originals are not counted as app-owned storage.
- Cache clearing deletes only `cacheDir` and `externalCacheDir` contents. It does not touch the dictionary asset, databases, media, or learning history.
- Backup is an explicitly requested JSON file written to the public Downloads collection on Android 10+ (or the app-specific Downloads directory on earlier Android versions) while the authenticated management operation is active. This avoids crossing the existing `ON_STOP` parent-session revocation boundary with a document-picker Activity. It contains family settings after secret-like keys are filtered, live profile fields, vocabulary state and context snapshots. PIN records, secure-store key material, and provider credentials are excluded. No backup is written automatically.
- No Room migration is required: the video visibility value is backward-compatible metadata JSON, and all other information uses existing tables.

## Consequences and Risks

- App-private copied-media deletion is path-contained and supported, but current SAF imports remain references by design. The UI explains this instead of claiming the source file was physically deleted.
- The family video catalog is shared; deleting one video clears every profile's progress for that video, while vocabulary/lookup snapshots remain. This distinction is surfaced in the confirmation dialog.
- Backup export is implemented; restore/import is not part of E8-T3.
- `VIDEO` and cache byte totals represent app-managed files, not the size of documents held by the system document provider.

## Verification

- Unit tests cover persistence and data-layer filtering of hidden videos, authorized transactional video-row cleanup, last-child deletion prevention, explicit member-confirmation/PIN sequencing, cache confirmation, and backup action.
- Xiaomi 10S (`52072092`) device verification: manually installed the debug and androidTest APKs, set AppOps `10021` and `10008` to `allow` for both `com.lazyeng.family` and `com.lazyeng.family.test`, launched `com.lazyeng.family/.MainActivity` with `am start -W`, confirmed it held `mCurrentFocus`, then ran `ParentManagementDeviceTest` directly with `am instrument`. Result: **OK (4 tests)**, including member delete/PIN gate, content visibility, storage UI and video-removal confirmation. No `connectedDebugAndroidTest` task was used.
