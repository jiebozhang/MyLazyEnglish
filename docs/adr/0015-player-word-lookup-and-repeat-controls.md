# ADR 0015: Player word lookup and repeat controls

## Status

Accepted for the E4-T3 player scope; dictionary and vocabulary persistence adapters remain pending their owning tasks.

## Context

E4-T3 adds word-level interaction to the E4-T2 subtitle overlay and transcript, a contextual lookup sheet, single-sentence repeat, and A-B repeat. The shared subtitle tokenizer already provides exact UTF-16 source ranges, NFC/case normalization and punctuation separation. Its contract explicitly does not guess morphological lemmas.

The player feature can depend on domain contracts, but the current application has no local dictionary or vocabulary repository implementation. Room is v3 and has no vocabulary tables; E7-T1 owns the next vocabulary schema migration, while E5 owns dictionary data and lookup. Creating a private ad-hoc store here would bypass those ownership boundaries and could report a bookmark as saved when it was not.

## Decisions

- Overlay and transcript words use `SubtitleTokenizer`; only WORD tokens are actionable. Punctuation remains rendered but is not a lookup target. Original surface, normalized lookup form and offsets are kept together.
- `LookupWord` pauses playback before presenting the sheet. Dismissal leaves the engine at its current position. “继续播放” explicitly closes the sheet and resumes.
- `PlayerPlaybackSession` exposes optional suspend lookup, saved-word and save callbacks. Save requests carry the strong `ProfileId`, video, source word, lookup form, dictionary snapshot, subtitle line, full sentence and playback timestamp. The app callback must remain bound to the session's current Profile scope.
- If no dictionary adapter is supplied, the sheet shows “暂无该词释义”. If no vocabulary adapter is supplied, save is disabled and the missing integration is stated; no mock definition or optimistic success is used. A successful adapter response is the only path that marks a word saved and underlines its occurrences.
- The optional pronunciation action is disabled until a real TTS/audio provider exists.
- Single-sentence repeat uses the active subtitle's exact `[startMs, endMs)` range. Re-tapping disables it; seeking outside that cue clears it. A-B repeat requires `B > A`; invalid B leaves the range unset and provides feedback. Starting either repeat mode clears the other.
- Loop boundary handling is driven by player position snapshots; it seeks to A/start without recreating the playback session or changing the saved watch position contract.

## Consequences and follow-up

Player word tapping, pause/resume, source ranges, loop transitions, validation and adapter callbacks are testable now. Production dictionary lookup and durable vocabulary/context writes cannot be claimed complete until E5-T1/E5-T2 and E7-T1/E7-T2 provide their adapters and storage. This ADR does not allocate a Room version or introduce schema.

## Verification

Focused JVM tests cover lookup pause/context capture and injected save success, sentence loop repeat/cancel, invalid and valid A-B ranges and repeat boundary seeking. `PlayerScreenTest` covers word-token tapping and lookup-sheet dismissal without position loss. Device instrumentation evidence is reported with the E4-T3 task result; no connected Gradle task is used on MIUI.
