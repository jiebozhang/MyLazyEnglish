# ADR 0018: Contextual Dictionary Sheet and System TTS

## Status

Accepted for E5-T3.

## Decisions

- The player host passes the selected subtitle's complete sentence and UTF-16, end-exclusive character offsets to `LocalDictionaryLookupUseCase`. The player feature receives only a small result contract containing the domain `DictionaryEntry`, matched lemma, or an availability message; it does not depend on the dictionary feature or SQLite implementation.
- The context sheet presents the original surface form, a distinct matched lemma, available phonetic/POS/senses, and CEFR only when the data actually provides it. The exact selected character range is highlighted in the source sentence. A miss keeps the original word and context visible.
- Dictionary data stays out of the APK and source tree per ADR 0016. The application currently looks for the controlled package at the app-private relative location `files/dictionary/ecdictionary.sqlite`; absent/unreadable packages are reported as unavailable, distinct from a legitimate word miss. A package provisioning/install workflow is not part of E5-T3.
- Pronunciation uses Android `TextToSpeech`, allowing American or British English locale selection. The player route owns the adapter only while a word sheet is open; closing the sheet or pausing/stopping the host stops speech, and disposal shuts down the engine. Missing service/voice data is surfaced inline without affecting lookup or capture.
- Add-to-vocabulary remains available only when a real `PlayerVocabularyCapture` callback is provided by the host. E5-T3 does not fake persistence ahead of E7.

## Validation and Limits

- JVM tests verify sentence/range propagation and matched-lemma state mapping. Compose instrumentation verifies sheet content, pronunciation callback/accent, and dismissal without changing the supplied playback position. The pronunciation test uses a fake; device-specific TTS voice availability remains dependent on installed Android speech services.
- No production ECDICT package is included in the repository or APK. Until package provisioning is implemented, the application correctly shows a dictionary-package availability message on an unprovisioned install; real corpus hits require the separately controlled package described by ADR 0016.
- The app-private package location is a local integration convention, not an update mechanism. Package integrity/version installation and user-facing package setup belong to a later delivery task.
