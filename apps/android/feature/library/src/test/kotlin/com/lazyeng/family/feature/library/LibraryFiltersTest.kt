package com.lazyeng.family.feature.library

import com.lazyeng.family.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersTest {
    private val videos = listOf(
        video("kite", "Red Kite", "A1", "nature", SubtitleStatus.Available),
        video("ocean", "Blue Ocean", "A2", "nature", SubtitleStatus.NoSubtitle),
        video("forest", "Green Forest", "A1", "plants", SubtitleStatus.Unknown("PENDING")),
    )

    @Test fun searchAndIndependentFiltersCombineWithoutClearingEachOther() {
        val filters = LibraryFilters(query = "red", level = "A1", topic = "nature", source = "LOCAL_FILE")
        assertEquals(listOf(VideoId("kite")), filterLibraryVideos(videos, filters).map { it.id })
        val changedSearch = filters.copy(query = "blue")
        assertEquals("A1", changedSearch.level)
        assertEquals("nature", changedSearch.topic)
        assertEquals("LOCAL_FILE", changedSearch.source)
        assertEquals(emptyList<VideoId>(), filterLibraryVideos(videos, changedSearch).map { it.id })
        assertEquals(listOf(VideoId("ocean")), filterLibraryVideos(videos, changedSearch.copy(level = "A2")).map { it.id })
    }

    @Test fun subtitleFilterAndClearCoverEveryStatus() {
        assertEquals(listOf(VideoId("kite")), filterLibraryVideos(videos, LibraryFilters(subtitle = LibrarySubtitleFilter.AVAILABLE)).map { it.id })
        assertEquals(listOf(VideoId("ocean")), filterLibraryVideos(videos, LibraryFilters(subtitle = LibrarySubtitleFilter.MISSING)).map { it.id })
        assertEquals(listOf(VideoId("forest")), filterLibraryVideos(videos, LibraryFilters(subtitle = LibrarySubtitleFilter.UNKNOWN)).map { it.id })
        assertEquals(3, filterLibraryVideos(videos, LibraryFilters()).size)
    }

    private fun video(id: String, title: String, level: String, topic: String, subtitle: SubtitleStatus) = Video(
        VideoId(id), VideoSourceType.LOCAL_FILE, VideoSourceReference("content://fixture/$id"), "fixture-hash",
        VideoMetadata(title, null, 10_000, listOf(topic), level, null, null, "child"),
        VideoStatus.READY, ImportStatus.Ready, subtitle, TranslationStatus.Unknown("NONE"), true,
        VideoRights("fixture", null, null, true),
    )
}
