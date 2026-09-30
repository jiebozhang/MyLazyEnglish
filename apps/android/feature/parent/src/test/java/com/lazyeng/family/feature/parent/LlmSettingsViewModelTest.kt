package com.lazyeng.family.feature.parent

import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.network.BaseUrlPolicy
import com.lazyeng.family.core.network.LlmConfigValidator
import com.lazyeng.family.core.network.LlmProviderOption
import com.lazyeng.family.core.network.LlmSettingsDraft
import com.lazyeng.family.core.testing.TestDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmSettingsViewModelTest {
    private val dispatchers = TestDispatchers()

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun providerDefaultsLoadAndConnectionHasLoadingThenSuccess() = runTest(dispatchers.dispatcher) {
        Dispatchers.setMain(dispatchers.dispatcher)
        val gateway = FakeGateway()
        val vm = model(gateway)
        advanceUntilIdle()
        assertEquals("https://api.deepseek.com/v1", vm.state.value.draft.baseUrl)
        vm.onEvent(LlmSettingsUiEvent.KeyChanged("fake-test-key-123"))
        vm.onEvent(LlmSettingsUiEvent.TestConnection)
        assertEquals(LlmConnectionPhase.TESTING, vm.state.value.connectionPhase)
        runCurrent()
        assertEquals(LlmConnectionPhase.SUCCESS, vm.state.value.connectionPhase)
        assertTrue(vm.state.value.connectionMessage.orEmpty().contains("18 ms"))
        assertEquals(1, gateway.tests)
        assertFalse(vm.state.value.keyDraft.isNotEmpty())
    }

    @Test fun providerChangeCancelsInflightTestAndCannotShowStaleResult() = runTest(dispatchers.dispatcher) {
        Dispatchers.setMain(dispatchers.dispatcher)
        val gateway = FakeGateway(slow = true)
        val vm = model(gateway)
        advanceUntilIdle()
        vm.onEvent(LlmSettingsUiEvent.KeyChanged("fake-test-key-123"))
        vm.onEvent(LlmSettingsUiEvent.TestConnection)
        runCurrent()
        vm.onEvent(LlmSettingsUiEvent.ProviderChanged(LlmProviderOption.OLLAMA))
        advanceUntilIdle()
        assertEquals(LlmProviderOption.OLLAMA, vm.state.value.draft.provider)
        assertEquals(LlmConnectionPhase.IDLE, vm.state.value.connectionPhase)
        assertTrue(gateway.cancelled)
    }

    @Test fun validConfigurationIsSavedAndKeyBufferIsClearedFromUi() = runTest(dispatchers.dispatcher) {
        Dispatchers.setMain(dispatchers.dispatcher)
        val gateway = FakeGateway()
        val vm = model(gateway)
        advanceUntilIdle()
        vm.onEvent(LlmSettingsUiEvent.KeyChanged("fake-test-key-123"))
        vm.onEvent(LlmSettingsUiEvent.Save)
        advanceUntilIdle()
        assertTrue(gateway.saved)
        assertTrue(vm.state.value.saveMessage.orEmpty().contains("立即生效"))
        assertEquals("", vm.state.value.keyDraft)
    }

    private fun model(gateway: FakeGateway) = LlmSettingsViewModel(gateway,
        LlmConfigValidator(BaseUrlPolicy(false)), object : DispatcherProvider {
            override val io = dispatchers.dispatcher
            override val computation = dispatchers.dispatcher
            override val main = dispatchers.dispatcher
        })

    private class FakeGateway(private val slow: Boolean = false) : LlmSettingsGateway {
        var tests = 0
        var saved = false
        var cancelled = false
        override suspend fun load() = LlmSettingsSnapshot(LlmProviderOption.DEEPSEEK.let {
            LlmSettingsDraft(it, it.defaultBaseUrl, it.defaultModel)
        }, LlmKeyMetadata(false))
        override suspend fun keyMetadata(provider: LlmProviderOption) = LlmKeyMetadata(false)
        override suspend fun test(draft: LlmSettingsDraft, key: CharArray?): LlmConnectionResult {
            tests++
            try {
                if (slow) delay(10_000)
                return LlmConnectionResult.Success(18, "Fake 服务")
            } catch (cancelledException: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw cancelledException
            }
        }
        override suspend fun save(draft: LlmSettingsDraft, key: CharArray?): Boolean { saved = true; return true }
    }
}
