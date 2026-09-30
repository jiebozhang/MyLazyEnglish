# Child video visibility policy correction

## Household decision

The household owner requested that imported videos be visible to children by
default, without maintaining age classification. This supersedes the original
PRD rule that automatically hid unknown/adult age labels in this household app.

## Implementation

- Child catalog lists, direct reads and recommendations check only the existing
  `hiddenFromChildren` flag. Legacy age labels no longer suppress imported videos.
- Explicit parent hiding remains supported and requires a live parent session.
- No database migration or rewriting of existing household records is needed.
- Library entries without published subtitles remain visible, but playback
  readiness checks are unchanged. Visibility does not fabricate READY status.
- Regression tests cover all legacy age labels, READY recommendations, and
  hiding/showing a video whose age label is unknown.

## Verification

- `testDebugUnitTest lintDebug :app:assembleRelease`: passed in 3m 27s
  using JDK 17 and the local dependency cache.
- VideoRepositoryTest: 12 tests, zero failures/errors. LibraryViewModelTest:
  one test, zero failures/errors. The latter retains the PIN-protected view of
  explicitly hidden videos while allowing unknown-age videos by default.
- No device installation or household-data changes were performed in this run.
- Temporary cache-origin repository entries were removed before commit.
