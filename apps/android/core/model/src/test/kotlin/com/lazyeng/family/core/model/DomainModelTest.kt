package com.lazyeng.family.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DomainModelTest {
    @Test
    fun videoStatusTransitionsFollowPrdFlow() {
        assertTrue(video(VideoStatus.DRAFT).canTransitionTo(VideoStatus.PROCESSING))
        assertTrue(video(VideoStatus.PROCESSING).canTransitionTo(VideoStatus.NEEDS_SUBTITLE))
        assertTrue(video(VideoStatus.PROCESSING).canTransitionTo(VideoStatus.FAILED))
        assertTrue(video(VideoStatus.PROCESSING).canTransitionTo(VideoStatus.READY))
        assertTrue(video(VideoStatus.READY).canTransitionTo(VideoStatus.ARCHIVED))
        assertFalse(video(VideoStatus.ARCHIVED).canTransitionTo(VideoStatus.READY))
        assertFalse(video(VideoStatus.DRAFT).canTransitionTo(VideoStatus.READY))
    }

    @Test
    fun publishedSubtitleVersionHasNoDraftMutationContract() {
        val published: SubtitleVersion = PublishedSubtitleVersion(
            SubtitleVersionId("version-1"), SubtitleTrackId("track-1"), "hash", "parser-1", Instant.EPOCH,
        )
        assertTrue(published is PublishedSubtitleVersion)
        assertFalse(published is DraftSubtitleVersion)
        assertFalse(published.javaClass.declaredMethods.any { it.name == "copy" })
    }

    private fun video(status: VideoStatus) = Video(
        id = VideoId("video-1"),
        sourceType = VideoSourceType.LOCAL_FILE,
        sourceReference = VideoSourceReference("opaque-reference"),
        checksum = null,
        metadata = VideoMetadata("Sample", null, null, emptyList(), null, null, null, null),
        status = status,
        importStatus = ImportStatus.Unknown("UNKNOWN"),
        subtitleStatus = SubtitleStatus.Unknown("UNKNOWN"),
        translationStatus = TranslationStatus.Unknown("UNKNOWN"),
        playable = false,
        rights = VideoRights("FAMILY", null, null, false),
    )
}
