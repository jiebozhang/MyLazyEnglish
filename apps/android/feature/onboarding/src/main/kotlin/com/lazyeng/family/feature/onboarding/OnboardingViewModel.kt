package com.lazyeng.family.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.datastore.OnboardingDraft
import com.lazyeng.family.core.security.PinCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val phase: OnboardingPhase = OnboardingPhase.RESTORING,
    val enteredCount: Int = 0,
    val nickname: String = "",
    val level: String = "Power Up 2",
    val avatar: String = "sun",
    val busy: Boolean = false,
    val message: String? = null,
    val lockUntil: Long = 0,
    val remainingSeconds: Long = 0,
)
sealed interface OnboardingUiEvent {
    data class Digit(val value: Int) : OnboardingUiEvent
    data object Backspace : OnboardingUiEvent
    data object SubmitPin : OnboardingUiEvent
    data object RestartPin : OnboardingUiEvent
    data class Nickname(val value: String) : OnboardingUiEvent
    data class Level(val value: String) : OnboardingUiEvent
    data class Avatar(val value: String) : OnboardingUiEvent
    data object CreateProfile : OnboardingUiEvent
    data object Retry : OnboardingUiEvent
    data object EnterHome : OnboardingUiEvent
}
sealed interface OnboardingUiEffect { data object EnterHome : OnboardingUiEffect }

class OnboardingViewModel(private val coordinator: OnboardingCoordinator, private val clock: Clock) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingUiState())
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<OnboardingUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val events = Channel<OnboardingUiEvent>(Channel.UNLIMITED)
    private var pin = CharArray(0)
    private var firstPin = CharArray(0)

    init {
        viewModelScope.launch {
            recover()
            for (event in events) {
                try { handle(event) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    clearSecrets()
                    mutableState.update { it.copy(phase = OnboardingPhase.ERROR, busy = false, enteredCount = 0,
                        message = "暂时无法保存或读取设置。请重试；如安全记录损坏，需清除应用数据重新初始化。") }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                mutableState.update { it.copy(remainingSeconds = remaining(it.lockUntil)) }
                delay(250)
            }
        }
    }

    fun onEvent(event: OnboardingUiEvent) {
        if (!state.value.busy) events.trySend(event)
    }
    /** Never place PIN buffers in SavedStateHandle, saved instance state, or DataStore. */
    fun onBackground() {
        clearSecrets()
        mutableState.update { it.copy(enteredCount = 0,
            phase = if (it.phase == OnboardingPhase.CONFIRM_PIN) OnboardingPhase.SET_PIN else it.phase) }
    }
    private suspend fun recover() {
        mutableState.update { it.copy(phase = OnboardingPhase.RESTORING, busy = true, message = null) }
        try {
            val restored = coordinator.restore()
            mutableState.value = OnboardingUiState(phase = restored.phase, nickname = restored.draft.nickname,
                level = restored.draft.level, avatar = restored.draft.avatar, lockUntil = restored.lockUntil,
                remainingSeconds = remaining(restored.lockUntil))
            if (restored.phase == OnboardingPhase.COMPLETE) effectChannel.send(OnboardingUiEffect.EnterHome)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(phase = OnboardingPhase.ERROR, busy = false,
                message = "无法恢复设置，请重试。安全记录损坏时需清除应用数据重新初始化。") }
        }
    }
    private suspend fun handle(event: OnboardingUiEvent) {
        val current = state.value
        when (event) {
            is OnboardingUiEvent.Digit -> if (current.phase in pinPhases && current.remainingSeconds == 0L && pin.size < 4) {
                require(event.value in 0..9)
                val updated = pin + ('0'.code + event.value).toChar()
                pin.fill('\u0000'); pin = updated
                mutableState.update { it.copy(enteredCount = pin.size, message = null) }
            }
            OnboardingUiEvent.Backspace -> if (pin.isNotEmpty()) {
                val updated = pin.copyOf(pin.size - 1)
                pin.fill('\u0000'); pin = updated
                mutableState.update { it.copy(enteredCount = pin.size) }
            }
            OnboardingUiEvent.RestartPin -> if (current.phase == OnboardingPhase.CONFIRM_PIN) {
                clearSecrets()
                mutableState.update { it.copy(phase = OnboardingPhase.SET_PIN, enteredCount = 0, message = null) }
            }
            OnboardingUiEvent.SubmitPin -> submitPin()
            is OnboardingUiEvent.Nickname -> if (current.phase == OnboardingPhase.CREATE_PROFILE) {
                updateDraft(coordinator.saveDetails(event.value.take(24), current.level, current.avatar))
            }
            is OnboardingUiEvent.Level -> if (current.phase == OnboardingPhase.CREATE_PROFILE) {
                updateDraft(coordinator.saveDetails(current.nickname, event.value, current.avatar))
            }
            is OnboardingUiEvent.Avatar -> if (current.phase == OnboardingPhase.CREATE_PROFILE) {
                updateDraft(coordinator.saveDetails(current.nickname, current.level, event.value))
            }
            OnboardingUiEvent.CreateProfile -> if (current.phase == OnboardingPhase.CREATE_PROFILE && current.nickname.isNotBlank()) {
                mutableState.update { it.copy(busy = true) }
                coordinator.finish()
                mutableState.update { it.copy(phase = OnboardingPhase.COMPLETE, busy = false) }
                effectChannel.send(OnboardingUiEffect.EnterHome)
            }
            OnboardingUiEvent.Retry -> recover()
            OnboardingUiEvent.EnterHome -> if (current.phase == OnboardingPhase.COMPLETE) effectChannel.send(OnboardingUiEffect.EnterHome)
        }
    }
    private suspend fun submitPin() {
        val phase = state.value.phase
        if (phase !in pinPhases || pin.size != 4 || remaining(state.value.lockUntil) > 0) return
        if (phase == OnboardingPhase.SET_PIN) {
            firstPin.fill('\u0000'); firstPin = pin.copyOf()
            pin.fill('\u0000'); pin = CharArray(0)
            mutableState.update { it.copy(phase = OnboardingPhase.CONFIRM_PIN, enteredCount = 0, message = null) }
            return
        }
        if (phase == OnboardingPhase.CONFIRM_PIN && !pin.contentEquals(firstPin)) {
            clearSecrets()
            mutableState.update { it.copy(phase = OnboardingPhase.SET_PIN, enteredCount = 0,
                message = "两次 PIN 不一致，请重新设置。") }
            return
        }
        val submitted = pin.copyOf()
        clearSecrets()
        mutableState.update { it.copy(busy = true, enteredCount = 0, message = null) }
        try {
            val result = if (phase == OnboardingPhase.CONFIRM_PIN) {
                coordinator.setPin(submitted); PinCheck.Accepted
            } else coordinator.verify(submitted)
            when (result) {
                PinCheck.Accepted -> mutableState.update { it.copy(phase = OnboardingPhase.CREATE_PROFILE, busy = false,
                    lockUntil = 0, remainingSeconds = 0) }
                is PinCheck.Locked -> showRejected(result.lockUntil)
                is PinCheck.Rejected -> showRejected(result.lockUntil)
            }
        } finally { submitted.fill('\u0000') }
    }
    private fun showRejected(until: Long) {
        mutableState.update { it.copy(busy = false, lockUntil = until, remainingSeconds = remaining(until),
            message = "PIN 未通过验证，请稍后重试。") }
    }
    private fun updateDraft(draft: OnboardingDraft) {
        mutableState.update { it.copy(nickname = draft.nickname, level = draft.level, avatar = draft.avatar) }
    }
    private fun remaining(until: Long) = ((until - clock.now().toEpochMilli()).coerceAtLeast(0) + 999) / 1000
    private fun clearSecrets() { pin.fill('\u0000'); firstPin.fill('\u0000'); pin = CharArray(0); firstPin = CharArray(0) }
    override fun onCleared() { clearSecrets(); events.close(); effectChannel.close() }
    companion object {
        private val pinPhases = setOf(OnboardingPhase.SET_PIN, OnboardingPhase.CONFIRM_PIN, OnboardingPhase.VERIFY_PIN)
    }
}
