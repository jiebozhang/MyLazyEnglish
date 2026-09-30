package com.lazyeng.family.core.network

import com.lazyeng.family.core.common.AiProviderConfig
import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.common.ContextExplanationRequest
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.common.SecureSecretStore
import com.lazyeng.family.core.model.SecretReference
import com.lazyeng.family.core.testing.TestDispatchers
import java.util.concurrent.TimeUnit
import java.net.Proxy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiProviderAdaptersTest {
    @Test
    fun connectionProbeUsesMinimalOneTokenRequestAndDoesNotParseModelPayload() = runTest {
        for (anthropic in listOf(false, true)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{}"))
                val result = provider(anthropic, testScheduler).validateConnection(config(server))
                advanceUntilIdle()
                assertTrue(result is AppResult.Success)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertTrue(request.body.readUtf8().contains("\"max_tokens\":1"))
                assertEquals(if (anthropic) "/v1/messages" else "/v1/chat/completions", request.path)
            }
        }
    }

    @Test
    fun compatibleProviderKeepsNestedV1PrefixWhenBuildingRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val result = provider(false, testScheduler).validateConnection(
                config(server).copy(baseUrl = "http://127.0.0.1:${server.port}/compatible-mode/v1"),
            )
            advanceUntilIdle()
            assertTrue(result is AppResult.Success)
            assertEquals("/compatible-mode/v1/chat/completions", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test
    fun bothAdaptersReturnTheSameProviderNeutralExplanation() = runTest {
        for (anthropic in listOf(false, true)) {
            MockWebServer().use { server ->
                val seen = mutableListOf<String>()
                server.enqueue(MockResponse().setBody(if (anthropic) anthropicResponse() else openAiResponse()))
                val provider = provider(anthropic, testScheduler, seen)
                assertTrue("base=${config(server).baseUrl}", BaseUrlPolicy(true).validate(config(server).baseUrl) != null)
                val result = provider.explainContext(config(server), request())
                advanceUntilIdle()
                val success = result as AppResult.Success
                assertEquals("书", success.value.value.meaningZh)
                assertEquals("book", success.value.value.simpleExampleEnglish)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(if (anthropic) "/v1/messages" else "/v1/chat/completions", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("sentence_en"))
                assertTrue(body.contains("learner_level"))
                assertFalse(body.contains("nickname"))
                assertFalse(body.contains("watch_history"))
                assertFalse(body.contains("complete_history"))
                if (anthropic) {
                    assertEquals("test-only-fake-key", request.getHeader("x-api-key"))
                    assertTrue(request.getHeader("Authorization").isNullOrEmpty())
                } else {
                    assertEquals("Bearer test-only-fake-key", request.getHeader("Authorization"))
                    assertTrue(request.getHeader("x-api-key").isNullOrEmpty())
                }
                assertEquals(listOf("ai_request_started", "ai_request_finished_200"), seen)
            }
        }
    }

    @Test
    fun failureStatusesAndMalformedBodyMapToStableCodes() = runTest {
        val cases = listOf(
            401 to "AI_AUTH_FAILED",
            404 to "AI_MODEL_NOT_FOUND",
            429 to "AI_BUDGET_REACHED",
            408 to "AI_TIMEOUT",
            200 to "AI_RESPONSE_FORMAT_INVALID",
        )
        for ((status, expected) in cases) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody(if (status == 200) "not json" else "{}"))
                val result = provider(false, testScheduler).explainContext(config(server), request())
                advanceUntilIdle()
                assertEquals(expected, (result as AppResult.Failure).error.code.value)
            }
        }
    }

    @Test
    fun timeoutMapsToAiTimeout() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBodyDelay(6, TimeUnit.SECONDS).setBody(openAiResponse()))
            val result = provider(false, testScheduler).explainContext(config(server), request())
            advanceUntilIdle()
            assertEquals("AI_TIMEOUT", (result as AppResult.Failure).error.code.value)
        }
    }

    @Test
    fun cancellingContextRequestCancelsTheOkHttpCall() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async { provider(false, testScheduler).explainContext(config(server), request()) }
            runCurrent()
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun rejectsNonHttpsUnlessExplicitDebugLoopbackPolicyAllowsIt() {
        assertTrue(BaseUrlPolicy(false).validate("http://localhost:9000") == null)
        assertTrue(BaseUrlPolicy(true).validate("http://localhost:9000") != null)
        assertTrue(BaseUrlPolicy(true).validate("http://example.test") == null)
        assertTrue(BaseUrlPolicy(true).validate("https://api.example.test/v1") != null)
        assertTrue(BaseUrlPolicy(false).validate("https://api.example.test/compatible-mode/v1") != null)
        assertTrue(BaseUrlPolicy(true).validate("https://api.example.test/unknown") == null)
        assertTrue(BaseUrlPolicy(false).validate("https://api.example.test/../compatible-mode/v1") == null)
        assertTrue(BaseUrlPolicy(false).validate("https://api.example.test/compatible%2Fmode/v1") == null)
        assertTrue(BaseUrlPolicy(false).validate("http://api.example.test/compatible-mode/v1") == null)
        assertFalse(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS)
        assertTrue(BaseUrlPolicy(false).validate("http://127.0.0.1:9000") == null)
    }

    @Test
    fun logsNeverContainAuthorizationKeyOrBodies() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(openAiResponse()))
            val events = mutableListOf<String>()
            provider(false, testScheduler, events).explainContext(config(server), request())
            advanceUntilIdle()
            val logText = events.joinToString(" ")
            assertFalse(logText.contains("test-only-fake-key"))
            assertFalse(logText.contains("Authorization"))
            assertFalse(logText.contains("private prompt text"))
        }
    }

    private fun provider(
        anthropic: Boolean,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        events: MutableList<String> = mutableListOf(),
    ) = TestDispatchers(scheduler).let { dispatchers ->
        val provider = object : DispatcherProvider {
            override val io = dispatchers.dispatcher
            override val computation = dispatchers.dispatcher
            override val main = dispatchers.dispatcher
        }
        val localClient = okhttp3.OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        if (anthropic) AnthropicProvider(FakeSecrets(), provider, RedactingNetworkObserver(events::add), localClient, BaseUrlPolicy(true))
        else OpenAiCompatibleProvider(FakeSecrets(), provider, RedactingNetworkObserver(events::add), localClient, BaseUrlPolicy(true))
    }

    private fun config(server: MockWebServer) = AiProviderConfig(
        baseUrl = "http://127.0.0.1:${server.port}/v1", model = "fake-model",
        secretReference = SecretReference("test-only-reference"), timeout = kotlin.time.Duration.parse("5s"),
    )

    private fun request() = ContextExplanationRequest(
        word = "book", lemma = "book", sentenceEnglish = "private prompt text", previousLineEnglish = null,
        nextLineEnglish = null, learnerLevel = "beginner", uiLanguage = "zh-CN",
    )

    private class FakeSecrets : SecureSecretStore {
        override suspend fun read(reference: SecretReference) = AppResult.Success("test-only-fake-key".toCharArray())
        override suspend fun write(reference: SecretReference, secret: CharArray) = AppResult.Success(Unit)
        override suspend fun delete(reference: SecretReference) = AppResult.Success(Unit)
    }

    private fun openAiResponse() = """{"choices":[{"message":{"content":"${escapedExplanation()}"}}]}"""
    private fun anthropicResponse() = """{"content":[{"type":"text","text":"${escapedExplanation()}"}]}"""
    private fun escapedExplanation() = "{\\\"meaning_zh\\\":\\\"书\\\",\\\"why_here_zh\\\":\\\"句中指书本\\\",\\\"simple_example_en\\\":\\\"book\\\",\\\"simple_example_zh\\\":\\\"书\\\",\\\"part_of_speech\\\":\\\"noun\\\",\\\"confidence\\\":0.9,\\\"safety_flag\\\":\\\"safe\\\"}"
}
