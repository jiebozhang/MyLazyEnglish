package com.lazyeng.family.feature.player

/** Immutable, binary-searched cue timeline. A small edge tolerance absorbs timestamp drift. */
class SubtitleTimelineIndex(
    cues: List<PlayerSubtitleCue>,
    private val toleranceMs: Long = DEFAULT_TOLERANCE_MS,
) {
    val cues: List<PlayerSubtitleCue> = cues.sortedWith(
        compareBy<PlayerSubtitleCue> { it.startMs }.thenBy { it.sequence }.thenBy { it.id.value },
    )
    private val prefixMaximumEnd = LongArray(this.cues.size).also { ends ->
        var maximum = Long.MIN_VALUE
        this.cues.forEachIndexed { index, cue ->
            maximum = maxOf(maximum, cue.endMs)
            ends[index] = maximum
        }
    }

    init {
        require(toleranceMs >= 0L)
        require(this.cues.all { it.startMs >= 0L && it.endMs >= it.startMs })
    }

    /** Returns the latest-starting active cue, or null during genuine subtitle gaps. */
    fun findCueIndex(positionMs: Long): Int? {
        if (positionMs < 0L || cues.isEmpty()) return null
        val earliestStart = lowerBoundEnd(positionMs - toleranceMs)
        val latestStart = upperBoundStart(positionMs + toleranceMs) - 1
        if (earliestStart > latestStart || latestStart < 0) return null
        for (index in latestStart downTo earliestStart) {
            if (cues[index].endMs >= positionMs - toleranceMs) return index
        }
        return null
    }

    private fun lowerBoundEnd(target: Long): Int {
        var low = 0
        var high = prefixMaximumEnd.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefixMaximumEnd[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun upperBoundStart(target: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= target) low = middle + 1 else high = middle
        }
        return low
    }

    companion object { const val DEFAULT_TOLERANCE_MS = 100L }
}
