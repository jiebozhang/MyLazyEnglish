package com.lazyeng.family.feature.home

import com.lazyeng.family.core.model.*

data class HomeProfileHeader(
    val profileId: ProfileId,
    val nickname: String,
    val avatarId: String?,
    val englishLevel: String,
    val role: ProfileRole,
)

data class ContinueWatchingModel(
    val videoId: VideoId,
    val title: String,
    val durationMs: Long?,
    val positionMs: Long,
    val completion: Float,
    val coverAssetId: VideoAssetId?,
    val levelLabel: String?,
) {
    val progressPercent: Int get() = (completion * 100).toInt().coerceIn(0, 100)
    val remainingMs: Long get() = ((durationMs ?: 0L) - positionMs).coerceAtLeast(0L)
}

data class StreakInfo(
    val streakDays: Int,
    val hasWatchedToday: Boolean,
    val message: String,
)

data class VocabularySummary(
    val pendingCount: Int,
    val label: String,
)

data class HomeVideoCardModel(
    val videoId: VideoId,
    val title: String,
    val durationMs: Long?,
    val durationLabel: String,
    val subtitleWordCount: Int?,
    val levelLabel: String?,
    val coverAssetId: VideoAssetId?,
    val canWatch: Boolean,
)

data class HomeDashboardData(
    val profile: HomeProfileHeader,
    val continueWatching: ContinueWatchingModel?,
    val streakInfo: StreakInfo,
    val vocabularySummary: VocabularySummary,
    val recommendations: List<HomeVideoCardModel>,
)
