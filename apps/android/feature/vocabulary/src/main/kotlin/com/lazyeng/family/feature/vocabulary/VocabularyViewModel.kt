package com.lazyeng.family.feature.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.model.VocabularyContext
import com.lazyeng.family.core.model.VocabularyItem
import com.lazyeng.family.core.model.VocabularyRepository
import com.lazyeng.family.core.model.VocabularyStatus
import com.lazyeng.family.core.model.ReviewSchedule
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class VocabularySession(
    val profileId: ProfileId,
    val repository: VocabularyRepository,
    val lookupEntry: suspend (String) -> DictionaryEntry?,
    val canOpenVideo: suspend (VideoId) -> Boolean,
    val now: () -> Instant,
)

data class VocabularyRow(
    val item: VocabularyItem,
    val context: VocabularyContext?,
    val entry: DictionaryEntry?,
) {
    val sourceVideoId: String? get() = context?.videoId?.value
}

data class VocabularyUiState(
    val loading: Boolean = true,
    val rows: List<VocabularyRow> = emptyList(),
    val statusFilter: VocabularyStatus? = null,
    val sourceFilter: String? = null,
    val pendingDeleteId: Long? = null,
    val message: String? = null,
    val todayDueCount: Int = 0,
) {
    val sources: List<String> get() = rows.mapNotNull(VocabularyRow::sourceVideoId).distinct().sorted()
    val visibleRows: List<VocabularyRow> get() = rows.filter { row ->
        (statusFilter == null || row.item.status == statusFilter) &&
            (sourceFilter == null || row.sourceVideoId == sourceFilter)
    }
}

sealed interface VocabularyUiEvent {
    data object Refresh : VocabularyUiEvent
    data class SelectStatus(val status: VocabularyStatus?) : VocabularyUiEvent
    data class SelectSource(val videoId: String?) : VocabularyUiEvent
    data class RequestDelete(val id: Long) : VocabularyUiEvent
    data object CancelDelete : VocabularyUiEvent
    data object ConfirmDelete : VocabularyUiEvent
    data class WatchContext(val id: Long) : VocabularyUiEvent
    data object ClearFilters : VocabularyUiEvent
    data object ClearMessage : VocabularyUiEvent
}

sealed interface VocabularyUiEffect {
    data class OpenPlayer(val videoId: VideoId, val timestampMs: Long) : VocabularyUiEffect
    data class Message(val text: String) : VocabularyUiEffect
}

class VocabularyViewModel(
    private val createSession: suspend () -> VocabularySession?,
    invalidations: Flow<Unit> = emptyFlow(),
    initialStatus: VocabularyStatus? = null,
    initialSource: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        VocabularyUiState(statusFilter = initialStatus, sourceFilter = initialSource),
    )
    val state = mutableState.asStateFlow()
    private val effects = Channel<VocabularyUiEffect>(Channel.BUFFERED)
    val effectFlow = effects.receiveAsFlow()
    private var loadJob: Job? = null
    private var session: VocabularySession? = null

    init {
        viewModelScope.launch {
            invalidations.collect {
                session = null
                refresh()
            }
        }
        refresh()
    }

    fun onEvent(event: VocabularyUiEvent) {
        when (event) {
            VocabularyUiEvent.Refresh -> refresh()
            is VocabularyUiEvent.SelectStatus -> mutableState.value = mutableState.value.copy(statusFilter = event.status)
            is VocabularyUiEvent.SelectSource -> mutableState.value = mutableState.value.copy(sourceFilter = event.videoId)
            VocabularyUiEvent.ClearFilters -> mutableState.value = mutableState.value.copy(statusFilter = null, sourceFilter = null)
            is VocabularyUiEvent.RequestDelete -> mutableState.value = mutableState.value.copy(pendingDeleteId = event.id)
            VocabularyUiEvent.CancelDelete -> mutableState.value = mutableState.value.copy(pendingDeleteId = null)
            VocabularyUiEvent.ConfirmDelete -> deletePending()
            is VocabularyUiEvent.WatchContext -> watchContext(event.id)
            VocabularyUiEvent.ClearMessage -> mutableState.value = mutableState.value.copy(message = null)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, rows = emptyList(), pendingDeleteId = null, message = null)
        loadJob = viewModelScope.launch {
            try {
                val current = session ?: createSession()?.also { session = it }
                if (current == null) {
                    mutableState.value = mutableState.value.copy(loading = false, message = "当前档案不可用，请重新选择档案。")
                    return@launch
                }
                current.repository.getWordsByProfile(current.profileId).collect { items ->
                    val rows = items.map { item ->
                        val context = current.repository.listContexts(current.profileId, item.lemma)
                            .maxWithOrNull(compareBy<VocabularyContext>({ it.createdAt ?: java.time.Instant.EPOCH }, VocabularyContext::id))
                        val entry = try { current.lookupEntry(item.lemma) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                        VocabularyRow(item, context, entry)
                    }.sortedByDescending { it.context?.createdAt ?: java.time.Instant.EPOCH }
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        rows = rows,
                        todayDueCount = rows.count { ReviewSchedule.isDue(it.item, current.now()) },
                        message = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = "暂时无法读取生词，请稍后重试。")
            }
        }
    }

    private fun deletePending() {
        val current = session ?: return
        val id = mutableState.value.pendingDeleteId ?: return
        viewModelScope.launch {
            try {
                current.repository.deleteWord(current.profileId, id)
                mutableState.value = mutableState.value.copy(pendingDeleteId = null)
                effects.send(VocabularyUiEffect.Message("已从生词本移除"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(pendingDeleteId = null, message = "移除失败，请重试。")
            }
        }
    }

    private fun watchContext(id: Long) {
        val current = session ?: return
        val row = mutableState.value.rows.firstOrNull { it.item.id == id } ?: return
        val video = row.context?.videoId
        if (video == null) {
            emit(VocabularyUiEffect.Message("这条生词没有可返回的视频记录。"))
            return
        }
        viewModelScope.launch {
            val available = try { current.canOpenVideo(video) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (available) emit(VocabularyUiEffect.OpenPlayer(video, row.context.timestampMs))
            else emit(VocabularyUiEffect.Message("原视频已不存在或暂时无法访问，请让家长重新导入。"))
        }
    }

    private fun emit(effect: VocabularyUiEffect) {
        viewModelScope.launch { effects.send(effect) }
    }

    override fun onCleared() {
        effects.close()
    }
}
