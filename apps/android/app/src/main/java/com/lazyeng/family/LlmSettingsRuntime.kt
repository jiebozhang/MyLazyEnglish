package com.lazyeng.family

import android.content.Context
import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.common.AiProviderConfig
import com.lazyeng.family.core.common.ContextExplanationRequest
import com.lazyeng.family.core.common.SecureSecretStore
import com.lazyeng.family.core.model.SecretReference
import com.lazyeng.family.core.network.AiProviderFactory
import com.lazyeng.family.core.network.AiProviderKind
import com.lazyeng.family.core.network.BaseUrlPolicy
import com.lazyeng.family.core.network.ContextExplanationUseCase
import com.lazyeng.family.core.network.LlmConfigValidator
import com.lazyeng.family.core.network.LlmProviderOption
import com.lazyeng.family.core.network.LlmSettingsDraft
import com.lazyeng.family.core.network.RedactingNetworkObserver
import com.lazyeng.family.core.network.SafeLogSink
import com.lazyeng.family.core.network.newDraft
import com.lazyeng.family.feature.parent.LlmConnectionResult
import com.lazyeng.family.feature.parent.LlmKeyMetadata
import com.lazyeng.family.feature.parent.LlmSettingsGateway
import com.lazyeng.family.feature.parent.LlmSettingsSnapshot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Duration.Companion.seconds
import java.nio.CharBuffer

class SecureLlmSettingsGateway(
    private val context: Context,
    private val secrets: SecureSecretStore,
    private val providerFactory: AiProviderFactory,
    private val validator: LlmConfigValidator,
    private val activate: (LlmSettingsDraft) -> Unit,
) : LlmSettingsGateway {
    private val preferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override suspend fun load(): LlmSettingsSnapshot {
        val provider = preferences.getString(KEY_PROVIDER, null)?.let { runCatching { LlmProviderOption.valueOf(it) }.getOrNull() }
            ?: LlmProviderOption.DEEPSEEK
        val defaults = provider.newDraft()
        val draft = defaults.copy(
            baseUrl = preferences.getString(KEY_BASE_URL, defaults.baseUrl) ?: defaults.baseUrl,
            model = preferences.getString(KEY_MODEL, defaults.model) ?: defaults.model,
            timeoutSeconds = preferences.getInt(KEY_TIMEOUT, 10),
            enabled = preferences.getBoolean(KEY_ENABLED, true),
        )
        return LlmSettingsSnapshot(draft, keyMetadata(provider))
    }

    override suspend fun keyMetadata(provider: LlmProviderOption): LlmKeyMetadata {
        return when (val result = secrets.read(reference(provider))) {
            is AppResult.Failure -> throw IllegalStateException("Secure credential storage unavailable")
            is AppResult.Success -> result.value?.let { key ->
                try { LlmKeyMetadata(true, mask(key)) } finally { key.fill('\u0000') }
            } ?: LlmKeyMetadata(false)
        }
    }

    override suspend fun test(draft: LlmSettingsDraft, key: CharArray?): LlmConnectionResult {
        if (!draft.enabled) return LlmConnectionResult.Failure("请先启用语境解释，再测试服务连接。")
        if (validator.validate(draft.copy(timeoutSeconds = draft.timeoutSeconds.coerceAtMost(MAX_TEST_SECONDS)), key?.let(CharBuffer::wrap) ?: "",
                key == null && keyMetadata(draft.provider).exists).isNotEmpty()) {
            key?.fill('\u0000')
            return LlmConnectionResult.Failure("配置未通过校验，请检查服务地址、模型名称和 API Key。")
        }
        if (key != null) {
            when (secrets.write(reference(draft.provider), key)) {
                is AppResult.Failure -> return LlmConnectionResult.Failure("Keystore 暂不可用，密钥未保存。")
                is AppResult.Success -> Unit
            }
        }
        val config = config(draft.copy(timeoutSeconds = draft.timeoutSeconds.coerceAtMost(MAX_TEST_SECONDS)))
        val started = System.nanoTime()
        val result = try {
            withTimeout(MAX_TEST_SECONDS * 1_000L) {
                providerFactory.create(draft.provider.kind).validateConnection(config)
            }
        } catch (_: TimeoutCancellationException) {
            return LlmConnectionResult.Failure("服务响应超过 15 秒，请检查网络或服务器地址。")
        }
        return when (result) {
            is AppResult.Success -> LlmConnectionResult.Success(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "服务已返回有效响应",
            )
            is AppResult.Failure -> LlmConnectionResult.Failure(diagnosis(result.error.code.value, draft.provider))
        }
    }

    override suspend fun save(draft: LlmSettingsDraft, key: CharArray?): Boolean {
        val existing = if (key == null) keyMetadata(draft.provider).exists else false
        if (validator.validate(draft, key?.let(CharBuffer::wrap) ?: "", existing).isNotEmpty()) return false
        if (key != null) {
            val result = secrets.write(reference(draft.provider), key)
            if (result !is AppResult.Success) return false
        }
        val saved = preferences.edit()
            .putString(KEY_PROVIDER, draft.provider.name)
            .putString(KEY_BASE_URL, draft.baseUrl.trim())
            .putString(KEY_MODEL, draft.model.trim())
            .putInt(KEY_TIMEOUT, draft.timeoutSeconds)
            .putBoolean(KEY_ENABLED, draft.enabled)
            .commit()
        if (saved) activate(draft)
        return saved
    }

    private fun config(draft: LlmSettingsDraft) = AiProviderConfig(
        baseUrl = draft.baseUrl.trim(),
        model = draft.model.trim(),
        secretReference = reference(draft.provider),
        timeout = draft.timeoutSeconds.seconds,
        apiKeyOptional = draft.provider.keyOptional,
        allowPrivateNetworkHttp = draft.provider == LlmProviderOption.OLLAMA,
    )

    private fun reference(provider: LlmProviderOption) = SecretReference("llm-provider-${provider.name.lowercase()}-key")

    private fun diagnosis(code: String, provider: LlmProviderOption): String = when (code) {
        "AI_AUTH_FAILED" -> "API Key 无效或尚未激活，请检查后重试。"
        "AI_MODEL_NOT_FOUND" -> "模型名称不存在或当前账号无权使用，请核对模型名称。"
        "AI_TIMEOUT" -> "服务响应超时，请检查网络或服务器地址。"
        "AI_BUDGET_REACHED" -> "服务配额或请求频率已达上限，请稍后重试。"
        "AI_RESPONSE_FORMAT_INVALID", "AI_OUTPUT_INVALID" -> "服务已响应，但返回格式不符合预期。"
        "AI_OFFLINE", "AI_TEMPORARY_NETWORK_FAILURE" -> if (provider == LlmProviderOption.OLLAMA)
            "无法访问 Ollama，请确认电脑端已启动并设置 OLLAMA_HOST=0.0.0.0。"
        else "无法访问服务，请检查网络连接和 URL 拼写。"
        else -> "连接测试失败，请检查服务地址、模型名称及网络设置。"
    }

    private fun mask(secret: CharArray): String {
        if (secret.size <= 8) return "••••••••"
        val prefix = String(secret, 0, minOf(5, secret.size - 4))
        val suffix = String(secret, secret.size - 4, 4)
        return "$prefix****$suffix"
    }

    private companion object {
        const val PREFS = "llm_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_TIMEOUT = "timeout_seconds"
        const val KEY_ENABLED = "enabled"
        const val MAX_TEST_SECONDS = 15
    }
}
