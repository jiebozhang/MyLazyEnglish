package com.lazyeng.family.core.common

@JvmInline
value class AppErrorCode(val value: String)

enum class ErrorHandling {
    AUTOMATIC_RETRY,
    USER_ACTION,
    PARENT_INTERVENTION,
    NON_RETRYABLE,
}

@JvmInline
value class UserMessageKey(val value: String)

data class DiagnosticInfo(
    val category: String,
    val detail: String? = null,
    val correlationId: String? = null,
)

data class AppError(
    val code: AppErrorCode,
    val handling: ErrorHandling,
    val userMessage: UserMessageKey,
    val diagnostic: DiagnosticInfo? = null,
    val maxAutomaticRetries: Int = if (handling == ErrorHandling.AUTOMATIC_RETRY) 1 else 0,
) {
    init {
        require(maxAutomaticRetries in 0..1) { "PRD limits automatic retry to one attempt" }
        require(handling == ErrorHandling.AUTOMATIC_RETRY || maxAutomaticRetries == 0)
    }
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

object KnownAppErrors {
    val noSubtitle = AppError(
        AppErrorCode("NO_SUBTITLE"), ErrorHandling.PARENT_INTERVENTION, UserMessageKey("error.no_subtitle"),
    )
    val videoUnavailable = AppError(
        AppErrorCode("VIDEO_UNAVAILABLE"), ErrorHandling.USER_ACTION, UserMessageKey("error.video_unavailable"),
    )
    val aiNotConfigured = AppError(
        AppErrorCode("AI_NOT_CONFIGURED"), ErrorHandling.PARENT_INTERVENTION, UserMessageKey("error.ai_not_configured"),
    )
    val aiAuthFailed = AppError(
        AppErrorCode("AI_AUTH_FAILED"), ErrorHandling.PARENT_INTERVENTION, UserMessageKey("error.ai_auth_failed"),
    )
    val aiBudgetReached = AppError(
        AppErrorCode("AI_BUDGET_REACHED"), ErrorHandling.PARENT_INTERVENTION, UserMessageKey("error.ai_budget_reached"),
    )
    val aiOutputInvalid = AppError(
        AppErrorCode("AI_OUTPUT_INVALID"), ErrorHandling.USER_ACTION, UserMessageKey("error.ai_output_invalid"),
    )
    val storageLow = AppError(
        AppErrorCode("STORAGE_LOW"), ErrorHandling.USER_ACTION, UserMessageKey("error.storage_low"),
    )
    val subtitleOutOfSync = AppError(
        AppErrorCode("SUBTITLE_OUT_OF_SYNC"), ErrorHandling.PARENT_INTERVENTION, UserMessageKey("error.subtitle_out_of_sync"),
    )

    val all = listOf(
        noSubtitle, videoUnavailable, aiNotConfigured, aiAuthFailed,
        aiBudgetReached, aiOutputInvalid, storageLow, subtitleOutOfSync,
    ).associateBy { it.code }
}

object AiConnectionErrors {
    val timeout = AppError(
        AppErrorCode("AI_TIMEOUT"), ErrorHandling.AUTOMATIC_RETRY, UserMessageKey("error.ai_unavailable"),
    )
    val offline = AppError(
        AppErrorCode("AI_OFFLINE"), ErrorHandling.USER_ACTION, UserMessageKey("error.ai_unavailable"),
    )
    val temporaryNetworkFailure = AppError(
        AppErrorCode("AI_TEMPORARY_NETWORK_FAILURE"), ErrorHandling.AUTOMATIC_RETRY,
        UserMessageKey("error.ai_unavailable"),
    )
    val modelNotFound = AppError(
        AppErrorCode("AI_MODEL_NOT_FOUND"), ErrorHandling.PARENT_INTERVENTION, UserMessageKey("error.ai_model_not_found"),
    )
    val responseFormatInvalid = AppError(
        AppErrorCode("AI_RESPONSE_FORMAT_INVALID"), ErrorHandling.USER_ACTION, UserMessageKey("error.ai_output_invalid"),
    )
}
