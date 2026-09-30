package com.lazyeng.family.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class MineProfileInfo(
    val nickname: String,
    val avatarId: String?,
    val ageMode: String,
    val englishLevel: String,
    val streakDays: Int,
    val streakMessage: String,
)

sealed interface MineUiState {
    data object Loading : MineUiState
    data class Content(val profile: MineProfileInfo) : MineUiState
    data class Error(val message: String) : MineUiState
}

sealed interface MineUiEvent {
    data object Refresh : MineUiEvent
    data object SwitchProfile : MineUiEvent
    data object ParentSettings : MineUiEvent
}

sealed interface MineUiEffect {
    data object NavigateToProfiles : MineUiEffect
    data object NavigateToParentGate : MineUiEffect
}

class MineViewModel(
    private val loadProfile: suspend () -> MineProfileInfo?,
    invalidations: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val mutableState = MutableStateFlow<MineUiState>(MineUiState.Loading)
    val state = mutableState.asStateFlow()

    private val effectChannel = Channel<MineUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { invalidations.collect { refresh() } }
        refresh()
    }

    fun onEvent(event: MineUiEvent) {
        when (event) {
            MineUiEvent.Refresh -> refresh()
            MineUiEvent.SwitchProfile -> emit(MineUiEffect.NavigateToProfiles)
            MineUiEvent.ParentSettings -> emit(MineUiEffect.NavigateToParentGate)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        mutableState.value = MineUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val profile = loadProfile()
                mutableState.value = profile?.let(MineUiState::Content)
                    ?: MineUiState.Error("当前档案不可用，请重新选择档案。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = MineUiState.Error("暂时无法读取档案信息，请重试。")
            }
        }
    }

    private fun emit(effect: MineUiEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }

    override fun onCleared() {
        effectChannel.close()
    }
}
