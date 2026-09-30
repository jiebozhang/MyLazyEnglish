package com.lazyeng.family

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.ContextExplanation
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VocabularyCapture
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.feature.player.*
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PhysicalRotationHostActivity>()

    @Test fun controlsExposePlaybackPositionAndDispatchPause() {
        val events = mutableListOf<PlayerUiEvent>()
        val position = MutableStateFlow(5_000L)
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture video", durationMs = 60_000L, isPlaying = true),
                    player = null,
                    positionMs = position,
                    subtitles = MutableStateFlow(emptyList()),
                    activeSubtitleIndex = MutableStateFlow(null),
                    onEvent = events::add,
                )
            }
        }
        compose.onNodeWithText("Fixture video").assertIsDisplayed()
        compose.onNodeWithTag("player-time").assertTextContains("0:05 / 1:00")
        compose.onNodeWithText("暂停").performClick()
        compose.onNodeWithTag("subtitle-mode-menu").performClick()
        compose.onNodeWithText("仅中文（暂无翻译）").assertIsNotEnabled()
        compose.onNodeWithText("仅英文").performClick()
        assertEquals(
            listOf(PlayerUiEvent.TogglePlayback, PlayerUiEvent.SetSubtitleMode(SubtitleDisplayMode.ENGLISH_ONLY)),
            events,
        )
    }

    @Test fun responsiveLayoutClassifiesPhonePortraitLandscapeAndExpandedWindows() {
        assertEquals(PlayerLayoutMode.PORTRAIT, playerLayoutMode(412f, 915f))
        assertEquals(PlayerLayoutMode.COMPACT_LANDSCAPE, playerLayoutMode(915f, 412f))
        assertEquals(PlayerLayoutMode.EXPANDED, playerLayoutMode(840f, 700f))
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 60_000L),
                    player = null,
                    positionMs = MutableStateFlow(0L),
                    subtitles = MutableStateFlow(emptyList()),
                    activeSubtitleIndex = MutableStateFlow(null),
                    onEvent = {},
                )
            }
        }
        compose.onNodeWithTag("player-layout-portrait").assertIsDisplayed()
        savePlayerScreenshot("player-portrait")
    }

    @Test fun compactLandscapeControlsAutoHideAndReturnOnScreenTap() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            compose.waitUntil(5_000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.mainClock.autoAdvance = false
            compose.setContent {
                LazyEngTheme {
                    PlayerScreen(
                        state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 60_000L, isPlaying = true),
                        player = null,
                        positionMs = MutableStateFlow(0L),
                        subtitles = MutableStateFlow(listOf(cue("landscape", 1, 0L, 5_000L, "横屏翻译"))),
                        activeSubtitleIndex = MutableStateFlow(0),
                        onEvent = {},
                    )
                }
            }
            compose.mainClock.advanceTimeBy(100L)
            compose.onNodeWithTag("player-layout-phone-landscape").assertIsDisplayed()
            compose.onNodeWithTag("player-controls").assertIsDisplayed()
            savePlayerScreenshot("player-compact-landscape-controls-visible")
            compose.mainClock.advanceTimeBy(5_100L)
            compose.mainClock.advanceTimeBy(300L)
            compose.onNodeWithTag("player-controls").assertDoesNotExist()
            compose.onNodeWithTag("player-subtitle-overlay").assertExists()
            compose.onNodeWithTag("player-screen").performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(300L)
            compose.onNodeWithTag("player-controls").assertIsDisplayed()
            compose.onNodeWithTag("player-subtitle-overlay").assertDoesNotExist()
            savePlayerScreenshot("player-compact-landscape-controls-restored")
        } finally {
            compose.mainClock.autoAdvance = true
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    @Test fun portraitControlsAutoHideWhilePlayingAndReturnOnTap() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                LazyEngTheme {
                    PlayerScreen(
                        state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 60_000L, isPlaying = true),
                        player = null,
                        positionMs = MutableStateFlow(0L),
                        subtitles = MutableStateFlow(listOf(cue("portrait", 1, 0L, 5_000L, "翻译"))),
                        activeSubtitleIndex = MutableStateFlow(0),
                        onEvent = {},
                    )
                }
            }
            compose.mainClock.advanceTimeBy(100L)
            compose.onNodeWithTag("player-layout-portrait").assertExists()
            compose.onNodeWithTag("player-controls").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(5_100L)
            compose.mainClock.advanceTimeBy(300L)
            compose.onNodeWithTag("player-controls").assertDoesNotExist()
            compose.onNodeWithTag("player-screen").performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(300L)
            compose.onNodeWithTag("player-controls").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun playerActionsExposeLocalizedSemanticsAndAtLeast48DpTargets() {
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(
                        phase = PlayerPhase.READY, title = "Fixture", durationMs = 60_000L,
                        sentenceLoop = cue("loop-cue", 1, 0L, 2_000L), loopStartMs = 1_000L, loopEndMs = 2_000L,
                    ),
                    player = null,
                    positionMs = MutableStateFlow(1_000L),
                    subtitles = MutableStateFlow(emptyList()),
                    activeSubtitleIndex = MutableStateFlow(null),
                    onEvent = {},
                )
            }
        }
        val descriptions = mapOf(
            "player-back" to "返回视频详情",
            "player-fullscreen" to "进入全屏",
            "player-play-pause" to "开始播放",
            "player-speed" to "播放速度",
            "player-volume-menu" to "音量设置",
            "subtitle-mode-menu" to "字幕显示模式",
            "player-sentence-loop" to "单句循环",
            "player-loop-a" to "设置 AB 循环起点 A",
            "player-loop-b" to "设置 AB 循环终点 B",
            "player-loop-clear" to "清除 AB 循环",
        )
        descriptions.forEach { (tag, description) ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            node.assertContentDescriptionEquals(description)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val density = compose.density.density
            assertTrue("$tag width was ${bounds.width / density}dp", bounds.width / density >= 48f)
            assertTrue("$tag height was ${bounds.height / density}dp", bounds.height / density >= 48f)
        }
        compose.onNodeWithTag("player-seek", useUnmergedTree = true)
            .assertContentDescriptionEquals("播放进度")
    }

    @Test fun longSubtitleRemainsAvailableAtTwoTimesFontScale() {
        val english = "A long subtitle should wrap across lines instead of being clipped when the system font is enlarged."
        val cues = MutableStateFlow(listOf(cue("large-text", 1, 0L, 10_000L, "系统字体放大后，这段较长的中文翻译仍应完整显示并允许自然折行。", english)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(compose.density.density, 2f)) {
                LazyEngTheme {
                    PlayerScreen(
                        state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 10_000L, hasChineseSubtitles = true),
                        player = null,
                        positionMs = MutableStateFlow(0L),
                        subtitles = cues,
                        activeSubtitleIndex = MutableStateFlow(0),
                        onEvent = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("subtitle-line-1").assertIsDisplayed()
        compose.onNodeWithTag("line-1-word-0").assertIsDisplayed()
        compose.onNodeWithTag("player-play-pause").assertIsDisplayed()
    }

    @Test fun transcriptHighlightsCurrentCueAndClickSeeksToItsStart() {
        val events = mutableListOf<PlayerUiEvent>()
        val cues = MutableStateFlow(listOf(cue("line-1", 1, 0L, 1_000L), cue("line-2", 2, 1_200L, 2_300L, "第二句中文")))
        val active = MutableStateFlow<Int?>(1)
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture video", durationMs = 10_000L, hasChineseSubtitles = false),
                    player = null,
                    positionMs = MutableStateFlow(1_300L),
                    subtitles = cues,
                    activeSubtitleIndex = active,
                    onEvent = events::add,
                )
            }
        }
        compose.onNodeWithTag("subtitle-line-2").assertIsDisplayed()
        compose.onNodeWithTag("line-2-word-0").assertIsDisplayed()
        compose.onAllNodesWithText("第二句中文").assertCountEquals(1)
        compose.onNodeWithTag("subtitle-translation-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("player-subtitle-overlay").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-timestamp-2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("subtitle-repeat-2", useUnmergedTree = true).assertContentDescriptionEquals("复读当前句")
        compose.onNodeWithTag("subtitle-line-2").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(20f, 20f))
        }
        assertEquals(listOf(PlayerUiEvent.Seek(1_200L)), events)
        savePlayerScreenshot("player-transcript-ui-portrait")
    }

    @Test fun subtitleWordTapOpensLookupAndClosingKeepsPosition() {
        val events = mutableListOf<PlayerUiEvent>()
        val position = MutableStateFlow(8_250L)
        val selected = mutableStateOf<PlayerWordSelection?>(null)
        val state = mutableStateOf(PlayerUiState(
            phase = PlayerPhase.READY, title = "Fixture", durationMs = 30_000L, isPlaying = true,
        ))
        val subtitle = cue("word-line", 1, 8_000L, 10_000L).copy(englishText = "Elephant, don't run!")
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = state.value.copy(selectedWord = selected.value, lookupCanSave = selected.value != null),
                    player = null,
                    positionMs = position,
                    subtitles = MutableStateFlow(listOf(subtitle)),
                    activeSubtitleIndex = MutableStateFlow(0),
                    onEvent = { event ->
                        events += event
                        if (event is PlayerUiEvent.LookupWord) {
                            selected.value = PlayerWordSelection(event.surface, event.normalized, event.start, event.end, event.cue, position.value)
                        } else if (event == PlayerUiEvent.CloseLookup) selected.value = null
                    },
                )
            }
        }
        compose.onNodeWithTag("player-subtitle-overlay").assertDoesNotExist()
        compose.onNodeWithTag("line-1-word-0").performClick()
        compose.onNodeWithTag("lookup-word").assertTextContains("Elephant")
        assertEquals(PlayerUiEvent.LookupWord("Elephant", "elephant", 0, 8, subtitle), events.first())
        compose.onNodeWithTag("lookup-empty").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        assertTrue(selected.value == null)
        assertEquals(8_250L, position.value)
    }

    @Test fun contextualDictionaryShowsLemmaAndPronunciationUsesSelectedAccent() {
        val pronunciation = FakePronunciationPlayer()
        val cue = cue("lookup-hit", 1, 2_000L, 4_000L).copy(englishText = "She is running home.")
        val selection = PlayerWordSelection("running", "run", 7, 14, cue, 2_600L)
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(
                        phase = PlayerPhase.READY,
                        title = "Fixture",
                        durationMs = 8_000L,
                        selectedWord = selection,
                        lookupEntry = com.lazyeng.family.core.model.DictionaryEntry(
                            lemma = "run", partOfSpeech = "verb", phonetic = "/rʌn/",
                            senses = listOf("跑", "经营"), cefr = null, frequencySource = null,
                        ),
                        lookupLemma = "run",
                    ),
                    player = null,
                    positionMs = MutableStateFlow(2_600L),
                    subtitles = MutableStateFlow(listOf(cue)),
                    activeSubtitleIndex = MutableStateFlow(0),
                    onEvent = {},
                    pronunciationPlayer = pronunciation,
                )
            }
        }
        compose.onNodeWithTag("lookup-word").assertTextContains("running")
        compose.onNodeWithTag("lookup-lemma").performScrollTo().assertTextContains("原形 run")
        compose.onNodeWithTag("lookup-phonetic").performScrollTo()
        compose.onNodeWithTag("lookup-phonetic").assertTextContains("/rʌn/")
        compose.onNodeWithTag("lookup-sense-0").performScrollTo()
        compose.onNodeWithTag("lookup-sense-0").assertTextContains("跑")
        compose.onNodeWithTag("lookup-context").performScrollTo()
        compose.onNodeWithTag("lookup-context").assertTextContains("She is running home.")
        compose.onNodeWithText("英音").performScrollTo()
        compose.onNodeWithText("英音").performClick()
        compose.onNodeWithTag("lookup-pronounce").performScrollTo()
        compose.onNodeWithTag("lookup-pronounce").performClick()
        assertEquals("running", pronunciation.spokenText)
        assertEquals(PronunciationAccent.BRITISH, pronunciation.spokenAccent)
    }

    @Test fun localDictionaryStaysVisibleDuringAiLoadingAndDismissCancelsAi() {
        val cue = cue("ai-context-line", 1, 0L, 8_000L).copy(englishText = "She is running home.")
        val engine = InstrumentedFakeEngine()
        var calls = 0
        var cancelled = false
        val localEntry = com.lazyeng.family.core.model.DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), null, null)
        val model = PlayerViewModel(
            loadSession = {
                PlayerSessionResult.Ready(PlayerPlaybackSession(
                    profileId = ProfileId("fixture-profile"), videoId = "fixture-video", title = "Fixture",
                    uri = "content://fixture/video", durationMs = 8_000L, resumePositionMs = 1_000L,
                    subtitles = listOf(cue), lookupWord = { _, _, _, _, _, _ -> PlayerDictionaryLookup(localEntry, "run") },
                    explainContext = { _, _, _ ->
                        calls++
                        if (calls == 1) {
                            delay(1_500L)
                            ContextExplanation("正在跑", "這句中的意思是跑", "She runs home.", "她跑回家。", "动词", .95f, "safe")
                        } else {
                            try { awaitCancellation() } finally { cancelled = true }
                        }
                    },
                    learnerLevel = "Power Up Starter-2", scopeValid = { true }, saveProgress = { _, _ -> Unit },
                ))
            },
            engineFactory = { engine }, clock = Clock { Instant.EPOCH },
            dispatchers = (compose.activity.application as LazyEngApplication).dispatchers,
        )
        compose.setContent {
            val state by model.state.collectAsState()
            LazyEngTheme { PlayerScreen(state, model.mediaPlayer, model.positionMs, model.subtitles, model.activeSubtitleIndex, model::onEvent) }
        }
        compose.waitUntil(5_000) { model.state.value.phase == PlayerPhase.READY }
        compose.onNodeWithTag("line-1-word-7").performClick()
        compose.waitUntil(5_000) { model.state.value.lookupEntry != null && model.state.value.aiExplanation.phase == AiExplanationPhase.LOADING }
        compose.onNodeWithTag("lookup-phonetic").performScrollTo().assertTextContains("/rʌn/")
        compose.onNodeWithTag("lookup-sense-0").performScrollTo().assertTextContains("跑")
        compose.onNodeWithTag("ai-explanation-loading").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5_000) { model.state.value.aiExplanation.phase == AiExplanationPhase.SUCCESS }
        compose.onNodeWithTag("ai-explanation-meaning").performScrollTo().assertTextContains("正在跑")

        compose.onNodeWithTag("line-1-word-7").performClick()
        compose.waitUntil(5_000) { calls == 2 && model.state.value.aiExplanation.phase == AiExplanationPhase.LOADING }
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.waitUntil(5_000) { cancelled && model.state.value.selectedWord == null }
        assertEquals(PlayerPhase.READY, model.state.value.phase)
    }

    @Test fun aiFailureShowsRetryableFallbackWithoutHidingOfflineDefinition() {
        val cue = cue("ai-fallback-line", 1, 0L, 8_000L).copy(englishText = "She is running home.")
        val model = PlayerViewModel(
            loadSession = {
                PlayerSessionResult.Ready(PlayerPlaybackSession(
                    profileId = ProfileId("fixture-profile"), videoId = "fixture-video", title = "Fixture",
                    uri = "content://fixture/video", durationMs = 8_000L, resumePositionMs = 1_000L,
                    subtitles = listOf(cue),
                    lookupWord = { _, _, _, _, _, _ ->
                        PlayerDictionaryLookup(
                            com.lazyeng.family.core.model.DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), null, null),
                            "run",
                        )
                    },
                    explainContext = { _, _, _ -> null },
                    learnerLevel = "Power Up Starter-2", scopeValid = { true }, saveProgress = { _, _ -> Unit },
                ))
            },
            engineFactory = { InstrumentedFakeEngine() }, clock = Clock { Instant.EPOCH },
            dispatchers = (compose.activity.application as LazyEngApplication).dispatchers,
        )
        compose.setContent {
            val state by model.state.collectAsState()
            LazyEngTheme { PlayerScreen(state, model.mediaPlayer, model.positionMs, model.subtitles, model.activeSubtitleIndex, model::onEvent) }
        }
        compose.waitUntil(5_000) { model.state.value.phase == PlayerPhase.READY }
        compose.onNodeWithTag("line-1-word-7").performClick()
        compose.waitUntil(5_000) { model.state.value.aiExplanation.phase == AiExplanationPhase.FALLBACK }
        compose.onNodeWithTag("lookup-sense-0").performScrollTo().assertTextContains("跑")
        compose.onNodeWithTag("ai-explanation-fallback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai-explanation-retry").performScrollTo().assertIsDisplayed()
    }

    @Test fun bundledDictionaryResolvesRealEntriesAndPreservesCapturePayload() {
        val application = compose.activity.application as LazyEngApplication
        val installedDictionary = runBlocking { application.ensureDictionaryReady() }
        assertTrue(installedDictionary.isFile)
        val vocabularyDbName = "player-vocabulary-${System.nanoTime()}.db"
        val vocabularyDb = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FamilyDatabase::class.java,
            vocabularyDbName,
        ).build()
        val family = com.lazyeng.family.core.model.FamilyId("fixture-family")
        val profile = ProfileId("fixture-profile")
        runBlocking {
            vocabularyDb.familyRepository().saveFamily(com.lazyeng.family.core.model.Family(family, emptyMap(), Instant.EPOCH))
            vocabularyDb.profileRepository().saveProfile(family, profile, com.lazyeng.family.core.model.Profile(
                profile, family, "Fixture child", null, com.lazyeng.family.core.model.ProfileRole.CHILD,
                "child", "Power Up 2", emptyMap(), Instant.EPOCH, null,
            ))
        }
        val vocabulary = vocabularyDb.vocabularyRepository()
        val subtitle = cue("real-dictionary-line", 1, 0L, 8_000L)
            .copy(englishText = "She is running home.")
        val captures = CopyOnWriteArrayList<PlayerVocabularyCapture>()
        val engine = InstrumentedFakeEngine()
        val dispatchers = application.dispatchers
        val model = PlayerViewModel(
            loadSession = {
                PlayerSessionResult.Ready(PlayerPlaybackSession(
                    profileId = ProfileId("fixture-profile"), videoId = "fixture-video", title = "Fixture",
                    uri = "content://fixture/video", durationMs = 8_000L, resumePositionMs = 2_000L,
                    subtitles = listOf(subtitle),
                    lookupWord = { _, _, _, sentence, start, end ->
                        val result = application.lookupDictionary(sentence, start, end)
                        PlayerDictionaryLookup(result?.entry, result?.matchedLemma)
                    },
                    isWordSaved = { lemma -> vocabulary.isWordSaved(ProfileId("fixture-profile"), lemma).first() },
                    listSavedWords = { vocabulary.listSavedLemmas(ProfileId("fixture-profile")) },
                    saveWord = { playerCapture ->
                        captures += playerCapture
                        vocabulary.saveWord(VocabularyCapture(
                            profileId = playerCapture.profileId,
                            surface = playerCapture.surface,
                            lemma = playerCapture.lemma,
                            phonetic = playerCapture.dictionaryEntry?.phonetic,
                            definition = playerCapture.dictionaryEntry?.senses?.firstOrNull(),
                            videoId = com.lazyeng.family.core.model.VideoId(playerCapture.videoId),
                            lineId = playerCapture.lineId,
                            contextSentence = playerCapture.sentence,
                            timestampMs = playerCapture.timestampMs,
                            createdAt = Instant.EPOCH,
                        )).isSuccess
                    },
                    scopeValid = { true }, saveProgress = { _, _ -> Unit },
                ))
            },
            engineFactory = { engine },
            clock = Clock { Instant.EPOCH },
            dispatchers = dispatchers,
        )
        compose.setContent {
            val state by model.state.collectAsState()
            LazyEngTheme {
                PlayerScreen(state, model.mediaPlayer, model.positionMs, model.subtitles,
                    model.activeSubtitleIndex, model::onEvent)
            }
        }
        compose.waitUntil(10_000) { model.state.value.phase == PlayerPhase.READY }
        compose.onNodeWithTag("line-1-word-7").performClick()
        compose.waitUntil(30_000) { model.state.value.lookupEntry != null }
        compose.onNodeWithTag("lookup-word").assertTextContains("running")
        compose.onNodeWithTag("lookup-lemma").performScrollTo().assertTextContains("原形 run")
        compose.onNodeWithTag("lookup-phonetic").performScrollTo().assertTextContains("rʌn")
        compose.onNodeWithTag("lookup-sense-0").performScrollTo().assertTextContains("n. 跑", substring = true)
        compose.onNodeWithTag("lookup-cefr").assertDoesNotExist()
        compose.onNodeWithTag("lookup-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { captures.isNotEmpty() && model.state.value.lookupSaved }
        compose.onNodeWithTag("lookup-save").assertTextContains("已加入生词本").assertIsNotEnabled()
        val capture = captures.single()
        assertEquals("running", capture.surface)
        assertEquals("run", capture.lemma)
        assertEquals("run", capture.dictionaryEntry?.lemma)
        assertTrue(capture.dictionaryEntry?.senses?.any { it.contains("跑") } == true)
        assertTrue(!capture.dictionaryEntry?.phonetic.isNullOrBlank())
        assertEquals("She is running home.", capture.sentence)
        assertEquals(2_000L, capture.timestampMs)
        assertEquals("fixture-video", capture.videoId)
        assertEquals(ProfileId("fixture-profile"), capture.profileId)
        val savedItem = runBlocking { vocabulary.getItem(ProfileId("fixture-profile"), "run") }
        assertNotNull(savedItem)
        assertEquals(com.lazyeng.family.core.model.VocabularyStatus.LEARNING, savedItem?.status)
        assertNull(savedItem?.nextReviewAt)
        val savedContext = runBlocking { vocabulary.listContexts(ProfileId("fixture-profile"), "run").single() }
        assertEquals("running", savedContext.surface)
        assertEquals("run", savedContext.vocabularyItem.lemma)
        assertEquals(capture.videoId, savedContext.videoId?.value)
        assertEquals(capture.lineId, savedContext.lineId)
        assertEquals(capture.timestampMs, savedContext.timestampMs)
        assertEquals(capture.sentence, savedContext.contextSentence)
        assertEquals(capture.dictionaryEntry?.senses?.firstOrNull(), savedContext.definition)
        vocabularyDb.close()
        val reopenedVocabularyDb = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FamilyDatabase::class.java,
            vocabularyDbName,
        ).build()
        val reopenedVocabulary = reopenedVocabularyDb.vocabularyRepository()
        assertTrue(runBlocking { reopenedVocabulary.isWordSaved(ProfileId("fixture-profile"), "run").first() })
        assertEquals(1, runBlocking { reopenedVocabulary.listContexts(ProfileId("fixture-profile"), "run") }.size)
        reopenedVocabularyDb.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(vocabularyDbName)

        val jumped = runBlocking { application.lookupDictionary("They jumped home.", 5, 11) }
        assertEquals("jump", jumped?.matchedLemma)
        assertTrue(jumped?.entry?.senses?.any { it.contains("跳") } == true)
        val phraseText = "We give up."
        val phraseStart = phraseText.indexOf("give up")
        val phrase = runBlocking { application.lookupDictionary(phraseText, phraseStart, phraseStart + 7) }
        assertEquals("give up", phrase?.matchedLemma)
        assertTrue(phrase?.entry?.senses?.isNotEmpty() == true)
    }

    @Test fun systemTtsReportsAvailabilityAndHandlesSpeechRequest() {
        lateinit var speech: SystemPronunciationPlayer
        compose.runOnUiThread { speech = SystemPronunciationPlayer(compose.activity) }
        try {
            compose.waitUntil(5_000) { speech.state.value != PronunciationState.INITIALIZING }
            if (speech.state.value == PronunciationState.READY) {
                compose.runOnUiThread { speech.speak("hello", PronunciationAccent.AMERICAN) }
                compose.waitUntil(5_000) {
                    speech.state.value in setOf(
                        PronunciationState.SPEAKING,
                        PronunciationState.READY,
                        PronunciationState.UNAVAILABLE,
                        PronunciationState.ERROR,
                    )
                }
            }
            assertTrue(speech.state.value != PronunciationState.INITIALIZING)
        } finally {
            compose.runOnUiThread { speech.release() }
        }
    }

    private class FakePronunciationPlayer : PronunciationPlayer {
        private val mutable = MutableStateFlow(PronunciationState.READY)
        override val state = mutable
        var spokenText: String? = null
        var spokenAccent: PronunciationAccent? = null
        override fun speak(text: String, accent: PronunciationAccent) {
            spokenText = text
            spokenAccent = accent
            mutable.value = PronunciationState.SPEAKING
        }
        override fun stop() { mutable.value = PronunciationState.READY }
        override fun release() { mutable.value = PronunciationState.UNAVAILABLE }
    }

    @Test fun subtitleDisplayModeUpdatesBothOverlayAndTranscript() {
        val mode = mutableStateOf(SubtitleDisplayMode.BILINGUAL)
        val cues = MutableStateFlow(listOf(cue("translated", 1, 0L, 2_000L, "真实测试翻译")))
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 2_000L,
                        subtitleMode = mode.value, hasChineseSubtitles = true),
                    player = null,
                    positionMs = MutableStateFlow(0L),
                    subtitles = cues,
                    activeSubtitleIndex = MutableStateFlow(0),
                    onEvent = { event -> if (event is PlayerUiEvent.SetSubtitleMode) mode.value = event.mode },
                )
            }
        }
        compose.onAllNodesWithText("真实测试翻译").assertCountEquals(1)
        compose.onNodeWithTag("player-subtitle-overlay").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-mode-menu").performClick()
        compose.onNodeWithText("仅英文").performClick()
        compose.onNodeWithText("真实测试翻译").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-mode-menu").performClick()
        compose.onNodeWithText("仅中文").performClick()
        compose.onAllNodesWithText("真实测试翻译").assertCountEquals(1)
        compose.onNodeWithTag("line-1-word-0").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-mode-menu").performClick()
        compose.onNodeWithText("关闭字幕").performClick()
        compose.onNodeWithTag("subtitle-hidden").assertIsDisplayed()
    }

    @Test fun frequentPositionAndCueUpdatesDoNotRecomposePlayerPage() {
        val pageCompositions = java.util.concurrent.atomic.AtomicInteger()
        val position = MutableStateFlow(0L)
        val active = MutableStateFlow<Int?>(0)
        val cues = MutableStateFlow(listOf(cue("line-1", 1, 0L, 1_000L), cue("line-2", 2, 1_000L, 2_000L)))
        compose.setContent {
            SideEffect { pageCompositions.incrementAndGet() }
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 2_000L),
                    player = null,
                    positionMs = position,
                    subtitles = cues,
                    activeSubtitleIndex = active,
                    onEvent = {},
                )
            }
        }
        compose.waitForIdle()
        val initialPageCompositions = pageCompositions.get()
        repeat(8) { tick ->
            compose.runOnUiThread {
                position.value = (tick + 1) * 125L
                if (tick == 4) active.value = 1
            }
            compose.waitForIdle()
        }
        assertEquals(initialPageCompositions, pageCompositions.get())
    }

    @Test fun transcriptAutomaticallyScrollsToTheCurrentCue() {
        val cues = MutableStateFlow((0..30).map { index ->
            cue("scroll-$index", index + 1, index * 1_000L, (index + 1) * 1_000L)
        })
        val active = MutableStateFlow<Int?>(0)
        compose.setContent {
            LazyEngTheme {
                PlayerScreen(
                    state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 31_000L),
                    player = null,
                    positionMs = MutableStateFlow(0L),
                    subtitles = cues,
                    activeSubtitleIndex = active,
                    onEvent = {},
                )
            }
        }
        compose.onNodeWithTag("subtitle-line-1").assertIsDisplayed()
        compose.runOnUiThread { active.value = 25 }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("subtitle-line-26").assertIsDisplayed() }.isSuccess
        }
    }

    @Test fun media3PlaysSeeksAndPausesLocalSyntheticVideo() {
        val file = File(compose.activity.cacheDir, "synthetic-player-fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-player-fixture.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        lateinit var engine: Media3PlayerEngine
        compose.runOnUiThread { engine = Media3PlayerEngine(compose.activity) }
        try {
            compose.setContent {
                LazyEngTheme {
                    PlayerScreen(
                        state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 10_000L),
                        player = engine.player,
                        positionMs = MutableStateFlow(0L),
                        subtitles = MutableStateFlow(emptyList()),
                        activeSubtitleIndex = MutableStateFlow(null),
                        onEvent = {},
                    )
                }
            }
            compose.onNodeWithTag("player-surface").assertExists()
            compose.runOnUiThread { engine.prepare(Uri.fromFile(file).toString(), 0L) }
            compose.waitUntil(10_000) { engine.state.value.isPlaying }
            assertTrue(engine.state.value.durationMs!! >= 9_000L)
            compose.runOnUiThread {
                engine.seekTo(3_000L)
                engine.pause()
            }
            compose.waitUntil(5_000) { !engine.state.value.isPlaying && engine.state.value.positionMs >= 2_500L }
        } finally {
            compose.runOnUiThread { engine.release() }
            file.delete()
        }
    }

    @Test fun routePausesAndSavesWhenActivityLeavesForeground() {
        val engine = InstrumentedFakeEngine()
        val savedPositions = CopyOnWriteArrayList<Long>()
        val dispatchers = object : DispatcherProvider {
            override val io = Dispatchers.IO
            override val computation = Dispatchers.Default
            override val main = Dispatchers.Main
        }
        val model = PlayerViewModel(
            loadSession = {
                PlayerSessionResult.Ready(PlayerPlaybackSession(
                    profileId = ProfileId("fixture-profile"),
                    videoId = "fixture-video", title = "Fixture", uri = "content://fixture/video",
                    durationMs = 30_000L, resumePositionMs = 7_000L, scopeValid = { true },
                    saveProgress = { position, _ -> savedPositions += position },
                ))
            },
            engineFactory = { engine },
            clock = Clock { Instant.EPOCH },
            dispatchers = dispatchers,
        )
        val owner = FixtureLifecycleOwner()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LazyEngTheme { PlayerRoute(model) {} }
            }
        }
        compose.waitUntil(5_000) { engine.preparedAt == 7_000L }
        compose.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.waitUntil(5_000) { !engine.state.value.isPlaying }
        compose.waitUntil(10_000) { savedPositions.contains(7_000L) }
    }

    private class FixtureLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun savePlayerScreenshot(name: String) {
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "e4-t4")
        check(folder.exists() || folder.mkdirs())
        File(folder, "$name.png").outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun cue(
        id: String,
        sequence: Int,
        startMs: Long,
        endMs: Long,
        chinese: String? = null,
        english: String? = null,
    ) =
        PlayerSubtitleCue(SubtitleLineId(id), sequence, startMs, endMs,
            english ?: "${if (sequence == 1) "First" else "Second"} synthetic line", chinese)

    private class InstrumentedFakeEngine : PlayerEngine {
        override val player: Player? = null
        override val state = MutableStateFlow(PlayerEngineState())
        var preparedAt = -1L
        override fun prepare(uri: String, startPositionMs: Long) {
            preparedAt = startPositionMs
            state.value = PlayerEngineState(PlayerPhase.READY, startPositionMs, 30_000L, true)
        }
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun setVolume(volume: Float) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun release() = Unit
    }
}
