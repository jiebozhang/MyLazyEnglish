package com.lazyeng.family.core.network

import com.lazyeng.family.core.common.AiConnectionErrors
import com.lazyeng.family.core.common.AiProvider
import com.lazyeng.family.core.common.AiProviderConfig
import com.lazyeng.family.core.common.AiProviderResult
import com.lazyeng.family.core.common.AppError
import com.lazyeng.family.core.common.AppErrorCode
import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.common.ContextExplanation
import com.lazyeng.family.core.common.ContextExplanationRequest
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.common.ErrorHandling
import com.lazyeng.family.core.common.KnownAppErrors
import com.lazyeng.family.core.common.SecureSecretStore
import com.lazyeng.family.core.common.UserMessageKey
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

fun interface SafeLogSink {
    fun record(event: String)
}

/** Emits only fixed event labels. URLs, headers, bodies, response text and exception messages are excluded. */
class RedactingNetworkObserver(private val sink: SafeLogSink) {
    fun requestStarted() = sink.record("ai_request_started")
    fun requestFinished(status: Int) = sink.record("ai_request_finished_$status")
    fun requestFailed() = sink.record("ai_request_failed")
}

class RedactingLoggingInterceptor(private val observer: RedactingNetworkObserver) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        observer.requestStarted()
        return try {
            chain.proceed(chain.request()).also { observer.requestFinished(it.code) }
        } catch (error: IOException) {
            observer.requestFailed()
            throw error
        }
    }
}

class BaseUrlPolicy(private val allowInsecureLocalEndpoints: Boolean) {
    fun validate(value: String, allowPrivateNetworkHttp: Boolean = false): URI? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.host.isNullOrBlank() || uri.query != null || uri.fragment != null) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val segments = path.removePrefix("/").split('/')
        if (path.isNotEmpty() && (!path.startsWith('/') || segments.last() != "v1" ||
                segments.any { it.isBlank() || it == "." || it == ".." || !it.matches(Regex("[A-Za-z0-9._~-]+")) })) return null
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host.lowercase()
        val loopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        val localHttpAllowed = uri.scheme.equals("http", ignoreCase = true) &&
            ((allowInsecureLocalEndpoints && loopback) || (allowPrivateNetworkHttp && host.isPrivateIpv4()))
        return uri.takeIf { secure || localHttpAllowed }
    }
}

fun configuredBaseUrlPolicy() = BaseUrlPolicy(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS)

private fun String.isPrivateIpv4(): Boolean {
    val octets = split('.')
    if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any(Char::isDigit).not() }) return false
    val values = octets.map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return false }
    return values[0] == 10 ||
        (values[0] == 172 && values[1] in 16..31) ||
        (values[0] == 192 && values[1] == 168)
}

internal enum class WireProtocol { OPENAI_COMPATIBLE, ANTHROPIC }

enum class AiProviderKind { OPENAI_COMPATIBLE, ANTHROPIC }

class AiProviderFactory(
    private val secrets: SecureSecretStore,
    private val dispatchers: DispatcherProvider,
    private val log: RedactingNetworkObserver,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrlPolicy: BaseUrlPolicy = BaseUrlPolicy(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS),
) {
    fun create(kind: AiProviderKind): AiProvider = when (kind) {
        AiProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(secrets, dispatchers, log, client, baseUrlPolicy)
        AiProviderKind.ANTHROPIC -> AnthropicProvider(secrets, dispatchers, log, client, baseUrlPolicy)
    }
}

/** Both wire adapters satisfy the same provider-neutral contract. */
class OpenAiCompatibleProvider(
    secrets: SecureSecretStore,
    dispatchers: DispatcherProvider,
    log: RedactingNetworkObserver,
    client: OkHttpClient = OkHttpClient(),
    baseUrlPolicy: BaseUrlPolicy = BaseUrlPolicy(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS),
) : HttpAiProvider("openai_compatible", WireProtocol.OPENAI_COMPATIBLE, secrets, dispatchers, log, client, baseUrlPolicy)

class AnthropicProvider(
    secrets: SecureSecretStore,
    dispatchers: DispatcherProvider,
    log: RedactingNetworkObserver,
    client: OkHttpClient = OkHttpClient(),
    baseUrlPolicy: BaseUrlPolicy = BaseUrlPolicy(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS),
) : HttpAiProvider("anthropic", WireProtocol.ANTHROPIC, secrets, dispatchers, log, client, baseUrlPolicy)

abstract class HttpAiProvider internal constructor(
    private val providerName: String,
    private val protocol: WireProtocol,
    private val secrets: SecureSecretStore,
    private val dispatchers: DispatcherProvider,
    private val log: RedactingNetworkObserver,
        client: OkHttpClient,
    private val baseUrlPolicy: BaseUrlPolicy,
) : AiProvider {
    private val client = client.newBuilder().addInterceptor(RedactingLoggingInterceptor(log)).build()
    override suspend fun validateConnection(config: AiProviderConfig): AppResult<Unit> = ping(config)

    private suspend fun ping(config: AiProviderConfig): AppResult<Unit> = kotlinx.coroutines.withContext(dispatchers.io) {
        val uri = baseUrlPolicy.validate(config.baseUrl, config.allowPrivateNetworkHttp)
            ?: return@withContext failure(KnownAppErrors.aiNotConfigured)
        val secretResult = secrets.read(config.secretReference)
        val secret = when (secretResult) {
            is AppResult.Success -> secretResult.value ?: if (config.apiKeyOptional) CharArray(0)
                else return@withContext failure(KnownAppErrors.aiNotConfigured)
            is AppResult.Failure -> return@withContext secretResult
        }
        if ((secret.isEmpty() && !config.apiKeyOptional) || config.model.isBlank()) {
            secret.fill('\u0000')
            return@withContext failure(KnownAppErrors.aiNotConfigured)
        }
        val endpoint = endpoint(uri, protocol)
        val payload = when (protocol) {
            WireProtocol.OPENAI_COMPATIBLE -> JSONObject().put("model", config.model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
                .put("max_tokens", 1).toString()
            WireProtocol.ANTHROPIC -> JSONObject().put("model", config.model).put("max_tokens", 1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "OK"))).toString()
        }.toByteArray(Charsets.UTF_8)
        val token = secret.concatToString()
        secret.fill('\u0000')
        val request = Request.Builder().url(endpoint.toASCIIString()).post(payload.toRequestBody(JSON_MEDIA_TYPE)).apply {
            if (token.isNotEmpty()) when (protocol) {
                WireProtocol.OPENAI_COMPATIBLE -> header("Authorization", "Bearer $token")
                WireProtocol.ANTHROPIC -> header("x-api-key", token).header("anthropic-version", ANTHROPIC_VERSION)
            }
        }.build()
        try {
            client.newBuilder().callTimeout(config.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
                .newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) AppResult.Success(Unit) else failure(mapStatus(response.code))
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedIOException) {
            failure(AiConnectionErrors.timeout)
        } catch (_: IOException) {
            failure(AiConnectionErrors.offline)
        } catch (_: Exception) {
            failure(AiConnectionErrors.temporaryNetworkFailure)
        } finally {
            payload.fill(0)
        }
    }

    override suspend fun explainContext(
        config: AiProviderConfig,
        request: ContextExplanationRequest,
    ): AppResult<AiProviderResult<ContextExplanation>> = explain(config, request, repair = false)

    override suspend fun repairContext(
        config: AiProviderConfig,
        request: ContextExplanationRequest,
    ): AppResult<AiProviderResult<ContextExplanation>> = explain(config, request, repair = true)

    private suspend fun explain(
        config: AiProviderConfig,
        request: ContextExplanationRequest,
        repair: Boolean,
    ): AppResult<AiProviderResult<ContextExplanation>> {
        val started = System.nanoTime()
        return when (val result = send(config, request, repair)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(
                AiProviderResult(
                    requestId = UUID.randomUUID().toString(),
                    feature = com.lazyeng.family.core.model.AiFeature.CONTEXT_EXPLANATION,
                    value = result.value,
                    provider = providerName,
                    model = config.model,
                    promptVersion = "context-explanation-v1",
                    cacheHit = false,
                    latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    usageBucket = null,
                    safetyFlag = result.value.safetyFlag,
                ),
            )
        }
    }

    private suspend fun send(
        config: AiProviderConfig,
        input: ContextExplanationRequest,
        repair: Boolean = false,
    ): AppResult<ContextExplanation> = kotlinx.coroutines.withContext(dispatchers.io) {
        val uri = baseUrlPolicy.validate(config.baseUrl, config.allowPrivateNetworkHttp)
            ?: return@withContext failure(KnownAppErrors.aiNotConfigured)
        val secretResult = secrets.read(config.secretReference)
        val secret = when (secretResult) {
            is AppResult.Success -> secretResult.value
                ?: if (config.apiKeyOptional) CharArray(0) else return@withContext failure(KnownAppErrors.aiNotConfigured)
            is AppResult.Failure -> return@withContext secretResult
        }
        if ((secret.isEmpty() && !config.apiKeyOptional) || config.model.isBlank() || config.timeout.inWholeMilliseconds !in 5_000..120_000) {
            secret.fill('\u0000')
            return@withContext failure(KnownAppErrors.aiNotConfigured)
        }

        val endpoint = endpoint(uri, protocol)
        val payload = requestBody(config.model, input, protocol, repair).toByteArray(Charsets.UTF_8)
        val token = secret.concatToString()
        secret.fill('\u0000')
        val requestBuilder = Request.Builder().url(endpoint.toASCIIString())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        if (token.isNotEmpty()) when (protocol) {
            WireProtocol.OPENAI_COMPATIBLE -> requestBuilder.header("Authorization", "Bearer $token")
            WireProtocol.ANTHROPIC -> requestBuilder.header("x-api-key", token)
                .header("anthropic-version", ANTHROPIC_VERSION)
        }
        val callClient = client.newBuilder().callTimeout(config.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
        try {
            callClient.newCall(requestBuilder.build()).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext failure(mapStatus(response.code))
                val body = response.body?.string() ?: return@withContext failure(AiConnectionErrors.responseFormatInvalid)
                val content = runCatching { extractContent(JSONObject(body), protocol) }.getOrNull()
                    ?: return@withContext failure(AiConnectionErrors.responseFormatInvalid)
                val explanation = runCatching { parseExplanation(content) }.getOrNull()
                    ?: return@withContext failure(AiConnectionErrors.responseFormatInvalid)
                AppResult.Success(explanation)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedIOException) {
            failure(AiConnectionErrors.timeout)
        } catch (_: IOException) {
            failure(AiConnectionErrors.offline)
        } catch (_: Exception) {
            failure(AiConnectionErrors.temporaryNetworkFailure)
        } finally {
            payload.fill(0)
        }
    }

    private suspend fun okhttp3.Call.awaitResponse(): okhttp3.Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    continuation.resume(response)
                } catch (_: IllegalStateException) {
                    response.close()
                }
            }
        })
    }

    private fun requestBody(model: String, input: ContextExplanationRequest, protocol: WireProtocol, repair: Boolean = false): String {
        val prompt = JSONObject()
            .put("word", input.word)
            .put("lemma", input.lemma)
            .put("sentence_en", input.sentenceEnglish)
            .put("previous_line_en", input.previousLineEnglish)
            .put("next_line_en", input.nextLineEnglish)
            .put("learner_level", input.learnerLevel)
            .put("ui_language", input.uiLanguage)
            .toString()
        val system = if (repair) {
            "Repair the prior output by generating one valid JSON object matching the required schema exactly. Use only the supplied word and sentence context. Do not add fields. meaning_zh max 24 characters; why_here_zh max 80 characters; safety_flag must be safe or unsafe."
        } else {
            "Return only a JSON object with exactly these fields: meaning_zh, why_here_zh, simple_example_en, simple_example_zh, part_of_speech, confidence, safety_flag. Nullable fields may be null. Keep language child-safe; safety_flag must be safe or unsafe. meaning_zh max 24 characters; why_here_zh max 80 characters."
        }
        return when (protocol) {
            WireProtocol.OPENAI_COMPATIBLE -> JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
                .put("response_format", JSONObject().put("type", "json_object"))
                .toString()
            WireProtocol.ANTHROPIC -> JSONObject()
                .put("model", model).put("max_tokens", 512)
                .put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .toString()
        }
    }

    private fun validationRequest() = ContextExplanationRequest(
        word = "book", lemma = "book", sentenceEnglish = "This is a book.", previousLineEnglish = null,
        nextLineEnglish = null, learnerLevel = "beginner", uiLanguage = "en",
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ANTHROPIC_VERSION = "2023-06-01"

        private fun endpoint(base: URI, protocol: WireProtocol): URI {
            val root = base.toASCIIString().trimEnd('/')
            val suffix = when (protocol) {
                WireProtocol.OPENAI_COMPATIBLE -> if (base.path.orEmpty().trimEnd('/').endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
                WireProtocol.ANTHROPIC -> if (base.path.orEmpty().trimEnd('/').endsWith("/v1")) "/messages" else "/v1/messages"
            }
            return URI(root + suffix)
        }

        private fun extractContent(root: JSONObject, protocol: WireProtocol): String? = when (protocol) {
            WireProtocol.OPENAI_COMPATIBLE -> root.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.takeIf(String::isNotBlank)
            WireProtocol.ANTHROPIC -> root.optJSONArray("content")?.let { blocks ->
                (0 until blocks.length()).asSequence().mapNotNull { blocks.optJSONObject(it)?.optString("text") }
                    .firstOrNull(String::isNotBlank)
            }
        }

        private fun parseExplanation(raw: String): ContextExplanation {
            return ContextExplanationJsonSchema.parse(raw) ?: error("Invalid context explanation schema")
        }

        private fun mapStatus(status: Int): AppError = when (status) {
            401, 403 -> KnownAppErrors.aiAuthFailed
            404 -> AiConnectionErrors.modelNotFound
            402, 429 -> KnownAppErrors.aiBudgetReached
            408, 504 -> AiConnectionErrors.timeout
            else -> if (status >= 500) AiConnectionErrors.temporaryNetworkFailure else AiConnectionErrors.responseFormatInvalid
        }

        private fun failure(error: AppError): AppResult.Failure = AppResult.Failure(error)
    }
}
