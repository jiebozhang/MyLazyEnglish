package com.lazyeng.family.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KnownAppErrorsTest {
    @Test
    fun catalogCoversEveryPrd15CodeWithSeparateMessageAndHandling() {
        val expected = setOf(
            "NO_SUBTITLE", "VIDEO_UNAVAILABLE", "AI_NOT_CONFIGURED", "AI_AUTH_FAILED",
            "AI_BUDGET_REACHED", "AI_OUTPUT_INVALID", "STORAGE_LOW", "SUBTITLE_OUT_OF_SYNC",
        )
        assertEquals(expected, KnownAppErrors.all.keys.map { it.value }.toSet())
        KnownAppErrors.all.values.forEach { error ->
            assertNotNull(error.userMessage)
            assertNotNull(error.handling)
        }
    }

    @Test
    fun aiConnectionErrorsDistinguishOperationalFailures() {
        assertEquals("AI_AUTH_FAILED", KnownAppErrors.aiAuthFailed.code.value)
        assertEquals("AI_BUDGET_REACHED", KnownAppErrors.aiBudgetReached.code.value)
        assertEquals("AI_TIMEOUT", AiConnectionErrors.timeout.code.value)
        assertEquals("AI_OFFLINE", AiConnectionErrors.offline.code.value)
        assertEquals(1, AiConnectionErrors.temporaryNetworkFailure.maxAutomaticRetries)
        assertEquals("AI_MODEL_NOT_FOUND", AiConnectionErrors.modelNotFound.code.value)
        assertEquals("AI_RESPONSE_FORMAT_INVALID", AiConnectionErrors.responseFormatInvalid.code.value)
    }

    @Test
    fun newErrorCodesRemainRepresentableWithoutChangingResultType() {
        val extensionError = AppResult.Failure(
            AppError(AppErrorCode("FUTURE_CODE"), ErrorHandling.USER_ACTION, UserMessageKey("future.message")),
        )
        assertEquals("FUTURE_CODE", extensionError.error.code.value)
    }
}
