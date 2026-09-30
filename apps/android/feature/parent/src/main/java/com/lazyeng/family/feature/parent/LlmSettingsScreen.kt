package com.lazyeng.family.feature.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.LazyEngSpacing
import com.lazyeng.family.core.network.LlmProviderOption

@Composable
fun LlmSettingsRoute(viewModel: LlmSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    DisposableEffect(viewModel) { onDispose { viewModel.onEvent(LlmSettingsUiEvent.ClearDraft) } }
    LlmSettingsScreen(state, viewModel::onEvent) { back() }
}

@Composable
fun LlmSettingsScreen(
    state: LlmSettingsUiState,
    onEvent: (LlmSettingsUiEvent) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LazyEngSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("llm-back")) { Text("返回家长控制台") }
            Text("大模型设置", style = MaterialTheme.typography.titleLarge)
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).testTag("llm-loading"))
            return@Column
        }
        state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        var providerExpanded by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { providerExpanded = true }, modifier = Modifier.fillMaxWidth().testTag("llm-provider")) {
                Text(state.draft.provider.label)
            }
            DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                LlmProviderOption.entries.forEach { provider ->
                    DropdownMenuItem(text = { Text(provider.label) }, onClick = {
                        providerExpanded = false
                        onEvent(LlmSettingsUiEvent.ProviderChanged(provider))
                    }, modifier = Modifier.testTag("llm-provider-${provider.name.lowercase()}"))
                }
            }
        }
        OutlinedTextField(state.draft.baseUrl, { onEvent(LlmSettingsUiEvent.BaseUrlChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag("llm-base-url"), label = { Text("Base URL") },
            supportingText = { Text("公网服务必须使用 HTTPS；Ollama 仅支持局域网私有 IPv4 HTTP。") }, singleLine = true)
        OutlinedTextField(state.draft.model, { onEvent(LlmSettingsUiEvent.ModelChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag("llm-model"), label = { Text("模型名称") }, singleLine = true)
        OutlinedTextField(state.draft.timeoutSeconds.takeIf { it > 0 }?.toString().orEmpty(),
            { onEvent(LlmSettingsUiEvent.TimeoutChanged(it)) }, modifier = Modifier.fillMaxWidth().testTag("llm-timeout"),
            label = { Text("请求超时（秒，5–120）") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("启用语境解释", Modifier.weight(1f))
            Switch(checked = state.draft.enabled, onCheckedChange = { onEvent(LlmSettingsUiEvent.EnabledChanged(it)) },
                modifier = Modifier.testTag("llm-enabled"))
        }

        if (state.savedKey.exists && !state.editingKey) {
            Column {
                Text("已保存的 API Key：${state.savedKey.masked ?: "••••••••"}", modifier = Modifier.testTag("llm-saved-key-mask"))
                TextButton(onClick = { onEvent(LlmSettingsUiEvent.BeginKeyEdit) }, modifier = Modifier.testTag("llm-edit-key")) { Text("替换 API Key") }
            }
        } else {
            OutlinedTextField(
                value = state.keyDraft,
                onValueChange = { onEvent(LlmSettingsUiEvent.KeyChanged(it)) },
                modifier = Modifier.fillMaxWidth().testTag("llm-api-key"),
                label = { Text(if (state.editingKey) "新的 API Key" else "API Key") },
                singleLine = true,
                visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { onEvent(LlmSettingsUiEvent.ToggleKeyVisibility) }, modifier = Modifier.testTag("llm-key-visibility")) {
                        Text(if (state.keyVisible) "隐藏" else "显示")
                    }
                },
            )
        }
        state.validationIssues.forEach { issue ->
            Text(issue.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("llm-validation-${issue.field}"))
        }
        state.connectionMessage?.let { message ->
            Text(message, color = if (state.connectionPhase == LlmConnectionPhase.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("llm-connection-result"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
            Button(onClick = { onEvent(LlmSettingsUiEvent.TestConnection) }, enabled = state.canTest,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("llm-test-connection")) {
                if (state.connectionPhase == LlmConnectionPhase.TESTING) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("正在探测服务响应...")
                } else Text("测试连接")
            }
            Button(onClick = { onEvent(LlmSettingsUiEvent.Save) }, enabled = state.canSave,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("llm-save")) {
                if (state.saving) CircularProgressIndicator(strokeWidth = 2.dp) else Text("保存配置")
            }
        }
        state.saveMessage?.let { Text(it, modifier = Modifier.testTag("llm-save-result")) }
        Text("API Key 使用 Android Keystore 加密保存，不会写入日志或普通偏好设置。忘记删除或替换前，密钥不会显示明文。",
            style = MaterialTheme.typography.bodySmall)
    }
}
