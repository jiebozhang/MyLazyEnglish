package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.network.BaseUrlPolicy
import com.lazyeng.family.core.network.LlmConfigValidator
import com.lazyeng.family.core.network.LlmProviderOption
import com.lazyeng.family.core.network.LlmSettingsDraft
import com.lazyeng.family.feature.parent.LlmConnectionResult
import com.lazyeng.family.feature.parent.LlmKeyMetadata
import com.lazyeng.family.feature.parent.LlmSettingsGateway
import com.lazyeng.family.feature.parent.LlmSettingsSnapshot
import com.lazyeng.family.feature.parent.LlmSettingsUiEvent
import com.lazyeng.family.feature.parent.LlmSettingsViewModel
import com.lazyeng.family.feature.parent.LlmSettingsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test

class LlmSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsMaskKeyAndDriveFakeConnectionStateWithoutNetwork() {
        val gateway = UiFakeGateway()
        val dispatchers = object : DispatcherProvider {
            override val io = Dispatchers.Main.immediate
            override val computation = Dispatchers.Main.immediate
            override val main = Dispatchers.Main.immediate
        }
        val viewModel = LlmSettingsViewModel(gateway, LlmConfigValidator(BaseUrlPolicy(false)), dispatchers)
        compose.setContent { LlmSettingsRoute(viewModel) {} }

        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        compose.onNodeWithTag("llm-api-key").performTextInput("fake-device-key-123")
        compose.onNodeWithTag("llm-test-connection").performClick()
        compose.onNodeWithText("正在探测服务响应...").assertIsDisplayed()
        compose.waitUntil(5_000) { viewModel.state.value.connectionPhase.name == "SUCCESS" }
        compose.onNodeWithTag("llm-connection-result").assertIsDisplayed()
        compose.onNodeWithText("连接成功，响应延迟 7 ms · Fake 服务已验证").assertIsDisplayed()
        compose.onNodeWithTag("llm-saved-key-mask").assertDoesNotExist()
        viewModel.onEvent(LlmSettingsUiEvent.ClearDraft)
    }

    @Test fun savedCredentialIsMaskedAndCanOnlyBeChangedInReplacementMode() {
        val gateway = UiFakeGateway(hasSavedKey = true)
        val dispatchers = object : DispatcherProvider {
            override val io = Dispatchers.Main.immediate
            override val computation = Dispatchers.Main.immediate
            override val main = Dispatchers.Main.immediate
        }
        val viewModel = LlmSettingsViewModel(gateway, LlmConfigValidator(BaseUrlPolicy(false)), dispatchers)
        compose.setContent { LlmSettingsRoute(viewModel) {} }
        compose.onNodeWithText("已保存的 API Key：fake****1234").assertIsDisplayed()
        compose.onAllNodesWithText("fake-device-key-123").assertCountEquals(0)
        compose.onNodeWithTag("llm-edit-key").performClick()
        compose.onNodeWithTag("llm-api-key").assertIsDisplayed()
    }

    private class UiFakeGateway(private val hasSavedKey: Boolean = false) : LlmSettingsGateway {
        override suspend fun load() = LlmSettingsSnapshot(
            LlmSettingsDraft(LlmProviderOption.DEEPSEEK, LlmProviderOption.DEEPSEEK.defaultBaseUrl,
                LlmProviderOption.DEEPSEEK.defaultModel), LlmKeyMetadata(hasSavedKey, "fake****1234"),
        )
        override suspend fun keyMetadata(provider: LlmProviderOption) = LlmKeyMetadata(false)
        override suspend fun test(draft: LlmSettingsDraft, key: CharArray?): LlmConnectionResult {
            delay(120)
            return LlmConnectionResult.Success(7, "Fake 服务已验证")
        }
        override suspend fun save(draft: LlmSettingsDraft, key: CharArray?) = true
    }
}
