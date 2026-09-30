package com.lazyeng.family.feature.home

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.VideoCatalog
import com.lazyeng.family.core.model.*
import java.time.LocalDate
import java.time.ZoneId

data class HomeSession(val data: HomeData)

class HomeData(
    val familyId: FamilyId,
    val profileId: ProfileId,
    private val profiles: ProfileRepository,
    private val videos: VideoCatalog,
    private val progress: WatchProgressRepository,
    private val scopeValid: suspend () -> Boolean,
    private val clock: Clock,
) {
    suspend fun validScope(): Boolean = scopeValid()

    suspend fun loadDashboard(): HomeDashboardData? {
        if (!validScope()) return null
        val profile = profiles.getProfile(familyId, profileId) ?: return null
        if (profile.deletedAt != null) return null

        val progressList = progress.listProgress(profileId)

        // Find candidate for Continue Watching
        var continueItem: ContinueWatchingModel? = null
        if (progressList.isNotEmpty()) {
            val sortedProgress = progressList.sortedByDescending { it.updatedAt }
            // Prefer incomplete videos (< 0.95 completion)
            val candidate = sortedProgress.firstOrNull { it.completion < 0.95f } ?: sortedProgress.firstOrNull()
            if (candidate != null) {
                val video = videos.getVideo(profileId, candidate.videoId)
                if (video != null && video.status == VideoStatus.READY && video.playable) {
                    continueItem = ContinueWatchingModel(
                        videoId = video.id,
                        title = video.metadata.title,
                        durationMs = video.metadata.durationMs,
                        positionMs = candidate.positionMs.coerceAtLeast(0L),
                        completion = candidate.completion.coerceIn(0f, 1f),
                        coverAssetId = video.metadata.coverAssetId,
                        levelLabel = formatLevel(video.metadata),
                    )
                }
            }
        }

        // Streak & check-in calculation
        val zone = ZoneId.systemDefault()
        val today = clock.now().atZone(zone).toLocalDate()
        val watchedDates = progressList.map { it.updatedAt.atZone(zone).toLocalDate() }.toSet()
        val hasWatchedToday = watchedDates.contains(today)
        var streak = 0
        var checkDate = if (hasWatchedToday) today else today.minusDays(1)
        while (watchedDates.contains(checkDate)) {
            streak++
            checkDate = checkDate.minusDays(1)
        }
        val streakMessage = when {
            hasWatchedToday && streak > 0 -> "连续打卡 $streak 天 · 今日已完成"
            streak > 0 -> "连续打卡 $streak 天 · 今日待学习"
            else -> "开启学习打卡第一天"
        }
        val streakInfo = StreakInfo(
            streakDays = streak,
            hasWatchedToday = hasWatchedToday,
            message = streakMessage,
        )

        // Recommendations
        val rawRecs = videos.recommendations(profileId)
        val targetLevel = profile.englishLevel.trim()
        val sortedRecs = rawRecs.sortedWith(
            compareByDescending<Video> { v ->
                v.metadata.cefrLevel?.trim().equals(targetLevel, ignoreCase = true)
            }.thenBy { it.id.value }
        )
        val recommendations = sortedRecs.map { v ->
            HomeVideoCardModel(
                videoId = v.id,
                title = v.metadata.title,
                durationMs = v.metadata.durationMs,
                durationLabel = formatDuration(v.metadata.durationMs),
                subtitleWordCount = v.metadata.subtitleWordCount,
                levelLabel = formatLevel(v.metadata) ?: v.metadata.cefrLevel,
                coverAssetId = v.metadata.coverAssetId,
                canWatch = v.playable && v.status == VideoStatus.READY,
            )
        }

        val vocabularySummary = VocabularySummary(
            pendingCount = 0,
            label = "生词复习",
        )

        val header = HomeProfileHeader(
            profileId = profile.id,
            nickname = profile.nickname,
            avatarId = profile.avatarId,
            englishLevel = profile.englishLevel,
            role = profile.role,
        )

        if (!validScope()) return null
        return HomeDashboardData(
            profile = header,
            continueWatching = continueItem,
            streakInfo = streakInfo,
            vocabularySummary = vocabularySummary,
            recommendations = recommendations,
        )
    }

    companion object {
        fun formatDuration(ms: Long?): String = ms?.let {
            val totalSeconds = (it.coerceAtLeast(0) / 1000)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } ?: "--:--"

        fun formatLevel(metadata: VideoMetadata): String? = metadata.cefrLevel?.let { value ->
            when (metadata.levelSource) {
                VideoLevelSource.MANUAL -> "人工等级 $value"
                VideoLevelSource.ESTIMATED -> "估算等级 $value"
                null -> "等级 $value"
            }
        }
    }
}
