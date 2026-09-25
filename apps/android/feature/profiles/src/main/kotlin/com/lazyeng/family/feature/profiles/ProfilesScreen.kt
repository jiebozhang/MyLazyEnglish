package com.lazyeng.family.feature.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.core.model.*

@Composable
fun ProfilesRoute(viewModel: ProfilesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    LaunchedEffect(state.phase) { focus.clearFocus() }
    LaunchedEffect(viewModel, focus) {
        viewModel.effects.collect { focus.clearFocus() }
    }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.onBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(state.phase != ProfilesPhase.HOME && state.phase != ProfilesPhase.PICKER) { viewModel.onEvent(ProfilesUiEvent.Back) }
    ProfilesScreen(state, viewModel::onEvent)
}

private fun roleLabel(role: ProfileRole) = when (role) {
    ProfileRole.CHILD -> "孩子"; ProfileRole.ADULT -> "成人"; ProfileRole.PARENT -> "家长"
}

@Composable
fun ProfilesScreen(state: ProfilesUiState, onEvent: (ProfilesUiEvent) -> Unit) {
    val scroll = key(state.phase) { rememberScrollState() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = LazyEngSpacing.xxLarge * 20).fillMaxWidth().verticalScroll(scroll)
                .padding(LazyEngSpacing.xLarge), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large)) {
                Text("LazyEng 家庭版", style = MaterialTheme.typography.titleLarge)
                when (state.phase) {
                    ProfilesPhase.LOADING -> { Text("正在更新档案…"); LoadingSkeleton() }
                    ProfilesPhase.ERROR -> ErrorState("暂时无法更新", state.message.orEmpty(), retryLabel = "重试",
                        onRetry = { onEvent(ProfilesUiEvent.Retry) })
                    ProfilesPhase.HOME -> {
                        Text("首页", style = MaterialTheme.typography.headlineSmall)
                        state.current?.let { profile ->
                            ProfileChoiceCard(profile.nickname, "${roleLabel(profile.role)} · ${profile.englishLevel}",
                                profile.avatarId, true, { onEvent(ProfilesUiEvent.Picker) }, Modifier.testTag("current-profile"))
                        }
                        SecondaryButton("切换档案", { onEvent(ProfilesUiEvent.Picker) }, Modifier.fillMaxWidth().testTag("open-picker"))
                        PrimaryButton("家长成员管理", { onEvent(ProfilesUiEvent.Parent) }, Modifier.fillMaxWidth().testTag("open-parent"))
                        Text("家庭与孩子档案已准备好。学习内容将在后续版本接入。")
                    }
                    ProfilesPhase.PICKER -> {
                        SectionHeader("谁来学习？")
                        if (state.choices.isEmpty()) EmptyState("还没有可用档案", "请家长创建一个档案。")
                        state.choices.forEach { member ->
                            key(member.id) {
                                ProfileChoiceCard(member.nickname, "${roleLabel(member.role)} · ${member.englishLevel}",
                                    member.avatarId, false, { onEvent(ProfilesUiEvent.Select(member.id)) },
                                    Modifier.testTag("choose-${member.id.value}"))
                            }
                        }
                        PrimaryButton("家长成员管理", { onEvent(ProfilesUiEvent.Parent) }, Modifier.fillMaxWidth().testTag("open-parent"))
                    }
                    ProfilesPhase.PIN, ProfilesPhase.DELETE_PIN -> {
                        Text(if (state.phase == ProfilesPhase.PIN) "家长验证" else "再次验证 PIN 后删除",
                            style = MaterialTheme.typography.headlineSmall)
                        state.deleting?.let {
                            Text("删除“${it.nickname}”后，该档案将不再出现在选择器中；其他成员不受影响。")
                        }
                        PinDots(state.enteredCount)
                        if (state.remainingSeconds > 0) Text("请等待 ${state.remainingSeconds} 秒后再试",
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        PinKeypad(state.enteredCount, !state.busy && state.remainingSeconds == 0L,
                            if (state.busy) "验证中…" else if (state.deleting != null) "确认删除" else "确认",
                            { onEvent(ProfilesUiEvent.Digit(it)) }, { onEvent(ProfilesUiEvent.Erase) },
                            { onEvent(ProfilesUiEvent.SubmitPin) })
                        if (state.busy) Text("正在安全处理，请稍候…")
                        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        SecondaryButton("取消", { onEvent(ProfilesUiEvent.Back) }, Modifier.testTag("cancel-pin"))
                    }
                    ProfilesPhase.OVERVIEW -> {
                        SectionHeader("家庭成员")
                        PrimaryButton("新增档案", { onEvent(ProfilesUiEvent.Create) }, Modifier.fillMaxWidth().testTag("create-profile"))
                        state.members.forEach { member ->
                            Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                                ProfileChoiceCard(member.nickname, "${roleLabel(member.role)} · ${member.ageMode} · ${member.englishLevel}",
                                    member.avatarId, false, { onEvent(ProfilesUiEvent.Edit(member.id)) }, actionDescription = "编辑此档案")
                                Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
                                    SecondaryButton("编辑", { onEvent(ProfilesUiEvent.Edit(member.id)) }, Modifier.weight(1f).testTag("edit-${member.id.value}"))
                                    SecondaryButton("删除", { onEvent(ProfilesUiEvent.Delete(member.id)) }, Modifier.weight(1f).testTag("delete-${member.id.value}"))
                                }
                            }
                        }
                        SecondaryButton("退出家长管理", { onEvent(ProfilesUiEvent.Back) }, Modifier.testTag("leave-parent"))
                    }
                    ProfilesPhase.EDIT -> state.editor?.let { editor ->
                        ProfileEditor(editor, onEvent)
                        SecondaryButton("取消", { onEvent(ProfilesUiEvent.Back) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(draft: ProfileDraft, onEvent: (ProfilesUiEvent) -> Unit) {
    fun update(value: ProfileDraft) = onEvent(ProfilesUiEvent.Draft(value))
    Text("档案设置", style = MaterialTheme.typography.headlineSmall)
    ProfileAvatar(draft.avatar, draft.nickname)
    OutlinedTextField(draft.nickname, { update(draft.copy(nickname = it)) },
        Modifier.fillMaxWidth().testTag("profile-nickname"), label = { Text("昵称") }, singleLine = true)
    Text("头像")
    Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
        listOf("sun" to "太阳", "moon" to "月亮", "star" to "星星").forEach { (id, label) ->
            FilterChip(draft.avatar == id, { update(draft.copy(avatar = id)) }, { Text(label) },
                Modifier.heightIn(min = LazyEngTouchTarget.minimum).testTag("avatar-$id"))
        }
    }
    Text("角色")
    Column {
        ProfileRole.entries.forEach { role ->
            FilterChip(draft.role == role, { update(draft.copy(role = role)) }, { Text(roleLabel(role)) },
                Modifier.heightIn(min = LazyEngTouchTarget.minimum).testTag("role-${role.name}"))
        }
    }
    OutlinedTextField(draft.ageMode, { update(draft.copy(ageMode = it)) },
        Modifier.fillMaxWidth().testTag("profile-age"), label = { Text("年龄模式") }, singleLine = true)
    OutlinedTextField(draft.level, { update(draft.copy(level = it)) },
        Modifier.fillMaxWidth().testTag("profile-level"), label = { Text("英语等级") }, singleLine = true)
    PrimaryButton("保存档案", { onEvent(ProfilesUiEvent.Save) }, Modifier.fillMaxWidth().testTag("profile-save"),
        draft.nickname.isNotBlank() && draft.ageMode.isNotBlank() && draft.level.isNotBlank())
}

@Preview(widthDp = 412, showBackground = true, fontScale = 1.3f)
@Composable
private fun PickerPreview() = LazyEngTheme {
    ProfilesScreen(ProfilesUiState(ProfilesPhase.PICKER, choices = listOf(
        ProfileChoice(ProfileId("preview"), "示例学员", "star", ProfileRole.CHILD, "Power Up 2"))), {})
}
@Preview(widthDp = 720, showBackground = true)
@Composable
private fun EmptyPreview() = LazyEngTheme { ProfilesScreen(ProfilesUiState(ProfilesPhase.PICKER), {}) }
@Preview(widthDp = 412, showBackground = true)
@Composable
private fun ErrorPreview() = LazyEngTheme { ProfilesScreen(ProfilesUiState(ProfilesPhase.ERROR, message = "请重试"), {}) }
