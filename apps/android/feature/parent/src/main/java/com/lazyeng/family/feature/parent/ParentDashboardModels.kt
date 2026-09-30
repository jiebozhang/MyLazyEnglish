package com.lazyeng.family.feature.parent

import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VideoId
import java.time.Instant

data class RecentLearningItem(
    val videoId: VideoId,
    val title: String,
    val updatedAt: Instant,
    val completionPercent: Int,
)

data class ParentDashboardData(
    val profileId: ProfileId,
    val streakDays: Int,
    val vocabularyCount: Int,
    val masteredCount: Int,
    val recentItems: List<RecentLearningItem>,
    /** Null until durable foreground playback-session durations are recorded. */
    val todayWatchMinutes: Long? = null,
    val weekWatchMinutes: Long? = null,
)

fun interface ParentDashboardDataSource {
    suspend fun load(profileId: ProfileId): ParentDashboardData
}

sealed interface ParentDashboardUiState {
    data object Loading : ParentDashboardUiState
    data class Content(
        val profiles: List<Profile>,
        val selectedProfileId: ProfileId,
        val data: ParentDashboardData,
    ) : ParentDashboardUiState
    data class Error(val message: String) : ParentDashboardUiState
}

sealed interface ParentDashboardUiEvent {
    data class SelectProfile(val profileId: ProfileId) : ParentDashboardUiEvent
    data object Refresh : ParentDashboardUiEvent
    data object ImportVideo : ParentDashboardUiEvent
    data object TimeSettings : ParentDashboardUiEvent
    data object ContentManagement : ParentDashboardUiEvent
    data object MemberManagement : ParentDashboardUiEvent
    data object StorageManagement : ParentDashboardUiEvent
    data object ChangePin : ParentDashboardUiEvent
    data object LlmSettings : ParentDashboardUiEvent
}

sealed interface ParentDashboardUiEffect {
    data object ImportVideo : ParentDashboardUiEffect
    data object OpenLlmSettings : ParentDashboardUiEffect
    data object OpenMemberManagement : ParentDashboardUiEffect
    data object OpenContentManagement : ParentDashboardUiEffect
    data object OpenStorageManagement : ParentDashboardUiEffect
    data class NotAvailableYet(val action: String) : ParentDashboardUiEffect
}
