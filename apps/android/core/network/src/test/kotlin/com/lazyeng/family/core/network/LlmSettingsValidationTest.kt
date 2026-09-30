package com.lazyeng.family.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsValidationTest {
    @Test fun providerDefaultsUseExpectedEndpointsAndModels() {
        assertEquals("https://api.deepseek.com/v1", LlmProviderOption.DEEPSEEK.newDraft().baseUrl)
        assertEquals("deepseek-flash", LlmProviderOption.DEEPSEEK.newDraft().model)
        assertEquals("https://api.anthropic.com/v1", LlmProviderOption.CLAUDE.newDraft().baseUrl)
    }

    @Test fun internetHttpIsRejectedWhilePrivateOllamaIpv4IsAllowed() {
        val policy = BaseUrlPolicy(false)
        assertNull(policy.validate("http://api.example.test/v1", allowPrivateNetworkHttp = true))
        assertNull(policy.validate("http://192.168.1.20:11434/v1"))
        assertTrue(policy.validate("http://192.168.1.20:11434/v1", allowPrivateNetworkHttp = true) != null)
        assertTrue(policy.validate("https://api.example.test/v1") != null)
    }

    @Test fun nonOllamaConfigurationRequiresKeyAndValidTimeout() {
        val validator = LlmConfigValidator(BaseUrlPolicy(false))
        val draft = LlmProviderOption.DEEPSEEK.newDraft()
        assertTrue(validator.validate(draft.copy(timeoutSeconds = 2), "", false).any { it.field == "apiKey" })
        assertTrue(validator.validate(draft.copy(timeoutSeconds = 2), "fake-test-key", true).any { it.field == "timeout" })
        assertTrue(validator.validate(draft, "fake-test-key", true).isEmpty())
    }

    @Test fun customAliyunCompatibleEndpointAndModelPassValidation() {
        val validator = LlmConfigValidator(BaseUrlPolicy(false))
        val draft = LlmProviderOption.CUSTOM.newDraft().copy(
            baseUrl = "https://workspace-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            model = "qwen3.7-plus",
        )
        assertTrue(validator.validate(draft, "fake-test-key", false).isEmpty())
    }

    @Test fun debugLoopbackExceptionFollowsBuildTypeFlag() {
        val allowed = configuredBaseUrlPolicy().validate("http://127.0.0.1:11434/v1") != null
        assertEquals(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS, allowed)
    }

    @Test fun disabledProviderCanBeSavedWithoutCredentialOrNetworkEndpoint() {
        val validator = LlmConfigValidator(BaseUrlPolicy(false))
        assertTrue(validator.validate(LlmSettingsDraft(enabled = false, baseUrl = "", model = ""), "", false).isEmpty())
    }
}
