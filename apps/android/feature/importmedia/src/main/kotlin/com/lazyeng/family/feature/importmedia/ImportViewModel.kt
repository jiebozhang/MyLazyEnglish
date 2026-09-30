package com.lazyeng.family.feature.importmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.SubtitleCandidate
import com.lazyeng.family.core.common.subtitle.SubtitleEncoding
import com.lazyeng.family.core.model.FileReference
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.core.security.PinCheck
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ImportSession(val parent: ParentSession, val coordinator: VideoImportCoordinator,
    val initialVideo: FileReference? = null, val repairVideoId: VideoId? = null)
data class ImportScreenState(val initialized: Boolean = false, val authorized: Boolean = false,
    val busy: Boolean = false, val enteredCount: Int = 0, val remainingSeconds: Long = 0,
    val videoSelected: Boolean = false, val subtitleSelected: Boolean = false, val rights: Boolean = false,
    val message: String? = null, val import: ImportUiState = ImportUiState(),
    val directorySelected: Boolean = false, val selectedSubtitleName: String? = null,
    val subtitleEncoding: SubtitleEncoding? = null, val subtitleOffsetMs: Long = 0, val repairVideoId: VideoId? = null)
sealed interface ImportUiEvent {
    data class Digit(val value: Int) : ImportUiEvent
    data object Erase : ImportUiEvent
    data object Verify : ImportUiEvent
    data class Choose(val subtitle: Boolean) : ImportUiEvent
    data object ChooseDirectory : ImportUiEvent
    data class Selected(val subtitle: Boolean, val reference: FileReference?) : ImportUiEvent
    data class DirectorySelected(val reference: FileReference?) : ImportUiEvent
    data class SelectCandidate(val candidate: SubtitleCandidate) : ImportUiEvent
    data class Encoding(val value: SubtitleEncoding?) : ImportUiEvent
    data class Offset(val valueMs: Long) : ImportUiEvent
    data object ContinueWithoutSubtitle : ImportUiEvent
    data object Inspect : ImportUiEvent
    data class Rights(val value: Boolean) : ImportUiEvent
    data class Confirm(val choice: DuplicateChoice) : ImportUiEvent
    data object RollbackSubtitle : ImportUiEvent
    data object Reset : ImportUiEvent
}
sealed interface ImportUiEffect {
    data class OpenDocument(val subtitle: Boolean) : ImportUiEffect
    data object OpenDirectory : ImportUiEffect
}

class ImportViewModel(private val factory: suspend () -> ImportSession, private val clock: Clock) : ViewModel() {
    private val mutable = MutableStateFlow(ImportScreenState())
    val state = mutable.asStateFlow()
    private val channel = Channel<ImportUiEffect>(Channel.BUFFERED)
    val effects = channel.receiveAsFlow()
    private var session: ImportSession? = null
    private var operation: Job? = null
    private var pickerPending = false
    private var video: FileReference? = null
    private var subtitle: FileReference? = null
    private var directory: FileReference? = null
    private var selectedSubtitleName: String? = null
    private var pin = CharArray(0)
    private var lockUntil = 0L

    init {
        // Navigation may clear the ViewModel before the route's disposal callback can launch work.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { session?.coordinator?.cancel() } }
        }
        operation = viewModelScope.launch {
            try {
                val loaded = factory()
                session = loaded
                lockUntil = loaded.parent.status().lockUntil
                mutable.value = ImportScreenState(initialized = true)
                viewModelScope.launch { loaded.coordinator.state.collect { value -> mutable.update { it.copy(import = value) } } }
                viewModelScope.launch {
                    while (isActive) {
                        mutable.update { it.copy(remainingSeconds = ((lockUntil - clock.now().toEpochMilli()).coerceAtLeast(0) + 999) / 1000) }
                        if (state.value.authorized && !loaded.parent.authorized() && !pickerPending) onBackground()
                        delay(250)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = ImportScreenState(message = "请先选择有效档案，再重新打开导入。") }
        }
    }
    fun onEvent(event: ImportUiEvent) {
        val current = session ?: return
        if (event is ImportUiEvent.Selected || event is ImportUiEvent.DirectorySelected) pickerPending = false
        if (event == ImportUiEvent.Reset) {
            val previous = operation
            operation = viewModelScope.launch {
                previous?.cancelAndJoin()
                current.coordinator.cancel()
                video = null; subtitle = null; directory = null; selectedSubtitleName = null
                mutable.update { it.copy(videoSelected = false, subtitleSelected = false, rights = false, busy = false,
                    message = null, directorySelected = false, selectedSubtitleName = null, subtitleEncoding = null, subtitleOffsetMs = 0) }
            }
            return
        }
        if (state.value.busy) return
        if (event is ImportUiEvent.Digit && !state.value.authorized && event.value in 0..9 && pin.size < 4) {
            val next = pin + ('0' + event.value); clearPin(); pin = next
            mutable.update { it.copy(enteredCount = pin.size, message = null) }; return
        }
        if (event == ImportUiEvent.Erase && !state.value.authorized) {
            val next = pin.dropLast(1).toCharArray(); clearPin(); pin = next
            mutable.update { it.copy(enteredCount = pin.size) }; return
        }
        if (event == ImportUiEvent.Verify && !state.value.authorized && pin.size == 4 && state.value.remainingSeconds == 0L) {
            val candidate = pin.copyOf(); clearPin()
            work {
                try {
                    val result = current.parent.unlock(candidate)
                    lockUntil = current.parent.status().lockUntil
                    if (result == PinCheck.Accepted && video == null) video = current.initialVideo
                    mutable.update { it.copy(authorized = result == PinCheck.Accepted, enteredCount = 0,
                        videoSelected = video != null,
                        repairVideoId = current.repairVideoId,
                        message = if (result == PinCheck.Accepted) null else "PIN 不正确，请重试。") }
                } finally { candidate.fill('\u0000') }
            }
            return
        }
        if (!current.parent.authorized()) { onBackground(); return }
        current.parent.touch()
        when (event) {
            is ImportUiEvent.Choose -> if (!state.value.busy && state.value.import.phase in setOf(ImportPhase.SELECT, ImportPhase.PREVIEW)) {
                pickerPending = true
                channel.trySend(ImportUiEffect.OpenDocument(event.subtitle))
            }
            ImportUiEvent.ChooseDirectory -> if (!state.value.busy && state.value.import.phase in setOf(ImportPhase.SELECT, ImportPhase.PREVIEW) && video != null) {
                pickerPending = true
                channel.trySend(ImportUiEffect.OpenDirectory)
            }
            is ImportUiEvent.Selected -> {
                pickerPending = false
                if (event.subtitle) { subtitle = event.reference; selectedSubtitleName = null } else video = event.reference
                mutable.update { it.copy(videoSelected = video != null, subtitleSelected = subtitle != null,
                    selectedSubtitleName = selectedSubtitleName) }
            }
            is ImportUiEvent.DirectorySelected -> {
                directory = event.reference
                subtitle = null; selectedSubtitleName = null
                mutable.update { it.copy(directorySelected = directory != null, subtitleSelected = false, selectedSubtitleName = null) }
            }
            is ImportUiEvent.SelectCandidate -> {
                subtitle = event.candidate.reference; selectedSubtitleName = event.candidate.displayName
                mutable.update { it.copy(subtitleSelected = true, selectedSubtitleName = selectedSubtitleName) }
            }
            is ImportUiEvent.Encoding -> mutable.update { it.copy(subtitleEncoding = event.value) }
            is ImportUiEvent.Offset -> mutable.update { it.copy(subtitleOffsetMs = event.valueMs.coerceIn(-5_000, 5_000)) }
            ImportUiEvent.ContinueWithoutSubtitle -> {
                subtitle = null; selectedSubtitleName = null
                mutable.update { it.copy(subtitleSelected = false, selectedSubtitleName = null, subtitleOffsetMs = 0) }
                work { current.coordinator.prepare(video, null, null, directory, 0) }
            }
            ImportUiEvent.Inspect -> work { current.coordinator.prepare(video, subtitle, state.value.subtitleEncoding,
                directory, state.value.subtitleOffsetMs) }
            is ImportUiEvent.Rights -> mutable.update { it.copy(rights = event.value) }
            is ImportUiEvent.Confirm -> work {
                current.coordinator.confirm(resolveImportChoice(event.choice, current.repairVideoId), state.value.rights)
            }
            ImportUiEvent.RollbackSubtitle -> work { current.coordinator.rollbackLastSubtitlePublication() }
            else -> Unit
        }
    }
    fun onBackground() {
        if (pickerPending) return // The external document picker itself temporarily backgrounds the app.
        val current = session ?: return
        current.parent.revoke()
        clearPin()
        video = null; subtitle = null; directory = null; selectedSubtitleName = null
        mutable.value = ImportScreenState(initialized = true)
        val previous = operation
        operation = viewModelScope.launch { previous?.cancelAndJoin(); current.coordinator.cancel() }
    }
    private fun work(block: suspend () -> Unit) {
        mutable.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(message = "操作失败，请重新选择文件或验证 PIN。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun clearPin() { pin.fill('\u0000'); pin = CharArray(0) }
    override fun onCleared() { clearPin(); session?.parent?.revoke(); super.onCleared() }
}

/** A detail repair must update the selected video, never create or silently select a duplicate. */
internal fun resolveImportChoice(requested: DuplicateChoice, repairVideoId: VideoId?): DuplicateChoice =
    repairVideoId?.let { DuplicateChoice.Reuse(it) } ?: requested
