package com.lazyeng.family.feature.vocabulary

import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.testing.TestDispatchers
import com.lazyeng.family.core.testing.FakeClock
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyViewModelTest {
    private val profileId = ProfileId("fixture-child")
    private val videoId = VideoId("fixture-video")
    private val now = Instant.parse("2026-09-27T00:00:00Z")

    @Test
    fun profileRowsFilterAndConfirmedDeleteAreScoped() = runTest {
        val testDispatchers = TestDispatchers(testScheduler)
        Dispatchers.setMain(testDispatchers.dispatcher)
        val repository = FakeVocabularyRepository(
            profileId,
            listOf(item(1, "run"), item(2, "private", ProfileId("another-child"))),
            listOf(context(1, "run"), context(2, "private", ProfileId("another-child"))),
        )
        val store = ViewModelStore()
        try {
            val model = VocabularyViewModel(
                createSession = { VocabularySession(profileId, repository, { null }, { true }, { now }) },
                initialStatus = VocabularyStatus.REVIEW,
            )
            store.put("vocab", model)
            advanceUntilIdle()
            assertEquals(profileId, repository.lastReadProfile)
            assertEquals(listOf("run"), model.state.value.rows.map { it.item.lemma })
            assertEquals(1, model.state.value.todayDueCount)
            assertTrue(model.state.value.visibleRows.isEmpty())

            model.onEvent(VocabularyUiEvent.SelectStatus(null))
            advanceUntilIdle()
            assertEquals(1, model.state.value.visibleRows.size)
            model.onEvent(VocabularyUiEvent.RequestDelete(1))
            assertEquals(1L, model.state.value.pendingDeleteId)
            model.onEvent(VocabularyUiEvent.CancelDelete)
            assertNull(model.state.value.pendingDeleteId)
            assertEquals(1, model.state.value.rows.size)

            model.onEvent(VocabularyUiEvent.RequestDelete(1))
            model.onEvent(VocabularyUiEvent.ConfirmDelete)
            advanceUntilIdle()
            assertTrue(repository.deletedProfiles.contains(profileId))
            assertTrue(model.state.value.rows.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reviewQueueUsesInjectedTimeAndRatingPersistsSnapshotBeforeAdvancing() = runTest {
        val testDispatchers = TestDispatchers(testScheduler)
        Dispatchers.setMain(testDispatchers.dispatcher)
        val clock = FakeClock(now)
        val repository = FakeVocabularyRepository(profileId, listOf(item(10, "run")), listOf(context(10, "run")))
        val store = ViewModelStore()
        try {
            val model = FlashcardReviewViewModel {
                FlashcardReviewSession(profileId, repository, { clock.now }, { "stable-event" }, { null }, { true })
            }
            store.put("review", model)
            advanceUntilIdle()
            assertEquals("run", model.state.value.current?.item?.lemma)
            model.onEvent(FlashcardReviewEvent.Flip)
            model.onEvent(FlashcardReviewEvent.Rate(ReviewRating.REMEMBERED))
            advanceUntilIdle()
            assertTrue(model.state.value.complete)
            assertEquals(1, model.state.value.ratings[ReviewRating.REMEMBERED])
            assertEquals(1, repository.recordedAttempts.size)
            assertEquals(now.plusSeconds(3 * 86_400L), repository.lastReviewedItem?.nextReviewAt)
            assertEquals(VocabularyStatus.REVIEW, repository.lastReviewedItem?.status)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun contextNavigationCarriesTimestampAndMissingVideoIsReported() = runTest {
        val testDispatchers = TestDispatchers(testScheduler)
        Dispatchers.setMain(testDispatchers.dispatcher)
        val repository = FakeVocabularyRepository(profileId, listOf(item(7, "run")), listOf(context(7, "run")))
        val store = ViewModelStore()
        try {
            val model = VocabularyViewModel(
                createSession = { VocabularySession(profileId, repository, { null }, { it == videoId }, { now }) },
            )
            store.put("vocab", model)
            advanceUntilIdle()
            val navigation = backgroundScope.async { model.effectFlow.first() }
            model.onEvent(VocabularyUiEvent.WatchContext(7))
            advanceUntilIdle()
            assertEquals(VocabularyUiEffect.OpenPlayer(videoId, 12_500L), navigation.await())

            val missingModel = VocabularyViewModel(
                createSession = { VocabularySession(profileId, repository, { null }, { false }, { now }) },
            )
            store.put("missing", missingModel)
            advanceUntilIdle()
            val message = backgroundScope.async { missingModel.effectFlow.first() }
            missingModel.onEvent(VocabularyUiEvent.WatchContext(7))
            advanceUntilIdle()
            assertEquals("原视频已不存在或暂时无法访问，请让家长重新导入。", (message.await() as VocabularyUiEffect.Message).text)
            assertFalse(missingModel.state.value.rows.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun item(id: Long, lemma: String, owner: ProfileId = profileId) = VocabularyItem(owner, lemma, VocabularyStatus.LEARNING, null, id)
    private fun context(id: Long, lemma: String, owner: ProfileId = profileId) = VocabularyContext(
        VocabularyItemKey(owner, lemma), SubtitleLineId("line-$id"), null, 12_500L,
        id = id, surface = "running", phonetic = "/ˈrʌnɪŋ/", definition = "跑", videoId = videoId,
        contextSentence = "They are running home.", createdAt = now,
    )

    private class FakeVocabularyRepository(
        private val profile: ProfileId,
        initialItems: List<VocabularyItem>,
        private val savedContexts: List<VocabularyContext>,
    ) : VocabularyRepository {
        private val rows = MutableStateFlow(initialItems)
        val deletedProfiles = mutableListOf<ProfileId>()
        val recordedAttempts = mutableListOf<ReviewAttempt>()
        var lastReviewedItem: VocabularyItem? = null
        var lastReadProfile: ProfileId? = null
        override suspend fun listItems(profileId: ProfileId) = rows.value.filter { it.profileId == profileId }
        override suspend fun getItem(profileId: ProfileId, lemma: String) = rows.value.firstOrNull { it.profileId == profileId && it.lemma == lemma }
        override suspend fun saveSnapshot(profileId: ProfileId, item: VocabularyItem) { rows.value = rows.value + item }
        override suspend fun deleteItem(profileId: ProfileId, lemma: String) { rows.value = rows.value.filterNot { it.profileId == profileId && it.lemma == lemma } }
        override suspend fun listContexts(profileId: ProfileId, lemma: String) = savedContexts.filter { it.vocabularyItem.profileId == profileId && it.vocabularyItem.lemma == lemma }
        override suspend fun addContext(profileId: ProfileId, context: VocabularyContext) = Unit
        override suspend fun saveWord(capture: VocabularyCapture) = Result.success(Unit)
        override fun isWordSaved(profileId: ProfileId, lemma: String): Flow<Boolean> = MutableStateFlow(false)
        override fun getWordsByProfile(profileId: ProfileId): Flow<List<VocabularyItem>> {
            lastReadProfile = profileId
            return rows.map { values -> values.filter { it.profileId == profileId } }
        }
        override suspend fun listSavedLemmas(profileId: ProfileId) = rows.value.filter { it.profileId == profileId }.map { it.lemma }.toSet()
        override suspend fun deleteWord(profileId: ProfileId, id: Long) {
            deletedProfiles += profileId
            rows.value = rows.value.filterNot { it.profileId == profile && it.id == id }
        }
        override suspend fun recordReview(profileId: ProfileId, attempt: ReviewAttempt, updatedItem: VocabularyItem): Boolean {
            if (recordedAttempts.any { it.eventUuid == attempt.eventUuid }) return false
            recordedAttempts += attempt
            lastReviewedItem = updatedItem
            rows.value = rows.value.map { if (it.key == updatedItem.key && it.profileId == profileId) updatedItem else it }
            return true
        }
    }
}
