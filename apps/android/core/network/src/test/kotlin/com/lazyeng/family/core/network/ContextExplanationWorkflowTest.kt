package com.lazyeng.family.core.network

import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.testing.TestDispatchers
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextExplanationWorkflowTest {
    @Test fun schemaRejectsInvalidJsonUnknownFieldsAndBounds() {
        assertNull(ContextExplanationJsonSchema.parse("not json"))
        assertNull(ContextExplanationJsonSchema.parse(json(meaning = "字".repeat(25))))
        assertNull(ContextExplanationJsonSchema.parse(json().replace("\"safety_flag\":\"safe\"", "\"safety_flag\":\"unsafe\",\"extra\":1")))
        assertEquals("unsafe", ContextExplanationJsonSchema.parse(json().replace("\"safety_flag\":\"safe\"", "\"safety_flag\":\"unsafe\""))?.safetyFlag)
        assertEquals("跑", ContextExplanationJsonSchema.parse(json())?.meaningZh)
    }

    @Test fun sameKeyThreeConcurrentCallsShareOneProviderRequestAndThenHitCache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeProvider(gate = gate)
        val setup = setup(provider)
        val calls = List(3) { async { setup.useCase.execute(input()) } }
        runCurrent()
        assertEquals(1, provider.explainCalls.get())
        gate.complete(Unit)
        advanceUntilIdle()
        calls.forEach { assertEquals("跑", it.await().explanation?.meaningZh) }
        val cached = setup.useCase.execute(input())
        assertTrue(cached.cacheHit)
        assertEquals(1, provider.explainCalls.get())
        assertTrue(setup.usage.values.values.any { it.cacheHits == 1 })
    }

    @Test fun cancellingLastObserverCancelsTheSharedProviderRequest() = runTest {
        val provider = FakeProvider(waitUntilCancelled = true)
        val setup = setup(provider)
        val caller = launch { setup.useCase.execute(input()) }
        runCurrent()
        assertEquals(1, provider.explainCalls.get())
        caller.cancelAndJoin()
        runCurrent()
        assertTrue(provider.providerCancelled)
        assertTrue(setup.content.rows.isEmpty())
    }

    @Test fun cacheKeySeparatesFamilyLemmaSentenceLineLevelLanguagePromptProviderAndModel() = runTest {
        val provider = FakeProvider()
        val cache = MemoryContent()
        val usage = MemoryUsage()
        val base = useCase(provider, cache, usage, prompt = "prompt-1")
        base.execute(input())
        base.execute(input())
        assertEquals(1, provider.explainCalls.get())
        val variations = listOf(
            input(family = "family-b"), input(lemma = "jog"), input(sentence = "A changed sentence."),
            input(lineVersion = "line-v2"), input(level = "Power Up 2"), input(language = "en"), input(providerId = "anthropic"),
        )
        variations.forEach { base.execute(it) }
        useCase(provider, cache, usage, model = "other-model", prompt = "prompt-1").execute(input())
        useCase(provider, cache, usage, model = "fake-model", prompt = "prompt-2").execute(input())
        assertEquals(10, provider.explainCalls.get())
    }

    @Test fun invalidFirstOutputGetsExactlyOneRepairAndUnsafeOrInvalidRepairIsNotCached() = runTest {
        val provider = FakeProvider(
            first = explanation("超长".repeat(15)), repair = explanation("也超长".repeat(15)),
        )
        val setup = setup(provider)
        val outcome = setup.useCase.execute(input(local = "local definition"))
        assertNull(outcome.explanation)
        assertEquals("local definition", outcome.localMeaning)
        assertEquals(1, provider.explainCalls.get())
        assertEquals(1, provider.repairCalls.get())
        assertTrue(setup.content.rows.isEmpty())
    }

    @Test fun oneRepairCanRecoverButUnsafeOutputNeverBecomesReady() = runTest {
        val repairedProvider = FakeProvider(first = explanation("超长".repeat(15)), repair = explanation("跑"))
        val repaired = setup(repairedProvider)
        assertEquals("跑", repaired.useCase.execute(input()).explanation?.meaningZh)
        assertEquals(1, repairedProvider.repairCalls.get())
        assertEquals(1, repaired.content.rows.size)

        val unsafeProvider = FakeProvider(first = explanation("跑", safety = "unsafe"), repair = explanation("跑", safety = "unsafe"))
        val unsafe = setup(unsafeProvider)
        val rejected = unsafe.useCase.execute(input(local = "本地义"))
        assertNull(rejected.explanation)
        assertEquals("本地义", rejected.localMeaning)
        assertTrue(unsafe.content.rows.isEmpty())
    }

    @Test fun failedRegenerationKeepsThePreviousSafeVersion() = runTest {
        val state = setup(FakeProvider())
        assertEquals("跑", state.useCase.execute(input()).explanation?.meaningZh)
        val refreshed = useCase(FakeProvider(first = explanation("不合法".repeat(12)), repair = explanation("仍不合法".repeat(12))),
            state.content, state.usage, prompt = "context-explanation-v2").execute(input(local = "本地义"))
        assertEquals("跑", refreshed.explanation?.meaningZh)
        assertEquals("本地义", refreshed.localMeaning)
        assertEquals(1, state.content.rows.size)
    }

    private fun TestScope.setup(provider: FakeProvider) = Setup(MemoryContent(), MemoryUsage()).let { state ->
        state.useCase = useCase(provider, state.content, state.usage)
        state
    }

    private fun TestScope.useCase(provider: FakeProvider, content: MemoryContent, usage: MemoryUsage, model: String = "fake-model", prompt: String = "context-explanation-v1") =
        testScheduler.let { scheduler ->
            val dispatchers = TestDispatchers(scheduler)
            val providerDispatcher = object : DispatcherProvider {
                override val io = dispatchers.dispatcher
                override val computation = dispatchers.dispatcher
                override val main = dispatchers.dispatcher
            }
            ContextExplanationUseCase(provider, AiProviderConfig("https://api.example.test", model,
                SecretReference("fake-reference"), kotlin.time.Duration.parse("5s")), content, MemoryJobs(), usage,
                providerDispatcher, backgroundScope, java.time.Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), java.time.ZoneOffset.UTC), prompt)
        }

    private fun input(
        family: String = "family-a", lemma: String = "run", sentence: String = "She is running home.",
        lineVersion: String = "line-v1", level: String = "Power Up Starter-2", language: String = "zh-CN",
        providerId: String = "openai_compatible", local: String? = null,
    ) = ContextExplanationInput(FamilyId(family), ContextExplanationRequest("running", lemma, sentence, null, null, level, language),
        lineVersion, providerId, local)

    private fun json(meaning: String = "跑") = """{"meaning_zh":"$meaning","why_here_zh":"句中指跑","simple_example_en":null,"simple_example_zh":null,"part_of_speech":"verb","confidence":0.9,"safety_flag":"safe"}"""
    private fun explanation(meaning: String, safety: String = "safe") = ContextExplanation(meaning, "句中指跑", null, null, "verb", .9f, safety)

    private class Setup(val content: MemoryContent, val usage: MemoryUsage) { lateinit var useCase: ContextExplanationUseCase }
    private class MemoryContent : AiContentRepository {
        val rows = mutableMapOf<Pair<String, String>, AiGeneratedContent>()
        override suspend fun findContent(familyId: FamilyId, cacheKey: CacheKey) = rows[familyId.value to cacheKey.value]
        override suspend fun findLatestReady(familyId: FamilyId, inputReference: String) = rows.values.lastOrNull {
            it.inputReference == inputReference && rows.any { row -> row.key.first == familyId.value && row.value.id == it.id } &&
                it.status == GeneratedContentStatus.Ready && it.safetyFlag == "safe"
        }
        override suspend fun saveContent(familyId: FamilyId, content: AiGeneratedContent) { rows[familyId.value to content.cacheKey.value] = content }
    }
    private class MemoryJobs : AiJobRepository {
        private val rows = mutableMapOf<Pair<String, String>, AiJob>()
        override suspend fun getJob(familyId: FamilyId, jobId: AiJobId) = rows[familyId.value to jobId.value]
        override suspend fun saveJob(familyId: FamilyId, job: AiJob) { rows[familyId.value to job.id.value] = job }
    }
    private class MemoryUsage : AiBudgetUsageRepository {
        val values = mutableMapOf<Triple<String, String, String>, AiBudgetUsage>()
        override suspend fun read(familyId: FamilyId, period: String, windowKey: String) = values[Triple(familyId.value, period, windowKey)]
        override suspend fun record(familyId: FamilyId, period: String, windowKey: String, usageBucket: String, cacheHit: Boolean) {
            val key = Triple(familyId.value, period, windowKey)
            val old = values[key]
            values[key] = AiBudgetUsage(familyId, period, windowKey, (old?.requestCount ?: 0) + if (cacheHit) 0 else 1,
                (old?.cacheHits ?: 0) + if (cacheHit) 1 else 0, usageBucket, old?.requestLimit, old?.usageLimit)
        }
        override suspend fun reserveRequest(familyId: FamilyId, dailyWindow: String, monthlyWindow: String) {
            val daily = values[Triple(familyId.value, "DAILY", dailyWindow)]
            val monthly = values[Triple(familyId.value, "MONTHLY", monthlyWindow)]
            check(daily?.requestLimit?.let { daily.requestCount < it } != false)
            check(monthly?.requestLimit?.let { monthly.requestCount < it } != false)
            record(familyId, "DAILY", dailyWindow, "UNKNOWN", false)
            record(familyId, "MONTHLY", monthlyWindow, "UNKNOWN", false)
        }
        override suspend fun updateUsageBucket(familyId: FamilyId, period: String, windowKey: String, usageBucket: String) {
            val key = Triple(familyId.value, period, windowKey); val old = values[key] ?: return
            values[key] = old.copy(usageBucket = if (old.usageBucket == "UNKNOWN" || old.usageBucket == usageBucket) usageBucket else "MIXED")
        }
        override suspend fun configure(familyId: FamilyId, period: String, windowKey: String, requestLimit: Int?, usageLimit: Int?) {
            val key = Triple(familyId.value, period, windowKey); val old = values[key]
            values[key] = AiBudgetUsage(familyId, period, windowKey, old?.requestCount ?: 0, old?.cacheHits ?: 0, old?.usageBucket ?: "", requestLimit, usageLimit)
        }
    }
    private class FakeProvider(
        private val first: ContextExplanation = ContextExplanation("跑", "句中指跑", null, null, "verb", .9f, "safe"),
        private val repair: ContextExplanation = ContextExplanation("跑", "句中指跑", null, null, "verb", .9f, "safe"),
        private val gate: CompletableDeferred<Unit>? = null,
        private val waitUntilCancelled: Boolean = false,
    ) : AiProvider {
        val explainCalls = AtomicInteger(); val repairCalls = AtomicInteger()
        @Volatile var providerCancelled = false
        override suspend fun validateConnection(config: AiProviderConfig) = AppResult.Success(Unit)
        override suspend fun explainContext(config: AiProviderConfig, request: ContextExplanationRequest): AppResult<AiProviderResult<ContextExplanation>> {
            explainCalls.incrementAndGet()
            try {
                if (waitUntilCancelled) awaitCancellation() else gate?.await()
            } finally {
                if (waitUntilCancelled) providerCancelled = true
            }
            yield(); return success(first, config)
        }
        override suspend fun repairContext(config: AiProviderConfig, request: ContextExplanationRequest): AppResult<AiProviderResult<ContextExplanation>> {
            repairCalls.incrementAndGet(); return success(repair, config)
        }
        private fun success(value: ContextExplanation, config: AiProviderConfig) = AppResult.Success(AiProviderResult(
            "request-id", AiFeature.CONTEXT_EXPLANATION, value, "fake", config.model, "prompt-v1", false, 1, "SMALL", value.safetyFlag))
    }
}
