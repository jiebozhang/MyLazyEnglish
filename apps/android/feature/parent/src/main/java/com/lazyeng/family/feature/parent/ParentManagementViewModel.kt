package com.lazyeng.family.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.security.PinCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ParentManagementSection { MEMBERS, CONTENT, STORAGE }

data class ManagedVideoRow(
    val id: VideoId,
    val title: String,
    val sourceName: String,
    val durationMs: Long?,
    val bytes: Long,
    val visibleToChildren: Boolean,
    val status: String,
)

data class StorageUsage(val videosBytes: Long, val dictionaryAndDatabaseBytes: Long, val cacheBytes: Long, val freeBytes: Long)

data class ParentManagementSnapshot(
    val members: List<Profile> = emptyList(),
    val videos: List<ManagedVideoRow> = emptyList(),
    val storage: StorageUsage? = null,
)

interface ParentManagementGateway {
    suspend fun load(section: ParentManagementSection): ParentManagementSnapshot
    suspend fun createMember(nickname: String, level: String, avatarId: String)
    suspend fun deleteMember(profileId: ProfileId, pin: CharArray): PinCheck
    suspend fun setVideoVisible(videoId: VideoId, visible: Boolean)
    suspend fun deleteVideo(videoId: VideoId)
    suspend fun clearCache()
    suspend fun exportBackupJson(): String
    suspend fun saveBackupJson(json: String): String
}

sealed interface ParentManagementConfirmation {
    data class DeleteMember(val profile: Profile) : ParentManagementConfirmation
    data class DeleteVideo(val video: ManagedVideoRow) : ParentManagementConfirmation
    data object ClearCache : ParentManagementConfirmation
    data object MemberPin : ParentManagementConfirmation
}

data class ParentManagementUiState(
    val section: ParentManagementSection,
    val loading: Boolean = true,
    val working: Boolean = false,
    val snapshot: ParentManagementSnapshot = ParentManagementSnapshot(),
    val confirmation: ParentManagementConfirmation? = null,
    val pendingMemberId: ProfileId? = null,
    val message: String? = null,
    val error: String? = null,
)

sealed interface ParentManagementUiEvent {
    data object Back : ParentManagementUiEvent
    data object Refresh : ParentManagementUiEvent
    data class CreateMember(val nickname: String, val level: String, val avatarId: String) : ParentManagementUiEvent
    data class RequestDeleteMember(val id: ProfileId) : ParentManagementUiEvent
    data class RequestDeleteVideo(val id: VideoId) : ParentManagementUiEvent
    data class SetVideoVisible(val id: VideoId, val visible: Boolean) : ParentManagementUiEvent
    data object RequestClearCache : ParentManagementUiEvent
    data object Confirm : ParentManagementUiEvent
    data object DismissConfirmation : ParentManagementUiEvent
    data class SubmitDeletePin(val pin: CharArray) : ParentManagementUiEvent
    data object ExportBackup : ParentManagementUiEvent
}

sealed interface ParentManagementUiEffect {
    data object Back : ParentManagementUiEffect
}

class ParentManagementViewModel(
    private val gateway: ParentManagementGateway,
    private val dispatchers: DispatcherProvider,
    section: ParentManagementSection,
    private val onMemberDeleted: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(ParentManagementUiState(section))
    val state = mutable.asStateFlow()
    private val effectChannel = Channel<ParentManagementUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { refresh() }

    fun onEvent(event: ParentManagementUiEvent) {
        when (event) {
            ParentManagementUiEvent.Back -> emit(ParentManagementUiEffect.Back)
            ParentManagementUiEvent.Refresh -> refresh()
            is ParentManagementUiEvent.CreateMember -> createMember(event)
            is ParentManagementUiEvent.RequestDeleteMember -> requestDeleteMember(event.id)
            is ParentManagementUiEvent.RequestDeleteVideo -> {
                val row = state.value.snapshot.videos.firstOrNull { it.id == event.id } ?: return
                mutable.value = state.value.copy(confirmation = ParentManagementConfirmation.DeleteVideo(row), error = null)
            }
            is ParentManagementUiEvent.SetVideoVisible -> launchWork {
                gateway.setVideoVisible(event.id, event.visible)
                refreshSnapshot("孩子端可见状态已更新。")
            }
            ParentManagementUiEvent.RequestClearCache -> mutable.value = state.value.copy(
                confirmation = ParentManagementConfirmation.ClearCache, error = null,
            )
            ParentManagementUiEvent.Confirm -> confirm()
            ParentManagementUiEvent.DismissConfirmation -> mutable.value = state.value.copy(confirmation = null, pendingMemberId = null)
            is ParentManagementUiEvent.SubmitDeletePin -> deleteMember(event.pin)
            ParentManagementUiEvent.ExportBackup -> exportBackup()
        }
    }

    private fun refresh() {
        mutable.value = state.value.copy(loading = true, error = null)
        launchWork { refreshSnapshot(null) }
    }

    private suspend fun refreshSnapshot(message: String?) {
        val snapshot = withContext(dispatchers.io) { gateway.load(state.value.section) }
        mutable.value = state.value.copy(loading = false, working = false, snapshot = snapshot,
            confirmation = null, pendingMemberId = null, message = message, error = null)
    }

    private fun createMember(event: ParentManagementUiEvent.CreateMember) {
        val nickname = event.nickname.trim()
        if (nickname.isEmpty() || nickname.length > 24) {
            mutable.value = state.value.copy(error = "昵称需为 1 到 24 个字符。")
            return
        }
        launchWork { gateway.createMember(nickname, event.level, event.avatarId); refreshSnapshot("孩子档案已添加。") }
    }

    private fun requestDeleteMember(id: ProfileId) {
        val snapshot = state.value.snapshot
        val profile = snapshot.members.firstOrNull { it.id == id } ?: return
        if (snapshot.members.count { it.role.name == "CHILD" && it.deletedAt == null } <= 1) {
            mutable.value = state.value.copy(error = "家庭至少需保留一名学习者，无法删除。")
            return
        }
        mutable.value = state.value.copy(confirmation = ParentManagementConfirmation.DeleteMember(profile), error = null)
    }

    private fun confirm() {
        when (val confirmation = state.value.confirmation) {
            is ParentManagementConfirmation.DeleteMember -> mutable.value = state.value.copy(
                confirmation = ParentManagementConfirmation.MemberPin, pendingMemberId = confirmation.profile.id,
            )
            is ParentManagementConfirmation.DeleteVideo -> launchWork {
                gateway.deleteVideo(confirmation.video.id)
                refreshSnapshot("视频已从家庭目录移除；系统文件选择器中的源文件不会被删除。")
            }
            ParentManagementConfirmation.ClearCache -> launchWork {
                gateway.clearCache()
                refreshSnapshot("临时缓存已清理。")
            }
            ParentManagementConfirmation.MemberPin, null -> Unit
        }
    }

    private fun deleteMember(pin: CharArray) {
        val id = state.value.pendingMemberId ?: run { pin.fill('\u0000'); return }
        mutable.value = state.value.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                when (val result = withContext(dispatchers.io) { gateway.deleteMember(id, pin) }) {
                    PinCheck.Accepted -> {
                        onMemberDeleted()
                        refreshSnapshot("孩子档案及其私有学习数据已删除。")
                    }
                    is PinCheck.Rejected, is PinCheck.Locked -> mutable.value = state.value.copy(
                        working = false, error = "PIN 验证未通过，请重试。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(working = false, error = "未能删除档案，请重新验证后重试。")
            } finally { pin.fill('\u0000') }
        }
    }

    private fun exportBackup() = launchWork {
        val json = withContext(dispatchers.io) { gateway.exportBackupJson() }
        val location = withContext(dispatchers.io) { gateway.saveBackupJson(json) }
        refreshSnapshot("家庭 JSON 备份已保存：$location")
    }

    private fun launchWork(block: suspend () -> Unit) {
        mutable.value = state.value.copy(working = true, error = null)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(working = false, loading = false, error = "操作失败，请检查授权与可用存储后重试。") }
        }
    }

    private fun emit(effect: ParentManagementUiEffect) { viewModelScope.launch { effectChannel.send(effect) } }
    override fun onCleared() { effectChannel.close() }
}
