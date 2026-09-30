package com.lazyeng.family.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.network.LlmConfigValidator
import com.lazyeng.family.core.network.LlmProviderOption
import com.lazyeng.family.core.network.LlmSettingsDraft
import com.lazyeng.family.core.network.LlmValidationIssue
import com.lazyeng.family.core.network.newDraft
import com.lazyeng.family.core.common.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LlmKeyMetadata(val exists: Boolean, val masked: String? = null)
data class LlmSettingsSnapshot(val draft: LlmSettingsDraft, val key: LlmKeyMetadata)
sealed interface LlmConnectionResult {
    data class Success(val latencyMs: Long, val confirmation: String) : LlmConnectionResult
    data class Failure(val message: String) : LlmConnectionResult
}

interface LlmSettingsGateway {
    suspend fun load(): LlmSettingsSnapshot
    suspend fun keyMetadata(provider: LlmProviderOption): LlmKeyMetadata
    suspend fun test(draft: LlmSettingsDraft, key: CharArray?): LlmConnectionResult
    suspend fun save(draft: LlmSettingsDraft, key: CharArray?): Boolean
}

enum class LlmConnectionPhase { IDLE, TESTING, SUCCESS, FAILURE }
data class LlmSettingsUiState(
    val loading: Boolean = true,
    val draft: LlmSettingsDraft = LlmProviderOption.DEEPSEEK.newDraft(),
    val keyDraft: String = "",
    val keyVisible: Boolean = false,
    val editingKey: Boolean = false,
    val savedKey: LlmKeyMetadata = LlmKeyMetadata(false),
    val validationIssues: List<LlmValidationIssue> = emptyList(),
    val connectionPhase: LlmConnectionPhase = LlmConnectionPhase.IDLE,
    val connectionMessage: String? = null,
    val saveMessage: String? = null,
    val saving: Boolean = false,
    val loadError: String? = null,
) {
    val canTest: Boolean get() = draft.enabled && !loading && !saving && connectionPhase != LlmConnectionPhase.TESTING && validationIssues.isEmpty()
    val canSave: Boolean get() = !loading && !saving && validationIssues.isEmpty()
}

sealed interface LlmSettingsUiEvent {
    data class ProviderChanged(val provider: LlmProviderOption) : LlmSettingsUiEvent
    data class BaseUrlChanged(val value: String) : LlmSettingsUiEvent
    data class ModelChanged(val value: String) : LlmSettingsUiEvent
    data class TimeoutChanged(val value: String) : LlmSettingsUiEvent
    data class KeyChanged(val value: String) : LlmSettingsUiEvent
    data class EnabledChanged(val value: Boolean) : LlmSettingsUiEvent
    data object ToggleKeyVisibility : LlmSettingsUiEvent
    data object BeginKeyEdit : LlmSettingsUiEvent
    data object TestConnection : LlmSettingsUiEvent
    data object Save : LlmSettingsUiEvent
    data object ClearDraft : LlmSettingsUiEvent
}

class LlmSettingsViewModel(
    private val gateway: LlmSettingsGateway,
    private val validator: LlmConfigValidator,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LlmSettingsUiState())
    val state = mutableState.asStateFlow()
    private var connectionJob: Job? = null

    init { refresh() }

    fun onEvent(event: LlmSettingsUiEvent) {
        when (event) {
            is LlmSettingsUiEvent.ProviderChanged -> changeProvider(event.provider)
            is LlmSettingsUiEvent.BaseUrlChanged -> updateDraft { copy(baseUrl = event.value) }
            is LlmSettingsUiEvent.ModelChanged -> updateDraft { copy(model = event.value) }
            is LlmSettingsUiEvent.TimeoutChanged -> updateDraft { copy(timeoutSeconds = event.value.toIntOrNull() ?: -1) }
            is LlmSettingsUiEvent.KeyChanged -> {
                cancelConnection()
                mutableState.update { old -> old.copy(keyDraft = event.value,
                    connectionPhase = LlmConnectionPhase.IDLE, connectionMessage = null,
                    validationIssues = validate(old.draft, event.value, old.savedKey.exists)) }
            }
            is LlmSettingsUiEvent.EnabledChanged -> updateDraft { copy(enabled = event.value) }
            LlmSettingsUiEvent.ToggleKeyVisibility -> mutableState.update { it.copy(keyVisible = !it.keyVisible) }
            LlmSettingsUiEvent.BeginKeyEdit -> {
                cancelConnection()
                mutableState.update { it.copy(editingKey = true, keyDraft = "", keyVisible = false,
                    connectionPhase = LlmConnectionPhase.IDLE, connectionMessage = null) }
            }
            LlmSettingsUiEvent.TestConnection -> testConnection()
            LlmSettingsUiEvent.Save -> save()
            LlmSettingsUiEvent.ClearDraft -> clearDraft()
        }
    }

    private fun refresh() = viewModelScope.launch(dispatchers.io) {
        runCatching { gateway.load() }.onSuccess { loaded ->
            mutableState.update { it.copy(loading = false, draft = loaded.draft, savedKey = loaded.key,
                editingKey = false, validationIssues = validate(loaded.draft, "", loaded.key.exists), loadError = null) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            mutableState.update { it.copy(loading = false, loadError = "无法读取已保存的设置，请重试。") }
        }
    }

    private fun changeProvider(provider: LlmProviderOption) {
        connectionJob?.cancel()
        val draft = provider.newDraft()
        mutableState.update { it.copy(draft = draft, keyDraft = "", keyVisible = false, editingKey = false,
            connectionPhase = LlmConnectionPhase.IDLE, connectionMessage = null, saveMessage = null,
            validationIssues = validate(draft, "", false), savedKey = LlmKeyMetadata(false)) }
        connectionJob = viewModelScope.launch(dispatchers.io) {
            val metadata = runCatching { gateway.keyMetadata(provider) }.getOrDefault(LlmKeyMetadata(false))
            mutableState.update { current ->
                if (current.draft.provider == provider) current.copy(savedKey = metadata,
                    validationIssues = validate(current.draft, current.keyDraft, metadata.exists)) else current
            }
        }
    }

    private fun updateDraft(transform: LlmSettingsDraft.() -> LlmSettingsDraft) {
        cancelConnection()
        mutableState.update { old ->
            val draft = old.draft.transform()
            old.copy(draft = draft, connectionPhase = LlmConnectionPhase.IDLE, connectionMessage = null,
                saveMessage = null, validationIssues = validate(draft, old.keyDraft, old.savedKey.exists))
        }
    }

    private fun testConnection() {
        val snapshot = state.value
        if (snapshot.validationIssues.isNotEmpty() || snapshot.connectionPhase == LlmConnectionPhase.TESTING) return
        val draft = snapshot.draft
        val secret = snapshot.keyDraft.takeIf(String::isNotEmpty)?.toCharArray()
        mutableState.update { it.copy(connectionPhase = LlmConnectionPhase.TESTING, connectionMessage = null, saveMessage = null) }
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch(dispatchers.io) {
            val result = try { gateway.test(draft, secret) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LlmConnectionResult.Failure("探测失败，请检查网络、地址和模型名称。") }
            finally { secret?.fill('\u0000') }
            val keyMetadata = runCatching { gateway.keyMetadata(draft.provider) }.getOrDefault(snapshot.savedKey)
            mutableState.update { current ->
                if (current.draft != draft) current else when (result) {
                    is LlmConnectionResult.Success -> current.copy(connectionPhase = LlmConnectionPhase.SUCCESS,
                        connectionMessage = "连接成功，响应延迟 ${result.latencyMs} ms · ${result.confirmation}",
                        keyDraft = "", keyVisible = false, editingKey = false, savedKey = keyMetadata,
                        validationIssues = validate(current.draft, "", keyMetadata.exists))
                    is LlmConnectionResult.Failure -> current.copy(connectionPhase = LlmConnectionPhase.FAILURE,
                        connectionMessage = result.message, keyDraft = "", keyVisible = false, editingKey = false,
                        savedKey = keyMetadata, validationIssues = validate(current.draft, "", keyMetadata.exists))
                }
            }
        }
    }

    private fun save() {
        val snapshot = state.value
        if (snapshot.validationIssues.isNotEmpty() || snapshot.saving) return
        cancelConnection()
        val draft = snapshot.draft
        val secret = snapshot.keyDraft.takeIf(String::isNotEmpty)?.toCharArray()
        mutableState.update { it.copy(saving = true, connectionPhase = LlmConnectionPhase.IDLE, connectionMessage = null, saveMessage = null) }
        viewModelScope.launch(dispatchers.io) {
            val saved = try { gateway.save(draft, secret) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { secret?.fill('\u0000') }
            if (!saved) {
                mutableState.update { it.copy(saving = false, saveMessage = "保存失败。请检查安全存储是否可用；密钥不会以明文保存。") }
            } else {
                val loaded = runCatching { gateway.load() }.getOrNull()
                mutableState.update { it.copy(saving = false, keyDraft = "", keyVisible = false, editingKey = false,
                    savedKey = loaded?.key ?: it.savedKey, saveMessage = "配置已安全保存并立即生效。",
                    validationIssues = validate(draft, "", (loaded?.key ?: it.savedKey).exists)) }
            }
        }
    }

    private fun clearDraft() {
        cancelConnection()
        mutableState.update { it.copy(keyDraft = "", keyVisible = false, editingKey = false) }
    }

    private fun cancelConnection() {
        connectionJob?.cancel()
        connectionJob = null
    }

    override fun onCleared() { connectionJob?.cancel(); clearDraft() }

    private fun validate(draft: LlmSettingsDraft, key: CharSequence, saved: Boolean) = validator.validate(draft, key, saved)
}
