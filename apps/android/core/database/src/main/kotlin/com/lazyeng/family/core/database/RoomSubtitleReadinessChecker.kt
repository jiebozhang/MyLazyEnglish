package com.lazyeng.family.core.database

import androidx.room.withTransaction
import com.lazyeng.family.core.common.SubtitleReadinessChecker
import com.lazyeng.family.core.common.subtitle.TimelineValidator
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VideoId

internal class RoomSubtitleReadinessChecker(private val db: FamilyDatabase) : SubtitleReadinessChecker {
    override suspend fun hasPublishedEnglish(profileId: ProfileId, videoId: VideoId): Boolean = db.withTransaction {
        val dao = db.subtitleDao()
        dao.tracks(profileId.value, videoId.value).any { track ->
            val english = englishTag.matches(track.language)
            val version = track.currentVersionId?.let { dao.version(profileId.value, it) }
            english && version != null && version.trackId == track.id && version.status == "PUBLISHED" && version.publishedAt != null &&
                TimelineValidator.validate(dao.lines(profileId.value, version.id, 0, Int.MAX_VALUE).map { it.toDomain() }).canPublish
        }
    }

    private val englishTag = Regex("en(?:-[a-z0-9]{1,8})*", RegexOption.IGNORE_CASE)
}
