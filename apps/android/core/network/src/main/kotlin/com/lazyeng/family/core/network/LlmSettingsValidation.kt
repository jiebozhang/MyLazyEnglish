package com.lazyeng.family.core.network

import java.net.URI

enum class LlmProviderOption(
    val label: String,
    val kind: AiProviderKind,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val keyOptional: Boolean = false,
) {
    DEEPSEEK("DeepSeek", AiProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", "deepseek-flash"),
    OPENAI("OpenAI", AiProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5-mini"),
    CLAUDE("Claude", AiProviderKind.ANTHROPIC, "https://api.anthropic.com/v1", "claude-haiku-4-5-20251001"),
    OLLAMA("Ollama（局域网）", AiProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.100:11434/v1", "llama3.2", true),
    CUSTOM("自定义兼容服务", AiProviderKind.OPENAI_COMPATIBLE, "", ""),
}

data class LlmSettingsDraft(
    val provider: LlmProviderOption = LlmProviderOption.DEEPSEEK,
    val baseUrl: String = LlmProviderOption.DEEPSEEK.defaultBaseUrl,
    val model: String = LlmProviderOption.DEEPSEEK.defaultModel,
    val timeoutSeconds: Int = 10,
    val enabled: Boolean = true,
)

data class LlmValidationIssue(val field: String, val message: String)

class LlmConfigValidator(private val baseUrlPolicy: BaseUrlPolicy) {
    fun validate(draft: LlmSettingsDraft, key: CharSequence, hasSavedKey: Boolean): List<LlmValidationIssue> {
        if (!draft.enabled) return emptyList()
        return buildList {
        val base = draft.baseUrl.trim()
        val uri = runCatching { URI(base) }.getOrNull()
        if (uri == null || baseUrlPolicy.validate(base, allowPrivateNetworkHttp = draft.provider == LlmProviderOption.OLLAMA) == null) {
            add(LlmValidationIssue("baseUrl", "请输入有效的 HTTPS 地址；Ollama 仅允许局域网私有 IPv4 地址使用 HTTP。"))
        }
        if (draft.model.isBlank() || draft.model.length > 128 || draft.model.any(Char::isWhitespace)) {
            add(LlmValidationIssue("model", "模型名称不能为空，且不能包含空白字符。"))
        }
        if (draft.timeoutSeconds !in 5..120) add(LlmValidationIssue("timeout", "超时时间需在 5 到 120 秒之间。"))
        val hasKey = key.isNotEmpty() || hasSavedKey
        if (!draft.provider.keyOptional && !hasKey) add(LlmValidationIssue("apiKey", "请输入 API Key。"))
        if (key.isNotEmpty() && (key.length !in 8..512 || key.any(Char::isWhitespace))) {
            add(LlmValidationIssue("apiKey", "Key 格式不正确，请检查长度及空格。"))
        }
        }
    }
}

fun LlmProviderOption.newDraft() = LlmSettingsDraft(this, defaultBaseUrl, defaultModel)
