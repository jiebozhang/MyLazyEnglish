package com.lazyeng.family.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.RestrictedContentOverrideAudit
import com.lazyeng.family.core.common.RestrictedContentOverrideEvent
import com.lazyeng.family.core.model.SubtitleStatus
import com.lazyeng.family.core.model.Video
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.model.VideoSourceType
import com.lazyeng.family.core.model.VideoStatus
import com.lazyeng.family.core.security.ParentAuthorizedScope
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.core.security.PinCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LibrarySubtitleFilter { ALL, AVAILABLE, MISSING, UNKNOWN }

data class LibraryFilters(
    val query: String = "",
    val level: String = "",
    val topic: String = "",
    val source: String = "",
    val subtitle: LibrarySubtitleFilter = LibrarySubtitleFilter.ALL,
) {
    val active: Boolean get() = query.isNotBlank() || level.isNotBlank() || topic.isNotBlank() ||
        source.isNotBlank() || subtitle != LibrarySubtitleFilter.ALL
}

data class LibraryUiState(
    val loading: Boolean = true,
    val videos: List<Video> = emptyList(),
    val filters: LibraryFilters = LibraryFilters(),
    val isChild: Boolean = true,
    val overrideActive: Boolean = false,
    val pinGateOpen: Boolean = false,
    val enteredCount: Int = 0,
    val remainingSeconds: Long = 0,
    val message: String? = null,
) {
    val visibleVideos: List<Video> get() = filterLibraryVideos(videos, filters)
    val levels: List<String> get() = videos.mapNotNull { it.metadata.cefrLevel?.takeIf(String::isNotBlank) }.distinct().sorted()
    val topics: List<String> get() = videos.flatMap { it.metadata.topicTags }.filter(String::isNotBlank).distinct().sorted()
    val sources: List<String> get() = videos.map { it.sourceType.name }.distinct().sorted()
}

fun filterLibraryVideos(videos: List<Video>, filters: LibraryFilters): List<Video> = videos.filter { video ->
    val queryMatches = filters.query.isBlank() || sequenceOf(video.metadata.title, video.metadata.cefrLevel.orEmpty())
        .plus(video.metadata.topicTags.asSequence()).any { it.contains(filters.query.trim(), ignoreCase = true) }
    val levelMatches = filters.level.isBlank() || video.metadata.cefrLevel.equals(filters.level, ignoreCase = true)
    val topicMatches = filters.topic.isBlank() || video.metadata.topicTags.any { it.equals(filters.topic, ignoreCase = true) }
    val sourceMatches = filters.source.isBlank() || video.sourceType.name == filters.source
    val subtitleMatches = when (filters.subtitle) {
        LibrarySubtitleFilter.ALL -> true
        LibrarySubtitleFilter.AVAILABLE -> video.subtitleStatus == SubtitleStatus.Available
        LibrarySubtitleFilter.MISSING -> video.subtitleStatus == SubtitleStatus.NoSubtitle
        LibrarySubtitleFilter.UNKNOWN -> video.subtitleStatus is SubtitleStatus.Unknown
    }
    queryMatches && levelMatches && topicMatches && sourceMatches && subtitleMatches
}

sealed interface LibraryUiEvent {
    data class RestoreFilters(val filters: LibraryFilters) : LibraryUiEvent
    data class QueryChanged(val value: String) : LibraryUiEvent
    data class LevelSelected(val value: String) : LibraryUiEvent
    data class TopicSelected(val value: String) : LibraryUiEvent
    data class SourceSelected(val value: String) : LibraryUiEvent
    data class SubtitleSelected(val value: LibrarySubtitleFilter) : LibraryUiEvent
    data object ClearFilters : LibraryUiEvent
    data object Refresh : LibraryUiEvent
    data object RequestOverride : LibraryUiEvent
    data object EndOverride : LibraryUiEvent
    data object CancelPin : LibraryUiEvent
    data class Digit(val value: Int) : LibraryUiEvent
    data object Erase : LibraryUiEvent
    data object SubmitPin : LibraryUiEvent
    data class VideoClicked(val videoId: VideoId) : LibraryUiEvent
}

sealed interface LibraryUiEffect {
    data class OpenVideo(val videoId: VideoId, val parentView: Boolean) : LibraryUiEffect
}

class LibraryViewModel(
    private val factory: suspend () -> LibrarySession,
    private val audit: RestrictedContentOverrideAudit,
    private val clock: Clock,
    invalidations: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryUiState())
    val state = mutable.asStateFlow()
    private val channel = Channel<LibraryUiEffect>(Channel.BUFFERED)
    val effects = channel.receiveAsFlow()
    private var session: LibrarySession? = null
    private var loadJob: Job? = null
    private var pinJob: Job? = null
    private var pin = CharArray(0)
    private var lockUntil = 0L
    private var filters = LibraryFilters()

    init {
        viewModelScope.launch {
            invalidations.collect {
                onBackground()
                session = null
                refresh()
            }
        }
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(250)
                val remaining = ((lockUntil - clock.now().toEpochMilli()).coerceAtLeast(0) + 999) / 1000
                mutable.update { it.copy(remainingSeconds = remaining) }
                if (state.value.overrideActive && session?.parent?.authorized() != true) {
                    onBackground()
                    refresh()
                }
            }
        }
    }

    fun onEvent(event: LibraryUiEvent) {
        when (event) {
            is LibraryUiEvent.RestoreFilters -> { filters = event.filters; mutable.update { it.copy(filters = filters) } }
            is LibraryUiEvent.QueryChanged -> updateFilters(filters.copy(query = event.value))
            is LibraryUiEvent.LevelSelected -> updateFilters(filters.copy(level = event.value))
            is LibraryUiEvent.TopicSelected -> updateFilters(filters.copy(topic = event.value))
            is LibraryUiEvent.SourceSelected -> updateFilters(filters.copy(source = event.value))
            is LibraryUiEvent.SubtitleSelected -> updateFilters(filters.copy(subtitle = event.value))
            LibraryUiEvent.ClearFilters -> updateFilters(LibraryFilters())
            LibraryUiEvent.Refresh -> refresh()
            LibraryUiEvent.RequestOverride -> {
                if (state.value.isChild) {
                    viewModelScope.launch {
                        try {
                            val current = session ?: factory().also { session = it }
                            lockUntil = current.parent.status().lockUntil
                            mutable.update { it.copy(pinGateOpen = true, message = null, remainingSeconds = remainingSeconds()) }
                        } catch (_: Exception) {
                            mutable.update { it.copy(message = "暂时无法进行家长验证，请重试。") }
                        }
                    }
                }
            }
            LibraryUiEvent.EndOverride -> {
                session?.parent?.revoke()
                mutable.update { it.copy(overrideActive = false, videos = emptyList(), pinGateOpen = false) }
                refresh()
            }
            LibraryUiEvent.CancelPin -> { clearPin(); mutable.update { it.copy(pinGateOpen = false, enteredCount = 0, message = null) } }
            is LibraryUiEvent.Digit -> if (event.value in 0..9 && state.value.pinGateOpen && pin.size < 4 && !state.value.loading) {
                val next = pin + ('0' + event.value); clearPin(); pin = next
                mutable.update { it.copy(enteredCount = pin.size, message = null) }
            }
            LibraryUiEvent.Erase -> if (state.value.pinGateOpen && !state.value.loading) {
                val next = pin.dropLast(1).toCharArray(); clearPin(); pin = next
                mutable.update { it.copy(enteredCount = pin.size) }
            }
            LibraryUiEvent.SubmitPin -> verifyPin()
            is LibraryUiEvent.VideoClicked -> if (state.value.visibleVideos.any { it.id == event.videoId }) {
                viewModelScope.launch { channel.send(LibraryUiEffect.OpenVideo(event.videoId, state.value.overrideActive)) }
            }
        }
    }

    fun onBackground() {
        loadJob?.cancel()
        pinJob?.cancel()
        session?.parent?.revoke()
        clearPin()
        mutable.update { it.copy(videos = emptyList(), overrideActive = false, pinGateOpen = false,
            enteredCount = 0, loading = false, message = null) }
    }

    private fun updateFilters(value: LibraryFilters) {
        filters = value
        mutable.update { it.copy(filters = value) }
    }

    private fun refresh(scope: ParentAuthorizedScope? = null) {
        loadJob?.cancel()
        mutable.update { it.copy(loading = true, message = null) }
        loadJob = viewModelScope.launch {
            try {
                val current = session ?: factory().also { session = it }
                val videos = current.data.list(scope)
                mutable.update { it.copy(loading = false, videos = videos, isChild = current.isChild,
                    overrideActive = scope != null, filters = filters, message = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, videos = emptyList(),
                    message = "本地视频暂时无法读取，请重试。", overrideActive = false) }
            }
        }
    }

    private fun verifyPin() {
        val current = session ?: return
        if (!state.value.pinGateOpen || pin.size != 4 || state.value.remainingSeconds > 0 || state.value.loading) return
        val candidate = pin.copyOf(); clearPin()
        mutable.update { it.copy(loading = true, message = null, enteredCount = 0) }
        pinJob?.cancel()
        pinJob = viewModelScope.launch {
            try {
                when (current.parent.unlock(candidate)) {
                    PinCheck.Accepted -> {
                        val scope = current.parent.requireScope()
                        if (!current.data.validScope()) throw IllegalStateException("Profile scope changed")
                        audit.append(RestrictedContentOverrideEvent(scope.familyId, current.data.profileId, clock.now()))
                        current.parent.checkScope(scope)
                        mutable.update { it.copy(pinGateOpen = false) }
                        refresh(scope)
                    }
                    is PinCheck.Rejected, is PinCheck.Locked -> {
                        lockUntil = current.parent.status().lockUntil
                        mutable.update { it.copy(loading = false, enteredCount = 0, remainingSeconds = remainingSeconds(),
                            message = if (lockUntil > clock.now().toEpochMilli()) "PIN 错误，请稍后重试。" else "PIN 不正确，请重试。") }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current.parent.revoke()
                mutable.update { it.copy(loading = false, overrideActive = false, videos = emptyList(),
                    message = "家长验证未完成，请重试。") }
            } finally { candidate.fill('\u0000') }
        }
    }

    private fun remainingSeconds() = ((lockUntil - clock.now().toEpochMilli()).coerceAtLeast(0) + 999) / 1000
    private fun clearPin() { pin.fill('\u0000'); pin = CharArray(0) }
    override fun onCleared() { onBackground(); super.onCleared() }
}
