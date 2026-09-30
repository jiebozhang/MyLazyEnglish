package com.lazyeng.family.core.network

import com.lazyeng.family.core.common.AiProvider
import com.lazyeng.family.core.common.AiProviderConfig
import com.lazyeng.family.core.common.AiProviderResult
import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.common.ContextExplanation
import com.lazyeng.family.core.common.ContextExplanationRequest
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.AiBudgetUsageRepository
import com.lazyeng.family.core.model.AiCacheDimensions
import com.lazyeng.family.core.model.AiContentRepository
import com.lazyeng.family.core.model.AiFeature
import com.lazyeng.family.core.model.AiGeneratedContent
import com.lazyeng.family.core.model.AiJob
import com.lazyeng.family.core.model.AiJobId
import com.lazyeng.family.core.model.AiJobRepository
import com.lazyeng.family.core.model.AiJobStatus
import com.lazyeng.family.core.model.CacheKey
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.GeneratedContentId
import com.lazyeng.family.core.model.GeneratedContentStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ContextExplanationInput(
    val familyId: FamilyId, val request: ContextExplanationRequest, val lineVersion: String,
    val providerId: String, val localMeaning: String?, val forceRegenerate: Boolean = false,
)
data class ContextExplanationOutcome(
    val explanation: ContextExplanation?, val localMeaning: String?, val cacheHit: Boolean, val errorCode: String? = null,
)

/** Closed JSON Schema contract for the PRD 8.12 response. */
object ContextExplanationJsonSchema {
    private val required = setOf(
        "meaning_zh", "why_here_zh", "simple_example_en", "simple_example_zh", "part_of_speech", "confidence", "safety_flag",
    )
    val schema: JSONObject = JSONObject().put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray(required.toList()))
        .put("properties", JSONObject()
            .put("meaning_zh", JSONObject().put("type", "string").put("maxLength", 24))
            .put("why_here_zh", JSONObject().put("type", "string").put("maxLength", 80))
            .put("simple_example_en", nullableType("string"))
            .put("simple_example_zh", nullableType("string"))
            .put("part_of_speech", nullableType("string"))
            .put("confidence", JSONObject().put("type", JSONArray(listOf("number", "null"))).put("minimum", 0).put("maximum", 1))
            .put("safety_flag", JSONObject().put("type", "string").put("enum", JSONArray(listOf("safe", "unsafe")))))

    fun parse(raw: String): ContextExplanation? = runCatching {
        val json = JSONObject(raw)
        require(json.keys().asSequence().toSet() == required)
        val meaning = json.get("meaning_zh") as? String ?: error("meaning_zh must be a string")
        val why = json.get("why_here_zh") as? String ?: error("why_here_zh must be a string")
        require(meaning.isNotBlank() && meaning.length <= 24 && why.isNotBlank() && why.length <= 80)
        val confidence = if (json.isNull("confidence")) null else json.getDouble("confidence").toFloat()
        require(confidence == null || confidence in 0f..1f)
        val safety = json.get("safety_flag") as? String ?: error("safety_flag must be a string")
        require(safety == "safe" || safety == "unsafe")
        ContextExplanation(meaning.cleaned(24), why.cleaned(80), json.nullableString("simple_example_en")?.cleaned(500),
            json.nullableString("simple_example_zh")?.cleaned(500), json.nullableString("part_of_speech")?.cleaned(80), confidence, safety)
    }.getOrNull()

    fun encode(value: ContextExplanation): String = JSONObject()
        .put("meaning_zh", value.meaningZh.cleaned(24)).put("why_here_zh", value.whyHereZh.cleaned(80))
        .put("simple_example_en", value.simpleExampleEnglish?.cleaned(500) ?: JSONObject.NULL)
        .put("simple_example_zh", value.simpleExampleChinese?.cleaned(500) ?: JSONObject.NULL)
        .put("part_of_speech", value.partOfSpeech?.cleaned(80) ?: JSONObject.NULL)
        .put("confidence", value.confidence ?: JSONObject.NULL).put("safety_flag", value.safetyFlag ?: "unsafe").toString()

    private fun nullableType(type: String) = JSONObject().put("type", JSONArray(listOf(type, "null")))
    private fun String.cleaned(max: Int) = replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().take(max)
    private fun JSONObject.nullableString(key: String): String? {
        val value = get(key)
        require(value === JSONObject.NULL || value is String)
        return (value as? String)?.takeIf(String::isNotBlank)
    }
}

/** Family-scoped cache and single-flight coordinator. Rejected model output is never persisted. */
class ContextExplanationUseCase(
    private val provider: AiProvider, private val config: AiProviderConfig, private val content: AiContentRepository,
    private val jobs: AiJobRepository, private val usage: AiBudgetUsageRepository,
    private val dispatchers: DispatcherProvider, private val appScope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(), private val promptVersion: String = "context-explanation-v1",
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ContextExplanationOutcome>>()
    private val inFlightWaiters = mutableMapOf<String, Int>()

    suspend fun execute(input: ContextExplanationInput): ContextExplanationOutcome {
        val request = input.request.sanitized()
        val now = Instant.now(clock)
        val contextHash = sha256(listOf(request.previousLineEnglish.orEmpty(), request.sentenceEnglish, request.nextLineEnglish.orEmpty()).joinToString("\u001f"))
        val inputReference = sha256(listOf(request.word, request.lemma, request.sentenceEnglish,
            request.previousLineEnglish.orEmpty(), request.nextLineEnglish.orEmpty(), request.learnerLevel, request.uiLanguage).joinToString("\u001f"))
        val key = AiCacheDimensions(input.familyId, request.lemma.trim().lowercase(), contextHash,
            input.lineVersion, request.learnerLevel, request.uiLanguage, promptVersion, input.providerId, config.model).key()
        if (!input.forceRegenerate) {
            content.findContent(input.familyId, CacheKey(key))?.let { row ->
                val cached = row.outputJson?.let(ContextExplanationJsonSchema::parse)
                if (row.status == GeneratedContentStatus.Ready && row.safetyFlag == "safe" && cached?.safetyFlag == "safe") {
                    recordCacheHit(input.familyId, now)
                    return ContextExplanationOutcome(cached, input.localMeaning, true)
                }
            }
        }
        val shared = mutex.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                inFlightWaiters[key] = (inFlightWaiters[key] ?: 0) + 1
                existing
            } else {
                appScope.async(dispatchers.io) { generate(input, request, key, inputReference) }.also { created ->
                    inFlight[key] = created
                    inFlightWaiters[key] = 1
                    created.invokeOnCompletion {
                        appScope.launch { mutex.withLock {
                            if (inFlight[key] === created) {
                                inFlight.remove(key)
                                inFlightWaiters.remove(key)
                            }
                        } }
                    }
                }
            }
        }
        try {
            return shared.await()
        } finally {
            mutex.withLock {
                val waiters = inFlightWaiters[key] ?: 0
                if (waiters <= 1) {
                    inFlightWaiters.remove(key)
                    if (inFlight[key] === shared) inFlight.remove(key)
                    if (!shared.isCompleted) shared.cancel(CancellationException("No active context explanation observers"))
                } else {
                    inFlightWaiters[key] = waiters - 1
                }
            }
        }
    }

    private suspend fun generate(input: ContextExplanationInput, request: ContextExplanationRequest, key: String, inputReference: String): ContextExplanationOutcome =
        withContext(dispatchers.io) {
            val now = Instant.now(clock)
            val prior = content.findLatestReady(input.familyId, inputReference)
                ?.takeIf { it.status == GeneratedContentStatus.Ready && it.safetyFlag == "safe" }
                ?.outputJson?.let(ContextExplanationJsonSchema::parse)
            val id = AiJobId(key)
            jobs.saveJob(input.familyId, AiJob(id, AiFeature.CONTEXT_EXPLANATION, AiJobStatus.Unknown("RUNNING"), 0f, null, null))
            try {
                reserveRequest(input.familyId, now)
            } catch (_: IllegalStateException) {
                jobs.saveJob(input.familyId, AiJob(id, AiFeature.CONTEXT_EXPLANATION, AiJobStatus.Unknown("FAILED"), 100f, "AI_BUDGET_REACHED", null))
                return@withContext ContextExplanationOutcome(prior, input.localMeaning, false, "AI_BUDGET_REACHED")
            }

            val first = provider.explainContext(config, request)
            val firstProviderResult = (first as? AppResult.Success)?.value
            firstProviderResult?.usageBucket?.let { updateUsageBucket(input.familyId, Instant.now(clock), it) }
            val firstValue = firstProviderResult?.value?.takeIf(::isSafeAndValid)
            val shouldRepair = firstProviderResult != null && firstValue == null
            val repairBudgetAvailable = if (shouldRepair) runCatching {
                reserveRequest(input.familyId, Instant.now(clock))
            }.isSuccess else false
            val repair = if (repairBudgetAvailable) provider.repairContext(config, request) else null
            val repairResult = (repair as? AppResult.Success)?.value
            repairResult?.usageBucket?.let { updateUsageBucket(input.familyId, Instant.now(clock), it) }
            val accepted = firstValue ?: repairResult?.value?.takeIf(::isSafeAndValid)
            if (accepted == null) {
                val error = when {
                    firstProviderResult == null -> (first as? AppResult.Failure)?.error?.code?.value ?: "AI_PROVIDER_FAILED"
                    !repairBudgetAvailable && firstValue == null -> "AI_BUDGET_REACHED"
                    repair is AppResult.Failure -> repair.error.code.value
                    first is AppResult.Failure -> first.error.code.value
                    else -> "AI_RESPONSE_FORMAT_INVALID"
                }
                jobs.saveJob(input.familyId, AiJob(id, AiFeature.CONTEXT_EXPLANATION, AiJobStatus.Unknown("FAILED"), 100f, error, null))
                return@withContext ContextExplanationOutcome(prior, input.localMeaning, false, error)
            }
            val providerResult: AiProviderResult<ContextExplanation>? = if (firstValue != null) firstProviderResult else repairResult
            val oldVersion = content.findContent(input.familyId, CacheKey(key))?.version?.toIntOrNull() ?: 0
            content.saveContent(input.familyId, AiGeneratedContent(
                GeneratedContentId(UUID.randomUUID().toString()), AiFeature.CONTEXT_EXPLANATION, CacheKey(key),
                inputReference, ContextExplanationJsonSchema.encode(accepted),
                "context-explanation-json-v1", (oldVersion + 1).toString(), GeneratedContentStatus.Ready, "safe",
            ))
            jobs.saveJob(input.familyId, AiJob(id, AiFeature.CONTEXT_EXPLANATION, AiJobStatus.Unknown("SUCCEEDED"), 100f, null, providerResult?.usageBucket))
            ContextExplanationOutcome(accepted, input.localMeaning, false)
        }

    private suspend fun reserveRequest(familyId: FamilyId, now: Instant) {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        usage.reserveRequest(familyId, date.toString(), date.withDayOfMonth(1).toString())
    }

    private suspend fun recordCacheHit(familyId: FamilyId, now: Instant) {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        usage.record(familyId, "DAILY", date.toString(), "CACHE", true)
        usage.record(familyId, "MONTHLY", date.withDayOfMonth(1).toString(), "CACHE", true)
    }

    private suspend fun updateUsageBucket(familyId: FamilyId, now: Instant, bucket: String) {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        usage.updateUsageBucket(familyId, "DAILY", date.toString(), bucket)
        usage.updateUsageBucket(familyId, "MONTHLY", date.withDayOfMonth(1).toString(), bucket)
    }

    private fun isSafeAndValid(value: ContextExplanation) = value.safetyFlag == "safe" &&
        value.meaningZh.isNotBlank() && value.meaningZh.length <= 24 && value.whyHereZh.isNotBlank() && value.whyHereZh.length <= 80 &&
        (value.confidence?.let { it in 0f..1f } != false)

    private fun ContextExplanationRequest.sanitized() = copy(
        word = word.cleaned(120), lemma = lemma.cleaned(120), sentenceEnglish = sentenceEnglish.cleaned(1_000),
        previousLineEnglish = previousLineEnglish?.cleaned(500), nextLineEnglish = nextLineEnglish?.cleaned(500),
        learnerLevel = learnerLevel.cleaned(80), uiLanguage = uiLanguage.cleaned(20),
    ).also { require(it.word.isNotBlank() && it.lemma.isNotBlank() && it.sentenceEnglish.isNotBlank()) }

    private fun AiCacheDimensions.key() = sha256(listOf(
        familyId.value, lemma, sentenceHash, lineVersion, learnerLevel, uiLanguage, promptVersion, provider, model,
    ).joinToString("\u001f"))
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun String.cleaned(max: Int) = replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().take(max)
}
