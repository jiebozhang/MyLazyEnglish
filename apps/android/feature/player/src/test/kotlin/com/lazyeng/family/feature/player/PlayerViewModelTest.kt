package com.lazyeng.family.feature.player

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import com.lazyeng.family.core.common.ContextExplanation
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.testing.TestDispatchers
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.model.ProfileId
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @Test fun offlineDefinitionIsAvailableWhileAiLoadsThenSuccessRenders() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val cue = PlayerSubtitleCue(SubtitleLineId("ai-line"), 1, 0L, 5_000L, "She is running.")
        val result = CompletableDeferred<ContextExplanation?>()
        var requestedLemma = ""
        val entry = com.lazyeng.family.core.model.DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), null, null)
        val session = testSession(subtitles = listOf(cue), lookupWord = { _, _, _, _, _, _ -> PlayerDictionaryLookup(entry) },
            explainContext = { request, _, _ -> requestedLemma = request.lemma; result.await() })
        val store = ViewModelStore()
        try {
            val model = createModel(tests, FakePlayerEngine(), session)
            store.put("player", model)
            runCurrent()
            model.onEvent(PlayerUiEvent.LookupWord("running", "running", 7, 14, cue))
            runCurrent()
            assertEquals("/rʌn/", model.state.value.lookupEntry?.phonetic)
            assertEquals(listOf("跑"), model.state.value.lookupEntry?.senses)
            assertEquals(AiExplanationPhase.LOADING, model.state.value.aiExplanation.phase)
            assertEquals("run", requestedLemma)
            result.complete(ContextExplanation("**正在跑**", "句中表示跑\n#", "She runs.", "她在跑。", "动词", .9f, "safe"))
            runCurrent()
            assertEquals(AiExplanationPhase.SUCCESS, model.state.value.aiExplanation.phase)
            assertEquals("正在跑", model.state.value.aiExplanation.explanation?.meaningZh)
            assertEquals("句中表示跑", model.state.value.aiExplanation.explanation?.whyHereZh)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun closingOrChangingWordCancelsPreviousAiRequestAndFailureCanRetry() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val cue = PlayerSubtitleCue(SubtitleLineId("ai-cancel-line"), 1, 0L, 5_000L, "She is running.")
        var calls = 0
        var wasCancelled = false
        val session = testSession(subtitles = listOf(cue), lookupWord = { _, lemma, _, _, _, _ ->
            PlayerDictionaryLookup(com.lazyeng.family.core.model.DictionaryEntry(lemma, "verb", null, listOf("离线义"), null, null), lemma)
        }, explainContext = { _, _, _ ->
            calls++
            if (calls == 1) {
                try { awaitCancellation() } finally { wasCancelled = true }
            } else if (calls == 2) null else ContextExplanation("跑起来", "句中表示跑", null, null, "动词", .9f, "safe")
        })
        val store = ViewModelStore()
        try {
            val model = createModel(tests, FakePlayerEngine(), session)
            store.put("player", model)
            runCurrent()
            model.onEvent(PlayerUiEvent.LookupWord("running", "run", 7, 14, cue))
            runCurrent()
            assertEquals(AiExplanationPhase.LOADING, model.state.value.aiExplanation.phase)
            model.onEvent(PlayerUiEvent.CloseLookup)
            runCurrent()
            assertTrue(wasCancelled)
            assertEquals(null, model.state.value.selectedWord)

            model.onEvent(PlayerUiEvent.LookupWord("running", "run", 7, 14, cue))
            runCurrent()
            model.onEvent(PlayerUiEvent.RetryAiExplanation)
            runCurrent()
            assertEquals(AiExplanationPhase.SUCCESS, model.state.value.aiExplanation.phase)
            assertEquals(3, calls)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun dictionaryLookupReceivesSentenceOffsetsAndPublishesMatchedLemma() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val cue = PlayerSubtitleCue(SubtitleLineId("lookup-line"), 1, 0L, 5_000L, "She is running.")
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val engine = FakePlayerEngine()
        val store = ViewModelStore()
        try {
            val session = testSession(subtitles = listOf(cue), lookupWord = { _, _, _, sentence, start, end ->
                calls += Triple(sentence, start, end)
                PlayerDictionaryLookup(
                    entry = com.lazyeng.family.core.model.DictionaryEntry(
                        lemma = "run", partOfSpeech = "verb", phonetic = "/rʌn/",
                        senses = listOf("跑"), cefr = null, frequencySource = null,
                    ),
                )
            })
            val model = createModel(tests, engine, session)
            store.put("player", model)
            runCurrent()
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 1_200L, 5_000L, true)
            runCurrent()
            model.onEvent(PlayerUiEvent.LookupWord("running", "running", 7, 14, cue))
            runCurrent()
            assertEquals(listOf(Triple("She is running.", 7, 14)), calls)
            assertEquals("run", model.state.value.lookupLemma)
            assertEquals("run", model.state.value.selectedWord?.lemma)
            assertEquals("/rʌn/", model.state.value.lookupEntry?.phonetic)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun wordLookupPausesCapturesExactWordContextAndSavesCurrentPosition() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val cue = PlayerSubtitleCue(SubtitleLineId("line-a"), 1, 1_000L, 4_000L, "Well, don't go!", null)
        val saved = mutableListOf<PlayerVocabularyCapture>()
        val engine = FakePlayerEngine()
        val session = testSession(
            subtitles = listOf(cue),
            saveWord = { capture -> saved += capture; true },
        )
        val store = ViewModelStore()
        try {
            val model = createModel(tests, engine, session)
            store.put("player", model)
            runCurrent()
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 1_500L, 60_000L, true)
            runCurrent()
            model.onEvent(PlayerUiEvent.LookupWord("don't", "don't", 6, 11, cue))
            runCurrent()
            assertFalse(engine.state.value.isPlaying)
            assertEquals("don't", model.state.value.selectedWord?.surface)
            assertEquals(1_500L, model.state.value.selectedWord?.positionMs)
            model.onEvent(PlayerUiEvent.AddLookupToVocabulary)
            runCurrent()
            assertEquals(listOf(PlayerVocabularyCapture(
                ProfileId("fixture-profile"), "fixture-video", "don't", "don't", null,
                cue.id, cue.englishText, 1_500L,
            )), saved)
            assertTrue(model.state.value.lookupSaved)
            model.onEvent(PlayerUiEvent.CloseLookup)
            assertEquals(1_500L, model.positionMs.value)
            assertFalse(engine.state.value.isPlaying)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun sentenceLoopRepeatsCueAndSecondToggleCancels() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val cue = PlayerSubtitleCue(SubtitleLineId("loop-line"), 1, 1_000L, 2_000L, "Repeat me.")
        val engine = FakePlayerEngine()
        val store = ViewModelStore()
        try {
            val model = createModel(tests, engine, testSession(subtitles = listOf(cue)))
            store.put("player", model)
            runCurrent()
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 1_500L, 60_000L, true)
            runCurrent()
            model.onEvent(PlayerUiEvent.ToggleSentenceLoop)
            assertEquals(cue, model.state.value.sentenceLoop)
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 2_000L, 60_000L, true)
            runCurrent()
            assertEquals(1_000L, engine.state.value.positionMs)
            model.onEvent(PlayerUiEvent.ToggleSentenceLoop)
            assertEquals(null, model.state.value.sentenceLoop)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun abLoopRejectsInvalidEndAndSeeksToStartAtBoundary() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val engine = FakePlayerEngine()
        val store = ViewModelStore()
        try {
            val model = createModel(tests, engine, testSession())
            store.put("player", model)
            runCurrent()
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 15_000L, 60_000L, true)
            runCurrent()
            model.onEvent(PlayerUiEvent.SetLoopA)
            model.onEvent(PlayerUiEvent.SetLoopB)
            assertEquals(null, model.state.value.loopEndMs)
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 5_000L, 60_000L, true)
            runCurrent()
            model.onEvent(PlayerUiEvent.SetLoopA)
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 15_001L, 60_000L, true)
            runCurrent()
            model.onEvent(PlayerUiEvent.SetLoopB)
            assertEquals(15_001L, model.state.value.loopEndMs)
            engine.state.value = PlayerEngineState(PlayerPhase.READY, 15_002L, 60_000L, true)
            runCurrent()
            assertEquals(5_000L, engine.state.value.positionMs)
            model.onEvent(PlayerUiEvent.ClearAbLoop)
            assertEquals(null, model.state.value.loopStartMs)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun restoresPositionPersistsEveryTenSecondsAndPausesOnLifecycleStop() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val dispatchers = object : DispatcherProvider {
            override val io = tests.dispatcher
            override val computation = tests.dispatcher
            override val main = tests.dispatcher
        }
        val saved = mutableListOf<Long>()
        val engine = FakePlayerEngine()
        val session = PlayerPlaybackSession(
            profileId = ProfileId("fixture-profile"),
            videoId = "fixture-video",
            title = "Fixture",
            uri = "content://fixture/video",
            durationMs = 60_000L,
            resumePositionMs = 12_000L,
            scopeValid = { true },
            saveProgress = { position, _ -> saved += position },
        )
        val store = ViewModelStore()
        try {
            val model = PlayerViewModel(
                loadSession = { PlayerSessionResult.Ready(session) },
                engineFactory = { engine },
                clock = Clock { Instant.EPOCH.plusMillis(testScheduler.currentTime) },
                dispatchers = dispatchers,
            )
            store.put("player", model)
            runCurrent()
            assertEquals(12_000L, engine.preparedAt)
            assertEquals(12_000L, model.positionMs.value)

            engine.state.value = PlayerEngineState(PlayerPhase.READY, 30_000L, 60_000L, true)
            runCurrent()
            advanceTimeBy(PlayerViewModel.PROGRESS_SAVE_INTERVAL_MS)
            runCurrent()
            assertTrue(saved.contains(30_000L))

            model.onEvent(PlayerUiEvent.LifecycleStopped)
            runCurrent()
            assertFalse(engine.state.value.isPlaying)
            assertTrue(saved.last() == 30_000L)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun createModel(tests: TestDispatchers, engine: FakePlayerEngine, session: PlayerPlaybackSession) =
        PlayerViewModel(
            loadSession = { PlayerSessionResult.Ready(session) }, engineFactory = { engine },
            clock = Clock { Instant.EPOCH },
            dispatchers = object : DispatcherProvider {
                override val io = tests.dispatcher
                override val computation = tests.dispatcher
                override val main = tests.dispatcher
            },
        )

    private fun testSession(
        subtitles: List<PlayerSubtitleCue> = emptyList(),
        saveWord: (suspend (PlayerVocabularyCapture) -> Boolean)? = null,
        lookupWord: (suspend (surface: String, lemma: String, lineId: SubtitleLineId, sentence: String, start: Int, end: Int) -> PlayerDictionaryLookup)? = null,
        explainContext: (suspend (com.lazyeng.family.core.common.ContextExplanationRequest, String?, Boolean) -> ContextExplanation?)? = null,
    ) = PlayerPlaybackSession(
        profileId = ProfileId("fixture-profile"),
        videoId = "fixture-video", title = "Fixture", uri = "content://fixture/video",
        durationMs = 60_000L, resumePositionMs = 0L, subtitles = subtitles,
        saveWord = saveWord, lookupWord = lookupWord, explainContext = explainContext,
        learnerLevel = "Power Up Starter-2", scopeValid = { true }, saveProgress = { _, _ -> },
    )

    @Test fun unavailableChineseModeIsRejectedAndEnglishModeIsStored() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        val store = ViewModelStore()
        try {
            val model = PlayerViewModel(
                loadSession = {
                    PlayerSessionResult.Ready(PlayerPlaybackSession(
                        profileId = ProfileId("fixture-profile"),
                        videoId = "fixture-video", title = "Fixture", uri = "content://fixture/video",
                        durationMs = 60_000L, resumePositionMs = 0L, scopeValid = { true }, saveProgress = { _, _ -> },
                    ))
                },
                engineFactory = { FakePlayerEngine() },
                clock = Clock { Instant.EPOCH },
                dispatchers = object : DispatcherProvider {
                    override val io = tests.dispatcher
                    override val computation = tests.dispatcher
                    override val main = tests.dispatcher
                },
            )
            store.put("player", model)
            runCurrent()
            model.onEvent(PlayerUiEvent.SetSubtitleMode(SubtitleDisplayMode.CHINESE_ONLY))
            assertEquals(SubtitleDisplayMode.BILINGUAL, model.state.value.subtitleMode)
            model.onEvent(PlayerUiEvent.SetSubtitleMode(SubtitleDisplayMode.ENGLISH_ONLY))
            assertEquals(SubtitleDisplayMode.ENGLISH_ONLY, model.state.value.subtitleMode)
            model.onEvent(PlayerUiEvent.SetSubtitleMode(SubtitleDisplayMode.HIDDEN))
            assertEquals(SubtitleDisplayMode.HIDDEN, model.state.value.subtitleMode)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun missingSourceShowsRecoverableErrorWithoutCreatingEngine() = runTest {
        val tests = TestDispatchers(testScheduler)
        Dispatchers.setMain(tests.dispatcher)
        var created = false
        val store = ViewModelStore()
        try {
            val model = PlayerViewModel(
                loadSession = { PlayerSessionResult.Failed("视频文件无法打开，请检查文件是否仍可访问。") },
                engineFactory = { created = true; FakePlayerEngine() },
                clock = Clock { Instant.EPOCH },
                dispatchers = object : DispatcherProvider {
                    override val io = tests.dispatcher
                    override val computation = tests.dispatcher
                    override val main = tests.dispatcher
                },
            )
            store.put("player", model)
            runCurrent()
            assertEquals(PlayerPhase.ERROR, model.state.value.phase)
            assertTrue(model.state.value.message!!.contains("文件无法打开"))
            assertFalse(created)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class FakePlayerEngine : PlayerEngine {
        override val player: Player? = null
        override val state = MutableStateFlow(PlayerEngineState())
        var preparedAt = -1L
        override fun prepare(uri: String, startPositionMs: Long) {
            preparedAt = startPositionMs
            state.value = PlayerEngineState(PlayerPhase.READY, startPositionMs, 60_000L, true)
        }
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun setVolume(volume: Float) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun release() = Unit
    }
}
