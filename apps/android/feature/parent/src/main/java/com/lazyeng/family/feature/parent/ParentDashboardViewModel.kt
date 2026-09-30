package com.lazyeng.family.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParentDashboardViewModel(
    profiles: List<Profile>,
    initialProfileId: ProfileId?,
    private val source: ParentDashboardDataSource,
    private val dispatchers: DispatcherProvider,
    private val onInteraction: () -> Unit = {},
) : ViewModel() {
    private var members = profiles.filter { it.deletedAt == null && it.role == com.lazyeng.family.core.model.ProfileRole.CHILD }
    private var selectedId = initialProfileId?.takeIf { id -> members.any { it.id == id } } ?: members.firstOrNull()?.id
    private var loadJob: Job? = null
    private val mutableState = MutableStateFlow<ParentDashboardUiState>(ParentDashboardUiState.Loading)
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<ParentDashboardUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { refresh(selectedId) }

    fun onEvent(event: ParentDashboardUiEvent) {
        onInteraction()
        when (event) {
            is ParentDashboardUiEvent.SelectProfile -> if (members.any { it.id == event.profileId }) refresh(event.profileId)
            ParentDashboardUiEvent.Refresh -> refresh((mutableState.value as? ParentDashboardUiState.Content)?.selectedProfileId ?: selectedId)
            ParentDashboardUiEvent.ImportVideo -> emit(ParentDashboardUiEffect.ImportVideo)
            ParentDashboardUiEvent.TimeSettings -> emit(ParentDashboardUiEffect.NotAvailableYet("时长与防沉迷设置"))
            ParentDashboardUiEvent.ContentManagement -> emit(ParentDashboardUiEffect.OpenContentManagement)
            ParentDashboardUiEvent.MemberManagement -> emit(ParentDashboardUiEffect.OpenMemberManagement)
            ParentDashboardUiEvent.StorageManagement -> emit(ParentDashboardUiEffect.OpenStorageManagement)
            ParentDashboardUiEvent.ChangePin -> emit(ParentDashboardUiEffect.NotAvailableYet("修改家长 PIN"))
            ParentDashboardUiEvent.LlmSettings -> emit(ParentDashboardUiEffect.OpenLlmSettings)
        }
    }

    fun refreshAuthorizedProfiles(profiles: List<Profile>, preferredProfileId: ProfileId?) {
        members = profiles.filter { it.deletedAt == null && it.role == com.lazyeng.family.core.model.ProfileRole.CHILD }
        selectedId = preferredProfileId?.takeIf { id -> members.any { it.id == id } }
            ?: selectedId?.takeIf { id -> members.any { it.id == id } }
            ?: members.firstOrNull()?.id
        refresh(selectedId)
    }

    private fun emit(effect: ParentDashboardUiEffect) { viewModelScope.launch { effectChannel.send(effect) } }

    private fun refresh(profileId: ProfileId?) {
        if (profileId == null) {
            mutableState.value = ParentDashboardUiState.Error("当前家庭还没有可查看的档案。")
            return
        }
        mutableState.value = ParentDashboardUiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val data = withContext(dispatchers.io) { source.load(profileId) }
                if (data.profileId != profileId) error("Dashboard data scope mismatch")
                mutableState.value = ParentDashboardUiState.Content(members, profileId, data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ParentDashboardUiState.Error("无法读取该孩子的学习概览，请重试。")
            }
        }
    }

    override fun onCleared() { effectChannel.close() }
}
