package com.lazyeng.family.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.PinCheck
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class ProfilesPhase { LOADING, HOME, PICKER, PIN, OVERVIEW, EDIT, DELETE_PIN, ERROR }
data class ProfilesUiState(
    val phase: ProfilesPhase = ProfilesPhase.LOADING,
    val current: Profile? = null, val choices: List<ProfileChoice> = emptyList(),
    val members: List<Profile> = emptyList(), val editor: ProfileDraft? = null,
    val deleting: Profile? = null, val busy: Boolean = false, val enteredCount: Int = 0,
    val remainingSeconds: Long = 0, val message: String? = null,
)
sealed interface ProfilesUiEvent {
    data object Picker : ProfilesUiEvent
    data class Select(val id: ProfileId) : ProfilesUiEvent
    data object Parent : ProfilesUiEvent
    data object Back : ProfilesUiEvent
    data object Retry : ProfilesUiEvent
    data object Create : ProfilesUiEvent
    data class Edit(val id: ProfileId) : ProfilesUiEvent
    data class Delete(val id: ProfileId) : ProfilesUiEvent
    data class Draft(val value: ProfileDraft) : ProfilesUiEvent
    data object Save : ProfilesUiEvent
    data class Digit(val number: Int) : ProfilesUiEvent
    data object Erase : ProfilesUiEvent
    data object SubmitPin : ProfilesUiEvent
}
sealed interface ProfilesUiEffect { data object ProfileSelected : ProfilesUiEffect }

class ProfilesViewModel(
    private val factory: suspend () -> ProfileCoordinator,
    private val clock: Clock,
) : ViewModel() {
    private lateinit var coordinator: ProfileCoordinator
    private val mutable = MutableStateFlow(ProfilesUiState())
    val state = mutable.asStateFlow()
    private val effectChannel = Channel<ProfilesUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var operation: Job? = null
    private var pin = CharArray(0)
    private var lockUntil = 0L

    init {
        restore()
        viewModelScope.launch {
            while (isActive) {
                if (::coordinator.isInitialized && state.value.phase in protectedPhases && !coordinator.parent.authorized()) {
                    onBackground()
                }
                mutable.update { it.copy(remainingSeconds = remaining()) }
                delay(250)
            }
        }
    }
    private fun restore() = work {
        coordinator = factory()
        val current = coordinator.current()
        currentCoroutineContext().ensureActive()
        if (current == null) picker() else mutable.value = ProfilesUiState(ProfilesPhase.HOME, current = current)
    }
    private fun remaining() = ((lockUntil - clock.now().toEpochMilli()).coerceAtLeast(0) + 999) / 1000
    private fun clearPin() { pin.fill('\u0000'); pin = CharArray(0) }
    private fun work(block: suspend () -> Unit) {
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                clearPin()
                if (::coordinator.isInitialized) coordinator.parent.revoke()
                mutable.value = ProfilesUiState(ProfilesPhase.ERROR, message = "暂时无法完成操作，请重新读取档案。")
            }
        }
    }
    private fun loading() { clearPin(); mutable.value = ProfilesUiState(busy = true) }
    private suspend fun picker() {
        val choices = coordinator.choices()
        currentCoroutineContext().ensureActive()
        mutable.value = ProfilesUiState(ProfilesPhase.PICKER, choices = choices)
    }
    private suspend fun overview() {
        val members = coordinator.overview()
        currentCoroutineContext().ensureActive()
        mutable.value = ProfilesUiState(ProfilesPhase.OVERVIEW, members = members)
    }
    fun onBackground() {
        if (!::coordinator.isInitialized) return
        coordinator.parent.revoke()
        clearPin()
        if (state.value.phase != ProfilesPhase.HOME && state.value.phase != ProfilesPhase.PICKER) {
            operation?.cancel()
            loading()
            work { picker() }
        }
    }
    fun onEvent(event: ProfilesUiEvent) {
        if (event == ProfilesUiEvent.Retry && !::coordinator.isInitialized && operation?.isActive != true) {
            loading(); restore(); return
        }
        if (!::coordinator.isInitialized) return
        if (event == ProfilesUiEvent.Back) {
            operation?.cancel()
            coordinator.parent.revoke()
            loading()
            work { picker() }
            return
        }
        if (operation?.isActive == true || state.value.busy) return
        if (state.value.phase in protectedPhases && !coordinator.parent.authorized()) { onBackground(); return }
        coordinator.parent.touch()
        when (event) {
            ProfilesUiEvent.Picker, ProfilesUiEvent.Retry -> {
                coordinator.parent.revoke(); loading(); work { picker() }
            }
            is ProfilesUiEvent.Select -> if (state.value.phase == ProfilesPhase.PICKER) {
                // Synchronous clearing before any suspension: no prior profile frame is retained.
                loading()
                work {
                    val selected = coordinator.switchTo(event.id)
                    currentCoroutineContext().ensureActive()
                    mutable.value = ProfilesUiState(ProfilesPhase.HOME, current = selected)
                    effectChannel.send(ProfilesUiEffect.ProfileSelected)
                }
            }
            ProfilesUiEvent.Parent -> {
                coordinator.parent.revoke(); loading()
                work {
                    lockUntil = coordinator.parent.status().lockUntil
                    mutable.value = ProfilesUiState(ProfilesPhase.PIN, remainingSeconds = remaining())
                }
            }
            ProfilesUiEvent.Create -> if (state.value.phase == ProfilesPhase.OVERVIEW) {
                loading(); work { mutable.value = ProfilesUiState(ProfilesPhase.EDIT, editor = coordinator.editor(null)) }
            }
            is ProfilesUiEvent.Edit -> if (state.value.phase == ProfilesPhase.OVERVIEW) {
                loading(); work { mutable.value = ProfilesUiState(ProfilesPhase.EDIT, editor = coordinator.editor(event.id)) }
            }
            is ProfilesUiEvent.Delete -> if (state.value.phase == ProfilesPhase.OVERVIEW) {
                val target = state.value.members.singleOrNull { it.id == event.id } ?: return
                clearPin()
                mutable.value = ProfilesUiState(ProfilesPhase.DELETE_PIN, deleting = target)
            }
            is ProfilesUiEvent.Draft -> if (state.value.phase == ProfilesPhase.EDIT) {
                val original = state.value.editor!!
                if (event.value.id == original.id) mutable.update { it.copy(editor = event.value.copy(nickname = event.value.nickname.take(24))) }
            }
            ProfilesUiEvent.Save -> if (state.value.phase == ProfilesPhase.EDIT) {
                val draft = state.value.editor!!
                loading(); work { coordinator.save(draft); overview() }
            }
            is ProfilesUiEvent.Digit -> if (state.value.phase in pinPhases && remaining() == 0L && pin.size < 4) {
                require(event.number in 0..9)
                val next = pin + ('0'.code + event.number).toChar()
                clearPin(); pin = next; mutable.update { it.copy(enteredCount = pin.size, message = null) }
            }
            ProfilesUiEvent.Erase -> if (pin.isNotEmpty()) {
                val next = pin.copyOf(pin.size - 1)
                clearPin(); pin = next; mutable.update { it.copy(enteredCount = pin.size) }
            }
            ProfilesUiEvent.SubmitPin -> if (state.value.phase in pinPhases && remaining() == 0L && pin.size == 4) {
                val target = state.value.deleting?.id
                val input = pin.copyOf()
                clearPin()
                mutable.update { it.copy(busy = true, enteredCount = 0, message = null) }
                work {
                    try {
                        val result = if (target == null) coordinator.parent.unlock(input) else coordinator.delete(target, input)
                        when (result) {
                            PinCheck.Accepted -> { lockUntil = 0; overview() }
                            is PinCheck.Rejected -> rejected(result.lockUntil)
                            is PinCheck.Locked -> rejected(result.lockUntil)
                        }
                    } finally { input.fill('\u0000') }
                }
            }
            else -> Unit
        }
    }
    private fun rejected(until: Long) {
        lockUntil = until
        mutable.update { it.copy(busy = false, remainingSeconds = remaining(), message = "PIN 未通过验证，请重试。") }
    }
    override fun onCleared() { if (::coordinator.isInitialized) coordinator.parent.revoke(); clearPin(); effectChannel.close() }
    companion object {
        private val protectedPhases = setOf(ProfilesPhase.OVERVIEW, ProfilesPhase.EDIT, ProfilesPhase.DELETE_PIN)
        private val pinPhases = setOf(ProfilesPhase.PIN, ProfilesPhase.DELETE_PIN)
    }
}
