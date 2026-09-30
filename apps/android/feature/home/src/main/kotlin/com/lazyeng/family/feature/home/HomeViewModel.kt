package com.lazyeng.family.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.VideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String, val canRetry: Boolean = true) : HomeUiState
    data class Empty(
        val profile: HomeProfileHeader,
        val message: String = "还没有可看的视频，请让家长在控制台导入视频。",
    ) : HomeUiState
    data class Content(val dashboard: HomeDashboardData) : HomeUiState
}

sealed interface HomeUiEvent {
    data object Refresh : HomeUiEvent
    data class ContinueWatchingClicked(val videoId: VideoId, val positionMs: Long) : HomeUiEvent
    data class VideoCardClicked(val videoId: VideoId) : HomeUiEvent
    data object VocabularyClicked : HomeUiEvent
    data object FirstVideoGuidanceClicked : HomeUiEvent
    data object SwitchProfileClicked : HomeUiEvent
    data object ParentConsoleClicked : HomeUiEvent
}

sealed interface HomeUiEffect {
    data class NavigateToPlayer(val videoId: VideoId, val positionMs: Long) : HomeUiEffect
    data class NavigateToVideoDetail(val videoId: VideoId) : HomeUiEffect
    data object NavigateToVocabulary : HomeUiEffect
    data object NavigateToLibrary : HomeUiEffect
    data object NavigateToProfiles : HomeUiEffect
    data object NavigateToParent : HomeUiEffect
}

class HomeViewModel(
    private val factory: suspend () -> HomeSession,
    private val clock: Clock,
    invalidations: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _effects = Channel<HomeUiEffect>(Channel.BUFFERED)
    val effects: Flow<HomeUiEffect> = _effects.receiveAsFlow()

    private var session: HomeSession? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            invalidations.collect {
                session = null
                refresh()
            }
        }
        refresh()
    }

    fun onEvent(event: HomeUiEvent) {
        when (event) {
            HomeUiEvent.Refresh -> refresh()
            is HomeUiEvent.ContinueWatchingClicked -> {
                viewModelScope.launch {
                    _effects.send(HomeUiEffect.NavigateToPlayer(event.videoId, event.positionMs))
                }
            }
            is HomeUiEvent.VideoCardClicked -> {
                viewModelScope.launch {
                    _effects.send(HomeUiEffect.NavigateToVideoDetail(event.videoId))
                }
            }
            HomeUiEvent.VocabularyClicked -> {
                viewModelScope.launch {
                    _effects.send(HomeUiEffect.NavigateToVocabulary)
                }
            }
            HomeUiEvent.FirstVideoGuidanceClicked -> {
                viewModelScope.launch {
                    _effects.send(HomeUiEffect.NavigateToLibrary)
                }
            }
            HomeUiEvent.SwitchProfileClicked -> {
                viewModelScope.launch {
                    _effects.send(HomeUiEffect.NavigateToProfiles)
                }
            }
            HomeUiEvent.ParentConsoleClicked -> {
                viewModelScope.launch {
                    _effects.send(HomeUiEffect.NavigateToParent)
                }
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        _state.value = HomeUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val current = session ?: factory().also { session = it }
                if (!current.data.validScope()) {
                    _state.value = HomeUiState.Error("当前档案已发生变化，请重新切换或重试。")
                    return@launch
                }
                val dashboard = current.data.loadDashboard()
                if (dashboard == null) {
                    _state.value = HomeUiState.Error("无法读取当前档案信息，请返回后重试。")
                } else if (dashboard.recommendations.isEmpty() && dashboard.continueWatching == null) {
                    _state.value = HomeUiState.Empty(dashboard.profile)
                } else {
                    _state.value = HomeUiState.Content(dashboard)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _state.value = HomeUiState.Error("加载首页数据失败：${e.localizedMessage ?: "未知错误"}")
            }
        }
    }
}
