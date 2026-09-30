package com.lazyeng.family.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.core.security.PinCheck
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class VideoDetailSession(val data: VideoDetailData, val parent: ParentSession)
data class VideoDetailState(val loading: Boolean = true, val child: ChildVideoDetail? = null,
    val parent: ParentVideoDetail? = null, val authorized: Boolean = false, val enteredCount: Int = 0,
    val remainingSeconds: Long = 0, val message: String? = null, val archiveConfirmation: Boolean = false)
sealed interface VideoDetailEvent {
    data object Refresh : VideoDetailEvent
    data object Watch : VideoDetailEvent
    data object Repair : VideoDetailEvent
    data object RequestArchive : VideoDetailEvent
    data object ConfirmArchive : VideoDetailEvent
    data object CancelArchive : VideoDetailEvent
    data class Digit(val digit: Int) : VideoDetailEvent
    data object Erase : VideoDetailEvent
    data object Verify : VideoDetailEvent
}
sealed interface VideoDetailEffect {
    data class Watch(val videoId: VideoId, val positionMs: Long) : VideoDetailEffect
    data class Repair(val videoId: VideoId) : VideoDetailEffect
}

class VideoDetailViewModel(private val factory: suspend () -> VideoDetailSession,
    private val parentView: Boolean, private val clock: Clock,
    invalidations: Flow<Unit> = emptyFlow()) : ViewModel() {
    private val mutable = MutableStateFlow(VideoDetailState())
    val state = mutable.asStateFlow()
    private val channel = Channel<VideoDetailEffect>(Channel.BUFFERED)
    val effects = channel.receiveAsFlow()
    private var session: VideoDetailSession? = null
    private var operation: Job? = null
    private var pin = CharArray(0)
    private var lockUntil = 0L

    init {
        viewModelScope.launch { invalidations.collect { onBackground() } }
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(250)
                mutable.update { it.copy(remainingSeconds = ((lockUntil - clock.now().toEpochMilli()).coerceAtLeast(0) + 999) / 1000) }
                if (state.value.authorized && session?.parent?.authorized() != true) onBackground()
            }
        }
    }
    fun onBackground() {
        operation?.cancel()
        session?.parent?.revoke()
        clearPin()
        // Never retain a parent's source/diagnostic projection behind the gate or in a stopped route.
        mutable.value = VideoDetailState(loading = false)
    }
    private fun refresh() = run {
        val current = session ?: factory().also { session = it }
        if (!current.data.validScope()) { mutable.value = VideoDetailState(loading = false, message = "档案已变化，请返回重新打开。"); return@run }
        if (parentView) {
            lockUntil = current.parent.status().lockUntil
            if (!current.parent.authorized()) { mutable.value = VideoDetailState(loading = false); return@run }
            val scope = current.parent.requireScope()
            val value = current.data.parent(scope)
            current.parent.checkScope(scope)
            mutable.value = VideoDetailState(loading = false, parent = value, authorized = true,
                message = if (value == null) "找不到这个视频，请返回。" else null)
        } else {
            val value = current.data.child()
            mutable.value = VideoDetailState(loading = false, child = value,
                message = if (value == null) "找不到这个视频，请返回。" else null)
        }
    }
    fun onEvent(event: VideoDetailEvent) {
        if (event == VideoDetailEvent.Refresh) { refresh(); return }
        if (state.value.loading) return
        val current = session ?: return
        if (event is VideoDetailEvent.Digit && event.digit in 0..9 && !state.value.authorized && pin.size < 4) {
            val next = pin + ('0' + event.digit); clearPin(); pin = next
            mutable.update { it.copy(enteredCount = pin.size, message = null) }; return
        }
        if (event == VideoDetailEvent.Erase) {
            val next = pin.dropLast(1).toCharArray(); clearPin(); pin = next
            mutable.update { it.copy(enteredCount = pin.size) }; return
        }
        if (event == VideoDetailEvent.Verify && parentView && pin.size == 4 && state.value.remainingSeconds == 0L) {
            val candidate = pin.copyOf(); clearPin()
            run {
                try {
                    val result = current.parent.unlock(candidate)
                    lockUntil = current.parent.status().lockUntil
                    if (result == PinCheck.Accepted) {
                        val scope = current.parent.requireScope()
                        val detail = current.data.parent(scope)
                        current.parent.checkScope(scope)
                        mutable.value = VideoDetailState(loading = false, authorized = true, parent = detail,
                            message = if (detail == null) "找不到这个视频，请返回。" else null)
                    } else mutable.value = VideoDetailState(loading = false, message = "PIN 不正确，请重试。")
                } finally { candidate.fill('\u0000') }
            }
            return
        }
        if (parentView && !current.parent.authorized()) { onBackground(); return }
        when (event) {
            VideoDetailEvent.Watch -> if (!parentView) run {
                val latest = current.data.child()
                mutable.value = VideoDetailState(loading = false, child = latest)
                if (latest?.canWatch == true) channel.send(VideoDetailEffect.Watch(current.data.videoId, latest.positionMs))
            }
            VideoDetailEvent.Repair -> if (parentView && state.value.parent?.canRepairSubtitle == true) run {
                current.parent.requireScope()
                if (current.data.validScope()) channel.send(VideoDetailEffect.Repair(current.data.videoId))
            }
            VideoDetailEvent.RequestArchive -> if (parentView && state.value.parent?.status == com.lazyeng.family.core.model.VideoStatus.READY) {
                current.parent.touch()
                mutable.update { it.copy(archiveConfirmation = true) }
            }
            VideoDetailEvent.CancelArchive -> mutable.update { it.copy(archiveConfirmation = false) }
            VideoDetailEvent.ConfirmArchive -> if (parentView && state.value.archiveConfirmation) run {
                val scope = current.parent.requireScope()
                when (current.data.archive()) {
                    is AppResult.Success -> {
                        val detail = current.data.parent(scope)
                        current.parent.checkScope(scope)
                        mutable.value = VideoDetailState(loading = false, authorized = true, parent = detail)
                    }
                    is AppResult.Failure -> mutable.update { it.copy(message = "无法归档，请刷新状态后重试。", archiveConfirmation = false) }
                }
            }
            else -> Unit
        }
    }
    private fun run(block: suspend () -> Unit) {
        operation?.cancel()
        mutable.update { it.copy(loading = true, message = null, enteredCount = 0) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = VideoDetailState(loading = false, message = "暂时无法读取，请返回或重试。") }
            finally { if (isActive) mutable.update { it.copy(loading = false) } }
        }
    }
    private fun clearPin() { pin.fill('\u0000'); pin = CharArray(0) }
    override fun onCleared() { clearPin(); session?.parent?.revoke(); super.onCleared() }
}
