package com.lazyeng.family.feature.player

import com.lazyeng.family.core.model.SubtitleLineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTimelineIndexTest {
    private val timeline = SubtitleTimelineIndex(
        listOf(
            cue("later", 2, 2_000, 3_000),
            cue("first", 1, 0, 1_000),
            cue("overlap", 3, 900, 2_200),
            cue("last", 4, 4_000, 5_000),
        ),
    )

    @Test fun binaryLookupTracksBoundariesOverlapAndGapsWithSmallTolerance() {
        assertEquals(0, timeline.findCueIndex(0))
        assertEquals(1, timeline.findCueIndex(1_100))
        assertEquals(2, timeline.findCueIndex(2_300))
        assertEquals(3, timeline.findCueIndex(3_950))
        assertNull(timeline.findCueIndex(3_500))
        assertNull(timeline.findCueIndex(5_101))
        assertNull(timeline.findCueIndex(-1))
    }

    @Test fun sortsDeterministicallyAndKeepsLatestStartingCueActive() {
        assertEquals(listOf("first", "overlap", "later", "last"), timeline.cues.map { it.id.value })
        assertEquals(1, timeline.findCueIndex(1_500))
        assertEquals(2, timeline.findCueIndex(2_200))
    }

    @Test fun rejectsInvalidIntervalsAndTolerance() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SubtitleTimelineIndex(listOf(cue("invalid", 1, 10, 9)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SubtitleTimelineIndex(emptyList(), toleranceMs = -1)
        }
    }

    private fun cue(id: String, sequence: Int, start: Long, end: Long) =
        PlayerSubtitleCue(SubtitleLineId(id), sequence, start, end, "Cue $id")
}
