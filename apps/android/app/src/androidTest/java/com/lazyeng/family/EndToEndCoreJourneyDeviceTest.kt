package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.ImportDocuments
import com.lazyeng.family.core.common.MediaReadinessChecker
import com.lazyeng.family.core.common.ProbedVideo
import com.lazyeng.family.core.common.SelectedSubtitle
import com.lazyeng.family.core.common.SubtitleCandidate
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.core.security.PinCheck
import com.lazyeng.family.core.security.PinDerivation
import com.lazyeng.family.core.security.PinRecord
import com.lazyeng.family.core.security.PinRecordStore
import com.lazyeng.family.core.security.PinService
import com.lazyeng.family.feature.dictionary.LookupEventCoordinator
import com.lazyeng.family.feature.dictionary.LookupRequest
import com.lazyeng.family.feature.importmedia.DuplicateChoice
import com.lazyeng.family.feature.importmedia.ImportPolicy
import com.lazyeng.family.feature.importmedia.ImportPhase
import com.lazyeng.family.feature.importmedia.VideoImportCoordinator
import com.lazyeng.family.feature.player.PlayerDictionaryLookup
import com.lazyeng.family.feature.player.PlayerEngine
import com.lazyeng.family.feature.player.PlayerEngineState
import com.lazyeng.family.feature.player.PlayerPhase
import com.lazyeng.family.feature.player.PlayerPlaybackSession
import com.lazyeng.family.feature.player.PlayerScreen
import com.lazyeng.family.feature.player.PlayerSessionResult
import com.lazyeng.family.feature.player.PlayerSubtitleCue
import com.lazyeng.family.feature.player.PlayerUiEvent
import com.lazyeng.family.feature.player.PlayerViewModel
import com.lazyeng.family.feature.vocabulary.FlashcardReviewEvent
import com.lazyeng.family.feature.vocabulary.FlashcardReviewScreen
import com.lazyeng.family.feature.vocabulary.FlashcardReviewSession
import com.lazyeng.family.feature.vocabulary.FlashcardReviewViewModel
import com.lazyeng.family.feature.vocabulary.VocabularyScreen
import com.lazyeng.family.feature.vocabulary.VocabularySession
import com.lazyeng.family.feature.vocabulary.VocabularyUiEffect
import com.lazyeng.family.feature.vocabulary.VocabularyViewModel
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.media3.common.Player

/** One disposable-database journey; it never reads, clears, or mutates the installed family's data. */
@RunWith(AndroidJUnit4::class)
class EndToEndCoreJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun profileImportPlaybackLookupReviewRestartIsolationAndParentControlsStayConsistent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "int1-journey-${UUID.randomUUID()}.db"
        val familyId = FamilyId("int1-family-${UUID.randomUUID()}")
        val profileA = ProfileId("int1-child-a")
        val profileB = ProfileId("int1-child-b")
        val videoReference = FileReference("content://int1.fixture/video.mp4")
        val subtitleReference = FileReference("content://int1.fixture/video.srt")
        val videoId = VideoId("int1-video")
        val jobId = ImportJobId("int1-job-2")
        val now = Instant.parse("2026-09-28T08:00:00Z")
        val db = Room.databaseBuilder(context, FamilyDatabase::class.java, databaseName).build()
        val viewModels = ViewModelStore()
        val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var player: PlayerViewModel? = null
        var vocabulary: VocabularyViewModel? = null
        var review: FlashcardReviewViewModel? = null
        var imported: VideoImportCoordinator? = null

        try {
            val families = db.familyRepository()
            val profiles = db.profileRepository()
            families.saveFamily(Family(familyId, emptyMap(), now))
            listOf(profileA to "Journey child", profileB to "Sibling").forEach { (id, nickname) ->
                profiles.saveProfile(familyId, id, Profile(id, familyId, nickname, "star", ProfileRole.CHILD,
                    "child", "Power Up 2", emptyMap(), now, null))
            }

            val pinStore = MemoryPinStore()
            val pins = PinService(pinStore, Clock { now }, PinDerivation { pin, salt, iterations ->
                MessageDigest.getInstance("SHA-256").digest(pin.concatToString().toByteArray() + salt + iterations.toString().toByteArray())
            }, Dispatchers.IO)
            val parentPin = CharArray(4) { '4' }
            pins.setup(familyId, parentPin)
            val parent = ParentSession(familyId, pins, Clock { now })
            assertEquals(PinCheck.Accepted, parent.unlock(parentPin))
            parentPin.fill('\u0000')

            val documents = JourneyDocuments()
            val catalog = db.videoRepository(
                media = MediaReadinessChecker { _, _, assets -> assets.any { it.localReference == videoReference } },
                parentSession = parent,
            )
            val parentCatalog = catalog as com.lazyeng.family.core.security.ParentVideoAccess
            var generatedId = 0
            imported = VideoImportCoordinator(
                profileA, documents, catalog, db.videoAssetRepository(), db.importJobRepository(),
                db.subtitleRepository(), db.importTransaction(), { parent.checkScope(parent.requireScope()) },
                Clock { now }, {
                    ++generatedId
                    when (generatedId) {
                        1 -> videoId.value
                        2 -> jobId.value
                        else -> "int1-generated-$generatedId"
                    }
                }, ImportPolicy(),
                listVideosInAuthorizedScope = { parentCatalog.listForAuthorizedParent(profileA, parent.requireScope()) },
                getVideoInAuthorizedScope = { parentCatalog.getForAuthorizedParent(profileA, it, parent.requireScope()) },
            )

            imported.prepare(videoReference, subtitleReference)
            assertEquals(ImportPhase.PREVIEW, imported.state.value.phase)
            assertTrue(db.videoRepository().listVideos(profileA).isEmpty())
            imported.confirm(DuplicateChoice.ImportNew, rightsAcknowledged = true)
            val readyVideo = checkNotNull(imported.state.value.video)
            assertEquals(VideoStatus.READY, readyVideo.status)
            catalog.saveVideo(profileA, readyVideo.copy(metadata = readyVideo.metadata.copy(ageFit = "child")))
            assertEquals(ImportStatus.Ready, db.importJobRepository().getImportJob(profileA, jobId)?.status)
            val publishedTrack = db.subtitleRepository().listTracks(profileA, videoId).single()
            val publishedVersion = checkNotNull(publishedTrack.currentVersionId)
            val subtitleLines = db.subtitleRepository().listLines(profileA, publishedVersion, 0, 20)
            assertEquals(1, subtitleLines.size)
            assertTrue(parentCatalog.listForAuthorizedParent(profileA, parent.requireScope()).any { it.id == videoId })

            val application = compose.activity.application as LazyEngApplication
            val dictionaryFile = application.ensureDictionaryReady()
            assertTrue("Bundled offline dictionary was not provisioned", dictionaryFile.isFile)
            val lineText = subtitleLines.single().text
            val wordStart = lineText.indexOf("running")
            val lookupStarted = System.nanoTime()
            val realEntry = application.lookupDictionary(lineText, wordStart, wordStart + "running".length)
            val lookupMillis = (System.nanoTime() - lookupStarted) / 1_000_000
            assertEquals("run", realEntry?.matchedLemma)
            assertTrue(realEntry?.entry?.phonetic?.isNotBlank() == true)
            assertTrue(realEntry?.entry?.senses?.any { it.contains("跑") } == true)
            assertTrue("Offline dictionary lookup took ${lookupMillis}ms", lookupMillis < 300)

            val lookupRepository = db.lookupEventRepository()
            val queryCalls = AtomicInteger()
            val queryStarted = CompletableDeferred<Unit>()
            val queryRelease = CompletableDeferred<Unit>()
            val coordinator = LookupEventCoordinator(
                lookupScope, lookupRepository, Clock { now }, { UUID.randomUUID().toString() },
                isProfileCurrent = { it == profileA },
                query = { request ->
                    queryCalls.incrementAndGet()
                    queryStarted.complete(Unit)
                    queryRelease.await()
                    application.lookupDictionary(request.sentence, request.start, request.end)
                },
            ).also { it.activateProfile(profileA) }
            val line = subtitleLines.single()
            val request = LookupRequest(profileA, videoId, "running", "running", line.id, lineText,
                wordStart, wordStart + "running".length)
            val repeated = (1..3).map { async(Dispatchers.IO) { coordinator.lookup(request) } }
            withTimeout(5_000) { queryStarted.await() }
            withTimeout(5_000) {
                while (queryCalls.get() < 1) kotlinx.coroutines.delay(5)
                kotlinx.coroutines.delay(100)
            }
            queryRelease.complete(Unit)
            assertTrue(repeated.all { it.await()?.matchedLemma == "run" })
            assertEquals("Concurrent taps should share one lookup", 1, queryCalls.get())
            assertEquals(1, lookupRepository.listEvents(profileA, now.minusSeconds(1), now.plusSeconds(1)).size)

            val vocabularyRepo = db.vocabularyRepository()
            val watchProgress = db.watchProgressRepository()
            val cue = PlayerSubtitleCue(line.id, line.sequence, line.startMs, line.endMs, line.text)
            val playbackEngine = JourneyPlayerEngine()
            player = PlayerViewModel(
                loadSession = {
                    PlayerSessionResult.Ready(PlayerPlaybackSession(
                        profileA, videoId.value, title = readyVideo.metadata.title,
                        uri = readyVideo.sourceReference.value, durationMs = readyVideo.metadata.durationMs!!,
                        resumePositionMs = 0L, subtitles = listOf(cue),
                        lookupWord = { surface, lemma, _, sentence, start, end ->
                            val value = application.lookupDictionary(sentence, start, end)
                            PlayerDictionaryLookup(value?.entry, value?.matchedLemma ?: lemma)
                        },
                        isWordSaved = { lemma -> vocabularyRepo.isWordSaved(profileA, lemma).first() },
                        listSavedWords = { vocabularyRepo.listSavedLemmas(profileA) },
                        explainContext = { _, _, _ -> null },
                        saveWord = { capture -> vocabularyRepo.saveWord(VocabularyCapture(
                            profileA, capture.surface, capture.lemma, capture.dictionaryEntry?.phonetic,
                            capture.dictionaryEntry?.senses?.firstOrNull(), videoId, capture.lineId,
                            capture.sentence, capture.timestampMs, now,
                        )).isSuccess },
                        scopeValid = { true },
                        saveProgress = { position, duration -> watchProgress.saveProgress(profileA,
                            WatchProgress(profileA, videoId, position, position.toFloat() / duration.coerceAtLeast(1), now)) },
                    ))
                },
                engineFactory = { playbackEngine }, clock = Clock { now }, dispatchers = application.dispatchers,
            ).also { viewModels.put("journey-player", it) }
            val playerModel = checkNotNull(player)
            var activeScreen by mutableStateOf("player")
            var vocabularyModel by mutableStateOf<VocabularyViewModel?>(null)
            var reviewModel by mutableStateOf<FlashcardReviewViewModel?>(null)
            compose.setContent {
                LazyEngTheme {
                    when (activeScreen) {
                        "player" -> {
                            val state by playerModel.state.collectAsState()
                            PlayerScreen(state, playerModel.mediaPlayer, playerModel.positionMs,
                                playerModel.subtitles, playerModel.activeSubtitleIndex, playerModel::onEvent)
                        }
                        "vocabulary" -> vocabularyModel?.let { model ->
                            val state by model.state.collectAsState()
                            VocabularyScreen(state, androidx.compose.foundation.lazy.rememberLazyListState(), model::onEvent)
                        }
                        else -> reviewModel?.let { model ->
                            val state by model.state.collectAsState()
                            FlashcardReviewScreen(state, onEvent = model::onEvent, onBack = {})
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { playerModel.state.value.phase == PlayerPhase.READY }
            compose.onNodeWithTag("player-play-pause").assertIsDisplayed()
            playerModel.onEvent(PlayerUiEvent.Seek(7_500L))
            compose.onNodeWithTag("line-1-word-$wordStart").performClick()
            compose.waitUntil(10_000) { playerModel.state.value.lookupEntry != null }
            compose.onNodeWithTag("lookup-word").assertTextContains("running")
            compose.onNodeWithTag("lookup-lemma").assertTextContains("原形 run")
            compose.onNodeWithTag("lookup-sense-0").assertIsDisplayed()
            compose.waitUntil(5_000) {
                playerModel.state.value.aiExplanation.phase == com.lazyeng.family.feature.player.AiExplanationPhase.FALLBACK
            }
            compose.onNodeWithTag("ai-explanation-fallback").assertIsDisplayed()
            compose.onNodeWithTag("lookup-save").performClick()
            compose.waitUntil(10_000) { playerModel.state.value.lookupSaved }
            val saved = checkNotNull(vocabularyRepo.getItem(profileA, "run"))
            val savedContext = vocabularyRepo.listContexts(profileA, "run").single()
            assertEquals("running", savedContext.surface)
            assertEquals(lineText, savedContext.contextSentence)
            assertEquals(videoId, savedContext.videoId)
            assertEquals(7_500L, savedContext.timestampMs)
            compose.waitUntil(5_000) {
                runBlocking { watchProgress.getProgress(profileA, videoId)?.positionMs ?: 0L } >= 7_500L
            }
            assertTrue(watchProgress.getProgress(profileA, videoId)!!.positionMs >= 7_500L)

            vocabulary = VocabularyViewModel({ VocabularySession(profileA, vocabularyRepo,
                { application.lookupDictionary("run", 0, 3)?.entry },
                { watchProgress.getProgress(profileA, it) != null }, { now }) })
                .also { viewModels.put("journey-vocabulary", it) }
            vocabularyModel = checkNotNull(vocabulary)
            activeScreen = "vocabulary"
            val vocabularyScreenModel = checkNotNull(vocabularyModel)
            compose.waitUntil(10_000) { !vocabularyScreenModel.state.value.loading && vocabularyScreenModel.state.value.rows.isNotEmpty() }
            compose.onNodeWithTag("vocabulary-word-${saved.id}").assertTextContains("running")
            compose.onNodeWithTag("vocabulary-context-${saved.id}").assertTextContains(lineText)
            val contextNavigation = async { vocabularyScreenModel.effectFlow.first() }
            compose.onNodeWithTag("vocabulary-watch-${saved.id}").performClick()
            val contextEffect = withTimeout(5_000) { contextNavigation.await() }
            assertTrue(contextEffect is VocabularyUiEffect.OpenPlayer)
            assertEquals(videoId, (contextEffect as VocabularyUiEffect.OpenPlayer).videoId)
            assertEquals(7_500L, contextEffect.timestampMs)

            review = FlashcardReviewViewModel({ FlashcardReviewSession(profileA, vocabularyRepo, { now },
                { "int1-review" }, { application.lookupDictionary("run", 0, 3)?.entry },
                { watchProgress.getProgress(profileA, it) != null }) })
                .also { viewModels.put("journey-review", it) }
            reviewModel = checkNotNull(review)
            activeScreen = "review"
            val reviewScreenModel = checkNotNull(reviewModel)
            compose.waitUntil(10_000) { !reviewScreenModel.state.value.loading && reviewScreenModel.state.value.current != null }
            compose.onNodeWithTag("review-flip").performClick()
            compose.waitUntil(2_000) { reviewScreenModel.state.value.flipped }
            compose.onNodeWithTag("review-rating-REMEMBERED").performClick()
            compose.waitUntil(10_000) { reviewScreenModel.state.value.complete }
            val reviewed = checkNotNull(vocabularyRepo.getItem(profileA, "run"))
            assertEquals(VocabularyStatus.REVIEW, reviewed.status)
            assertNotNull(reviewed.nextReviewAt)
            assertEquals(1, db.reviewAttemptRepository().listAttempts(profileA, "run").size)

            assertNull(vocabularyRepo.getItem(profileB, "run"))
            assertTrue(vocabularyRepo.getWordsByProfile(profileB).first().isEmpty())
            assertNull(watchProgress.getProgress(profileB, videoId))
            assertTrue(lookupRepository.listEvents(profileB, now.minusSeconds(1), now.plusSeconds(1)).isEmpty())

            val wrongPin = "9999".toCharArray()
            assertTrue(parent.confirmDeletion(parent.requireScope(), wrongPin) is PinCheck.Rejected)
            wrongPin.fill('\u0000')
            assertNotNull(profiles.getProfile(familyId, profileA))
            assertNotNull(profiles.getProfile(familyId, profileB))

            catalog.setChildVisibility(profileA, videoId, visible = false)
            assertTrue(catalog.listVideos(profileA).none { it.id == videoId })
            assertTrue(parentCatalog.listForAuthorizedParent(profileA, parent.requireScope()).any { it.id == videoId })

            viewModels.clear()
            player = null; vocabulary = null; review = null
            db.close()
            val reopened = Room.databaseBuilder(context, FamilyDatabase::class.java, databaseName).build()
            try {
                assertNotNull(reopened.profileRepository().getProfile(familyId, profileA))
                assertNotNull(reopened.watchProgressRepository().getProgress(profileA, videoId))
                assertEquals(ImportStatus.Ready, reopened.importJobRepository().getImportJob(profileA, jobId)?.status)
                assertNotNull(reopened.vocabularyRepository().getItem(profileA, "run")?.nextReviewAt)
                assertEquals(1, reopened.reviewAttemptRepository().listAttempts(profileA, "run").size)
                assertEquals(1, reopened.lookupEventRepository().listEvents(profileA, now.minusSeconds(1), now.plusSeconds(1)).size)
                assertNull(reopened.vocabularyRepository().getItem(profileB, "run"))
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                    assertFalse(cursor.moveToFirst())
                }
            } finally { reopened.close() }
        } finally {
            viewModels.clear()
            lookupScope.cancel()
            runCatching { imported?.cancel() }
            runCatching { db.close() }
            context.deleteDatabase(databaseName)
        }
    }

    private class MemoryPinStore : PinRecordStore {
        private var value: PinRecord? = null
        override suspend fun read() = value
        override suspend fun write(record: PinRecord) { value = record }
    }

    private class JourneyDocuments : ImportDocuments {
        private val grants = mutableSetOf<FileReference>()
        private val subtitle = "1\n00:00:01,000 --> 00:00:20,000\nShe is running home.\n".toByteArray()
        override suspend fun retain(reference: FileReference) = grants.add(reference)
        override suspend fun release(reference: FileReference) = Unit
        override suspend fun probe(reference: FileReference, progress: (Long) -> Unit): ProbedVideo {
            progress(1024)
            return ProbedVideo(VideoTechnicalMetadata("fixture.mp4", 320, 240, "video/mp4"),
                60_000L, 1024L, "int1-fixture-checksum", "video/avc")
        }
        override suspend fun readSubtitle(reference: FileReference) = SelectedSubtitle(subtitle, "fixture.srt")
        override suspend fun findSubtitleCandidates(directory: FileReference, videoDisplayName: String) = emptyList<SubtitleCandidate>()
        override suspend fun accessible(reference: FileReference) = reference in grants || reference.value == "content://int1.fixture/video.mp4"
        override suspend fun availableStorageBytes() = Long.MAX_VALUE
    }

    private class JourneyPlayerEngine : PlayerEngine {
        override val player: Player? = null
        override val state = kotlinx.coroutines.flow.MutableStateFlow(PlayerEngineState())
        override fun prepare(uri: String, startPositionMs: Long) {
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
