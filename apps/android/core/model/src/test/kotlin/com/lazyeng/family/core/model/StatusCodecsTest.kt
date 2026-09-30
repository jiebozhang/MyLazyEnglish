package com.lazyeng.family.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCodecsTest {
    @Test
    fun knownStatesRoundTripThroughWireValues() {
        val importStates = listOf(
            "DRAFT" to ImportStatus.Draft,
            "PROCESSING" to ImportStatus.Processing,
            "NEEDS_SUBTITLE" to ImportStatus.NeedsSubtitle,
            "FAILED" to ImportStatus.Failed,
            "READY" to ImportStatus.Ready,
            "ARCHIVED" to ImportStatus.Archived,
        )
        importStates.forEach { (wire, state) ->
            assertEquals(state, StatusCodecs.importStatusFromWire(wire))
            assertEquals(wire, StatusCodecs.importStatusToWire(state))
        }

        listOf("NO_SUBTITLE" to SubtitleStatus.NoSubtitle, "AVAILABLE" to SubtitleStatus.Available).forEach { (wire, state) ->
            assertEquals(state, StatusCodecs.subtitleStatusFromWire(wire))
            assertEquals(wire, StatusCodecs.subtitleStatusToWire(state))
        }
        listOf("AVAILABLE" to TranslationStatus.Available, "STALE" to TranslationStatus.Stale).forEach { (wire, state) ->
            assertEquals(state, StatusCodecs.translationStatusFromWire(wire))
            assertEquals(wire, StatusCodecs.translationStatusToWire(state))
        }
        listOf("QUEUED" to AiJobStatus.Queued, "CANCELLED" to AiJobStatus.Cancelled).forEach { (wire, state) ->
            assertEquals(state, StatusCodecs.aiJobStatusFromWire(wire))
            assertEquals(wire, StatusCodecs.aiJobStatusToWire(state))
        }
        assertEquals(GeneratedContentStatus.Ready, StatusCodecs.generatedContentStatusFromWire("READY"))
        assertEquals("READY", StatusCodecs.generatedContentStatusToWire(GeneratedContentStatus.Ready))
    }

    @Test
    fun unknownStatesRemainTypedAndRoundTripWithoutFailure() {
        val raw = "FUTURE_PROVIDER_STATE"
        assertEquals(ImportStatus.Unknown(raw), StatusCodecs.importStatusFromWire(raw))
        assertEquals(SubtitleStatus.Unknown(raw), StatusCodecs.subtitleStatusFromWire(raw))
        assertEquals(TranslationStatus.Unknown(raw), StatusCodecs.translationStatusFromWire(raw))
        assertEquals(AiJobStatus.Unknown(raw), StatusCodecs.aiJobStatusFromWire(raw))
        assertEquals(GeneratedContentStatus.Unknown(raw), StatusCodecs.generatedContentStatusFromWire(raw))

        assertEquals(raw, StatusCodecs.importStatusToWire(ImportStatus.Unknown(raw)))
        assertEquals(raw, StatusCodecs.subtitleStatusToWire(SubtitleStatus.Unknown(raw)))
        assertEquals(raw, StatusCodecs.translationStatusToWire(TranslationStatus.Unknown(raw)))
        assertEquals(raw, StatusCodecs.aiJobStatusToWire(AiJobStatus.Unknown(raw)))
        assertEquals(raw, StatusCodecs.generatedContentStatusToWire(GeneratedContentStatus.Unknown(raw)))
    }
}
