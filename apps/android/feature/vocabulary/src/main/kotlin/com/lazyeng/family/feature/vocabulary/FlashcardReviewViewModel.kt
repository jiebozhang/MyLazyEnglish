package com.lazyeng.family.feature.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.model.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class FlashcardReviewSession(
    val profileId: ProfileId,
    val repository: VocabularyRepository,
    val now: () -> Instant,
    val newEventId: () -> String,
    val lookupEntry: suspend (String) -> DictionaryEntry?,
    val canOpenVideo: suspend (VideoId) -> Boolean,
)

data class Flashcard(
    val item: VocabularyItem,
    val context: VocabularyContext?,
    val entry: DictionaryEntry?,
)

data class FlashcardReviewUiState(
    val loading: Boolean = true,
    val cards: List<Flashcard> = emptyList(),
    val index: Int = 0,
    val flipped: Boolean = false,
    val saving: Boolean = false,
    val exitConfirmation: Boolean = false,
    val ratings: Map<ReviewRating, Int> = ReviewRating.entries.associateWith { 0 },
    val message: String? = null,
) {
    val current: Flashcard? get() = cards.getOrNull(index)
    val completedCount: Int get() = ratings.values.sum()
    val complete: Boolean get() = !loading && cards.isEmpty() && completedCount > 0
    val initiallyEmpty: Boolean get() = !loading && cards.isEmpty() && completedCount == 0
}

sealed interface FlashcardReviewEvent {
    data object Flip : FlashcardReviewEvent
    data class Rate(val rating: ReviewRating) : FlashcardReviewEvent
    data object RequestExit : FlashcardReviewEvent
    data object CancelExit : FlashcardReviewEvent
    data object ConfirmExit : FlashcardReviewEvent
    data object ClearMessage : FlashcardReviewEvent
    data object WatchContext : FlashcardReviewEvent
}

sealed interface FlashcardReviewEffect {
    data object Exit : FlashcardReviewEffect
    data class OpenPlayer(val videoId: VideoId, val timestampMs: Long) : FlashcardReviewEffect
    data class Message(val text: String) : FlashcardReviewEffect
}

class FlashcardReviewViewModel(
    private val createSession: suspend () -> FlashcardReviewSession?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FlashcardReviewUiState())
    val state = mutableState.asStateFlow()
    private val effects = Channel<FlashcardReviewEffect>(Channel.BUFFERED)
    val effectFlow = effects.receiveAsFlow()
    private var session: FlashcardReviewSession? = null

    init { load() }

    fun onEvent(event: FlashcardReviewEvent) {
        when (event) {
            FlashcardReviewEvent.Flip -> if (!mutableState.value.saving && mutableState.value.current != null) {
                mutableState.value = mutableState.value.copy(flipped = !mutableState.value.flipped)
            }
            is FlashcardReviewEvent.Rate -> rate(event.rating)
            FlashcardReviewEvent.RequestExit -> mutableState.value = mutableState.value.copy(exitConfirmation = true)
            FlashcardReviewEvent.CancelExit -> mutableState.value = mutableState.value.copy(exitConfirmation = false)
            FlashcardReviewEvent.ConfirmExit -> { mutableState.value = mutableState.value.copy(exitConfirmation = false); emit(FlashcardReviewEffect.Exit) }
            FlashcardReviewEvent.ClearMessage -> mutableState.value = mutableState.value.copy(message = null)
            FlashcardReviewEvent.WatchContext -> watchContext()
        }
    }

    private fun load() = viewModelScope.launch {
        try {
            val active = createSession()
            session = active
            if (active == null) {
                mutableState.value = FlashcardReviewUiState(loading = false, message = "当前档案不可用，请返回后重新选择。")
                return@launch
            }
            val now = active.now()
            val due = active.repository.getWordsByProfile(active.profileId).first()
                .filter { ReviewSchedule.isDue(it, now) }
                .sortedWith(compareBy<VocabularyItem>({ it.nextReviewAt ?: Instant.MIN }, { it.lemma }))
            val cards = due.map { item ->
                val context = active.repository.listContexts(active.profileId, item.lemma)
                    .maxWithOrNull(compareBy<VocabularyContext>({ it.createdAt ?: Instant.MIN }, { it.id }))
                val entry = try { active.lookupEntry(item.lemma) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                Flashcard(item, context, entry)
            }
            mutableState.value = FlashcardReviewUiState(loading = false, cards = cards)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = FlashcardReviewUiState(loading = false, message = "暂时无法准备复习内容，请重试或返回生词本。")
        }
    }

    private fun rate(rating: ReviewRating) {
        val currentState = mutableState.value
        val card = currentState.current ?: return
        val active = session ?: return
        if (currentState.saving || !currentState.flipped) return
        mutableState.value = currentState.copy(saving = true, message = null)
        viewModelScope.launch {
            try {
                val now = active.now()
                val updated = ReviewSchedule.apply(card.item, rating, now)
                val eventId = active.newEventId()
                active.repository.recordReview(
                    active.profileId,
                    ReviewAttempt(
                        id = ReviewAttemptId(eventId),
                        eventUuid = EventUuid(eventId),
                        profileId = active.profileId,
                        vocabularyItem = card.item.key,
                        rating = rating,
                        occurredAt = now,
                    ),
                    updated,
                )
                val nextCards = currentState.cards.toMutableList().apply { removeAt(currentState.index) }
                val nextIndex = currentState.index.coerceAtMost((nextCards.size - 1).coerceAtLeast(0))
                mutableState.value = currentState.copy(
                    cards = nextCards,
                    index = nextIndex,
                    flipped = false,
                    saving = false,
                    ratings = currentState.ratings + (rating to (currentState.ratings.getValue(rating) + 1)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(saving = false, message = "暂未保存复习结果，请重试。")
            }
        }
    }

    private fun watchContext() {
        val active = session ?: return
        val context = mutableState.value.current?.context
        val videoId = context?.videoId
        if (context == null || videoId == null) {
            emit(FlashcardReviewEffect.Message("这张卡没有可返回的视频原句。"))
            return
        }
        viewModelScope.launch {
            val canOpen = try { active.canOpenVideo(videoId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (canOpen) emit(FlashcardReviewEffect.OpenPlayer(videoId, context.timestampMs))
            else emit(FlashcardReviewEffect.Message("原视频暂时无法访问，生词和例句仍会保留。"))
        }
    }

    private fun emit(effect: FlashcardReviewEffect) { viewModelScope.launch { effects.send(effect) } }

    override fun onCleared() { effects.close() }
}
