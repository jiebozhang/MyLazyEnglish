package com.lazyeng.family.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*

@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onBackground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    OnboardingScreen(state, viewModel::onEvent)
}

@Composable
fun OnboardingScreen(state: OnboardingUiState, onEvent: (OnboardingUiEvent) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = LazyEngSpacing.xxLarge * 16).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(LazyEngSpacing.xLarge), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large)) {
                Text("LazyEng 家庭版", style = MaterialTheme.typography.titleLarge)
                Text(when (state.phase) {
                    OnboardingPhase.CREATE_PROFILE, OnboardingPhase.VERIFY_PIN -> "第 2 步，共 3 步"
                    OnboardingPhase.COMPLETE -> "第 3 步，共 3 步"
                    else -> "第 1 步，共 3 步"
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (state.phase) {
                    OnboardingPhase.RESTORING -> { Text("正在恢复设置…"); LoadingSkeleton() }
                    OnboardingPhase.ERROR -> ErrorState("暂时无法继续", state.message.orEmpty(),
                        retryLabel = "重试", onRetry = { onEvent(OnboardingUiEvent.Retry) })
                    OnboardingPhase.COMPLETE -> EmptyState("准备好了", "孩子档案已保存。",
                        actionLabel = "进入首页", onAction = { onEvent(OnboardingUiEvent.EnterHome) })
                    OnboardingPhase.CREATE_PROFILE -> ProfileForm(state, onEvent)
                    else -> PinForm(state, onEvent)
                }
                if (state.message != null && state.phase != OnboardingPhase.ERROR) {
                    Text(state.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                }
                if (state.busy && state.phase != OnboardingPhase.RESTORING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(); Text("正在安全处理，请稍候…")
                    }
                }
            }
        }
    }
}

@Composable
private fun PinForm(state: OnboardingUiState, onEvent: (OnboardingUiEvent) -> Unit) {
    Text(when (state.phase) {
        OnboardingPhase.CONFIRM_PIN -> "再输入一次 PIN"
        OnboardingPhase.VERIFY_PIN -> "验证家长 PIN"
        else -> "创建家庭与家长 PIN"
    }, style = MaterialTheme.typography.headlineSmall)
    Text(if (state.phase == OnboardingPhase.VERIFY_PIN) "验证后继续创建孩子档案，已填写的内容会保留。" else
        "设置 4 位数字 PIN。忘记 PIN 后，只能清除应用数据重新初始化，已有家庭资料和学习记录将被删除。")
    PinDots(state.enteredCount, modifier = Modifier.testTag("pin-dots"))
    if (state.remainingSeconds > 0) {
        Text("请等待 ${state.remainingSeconds} 秒后再试", Modifier.testTag("pin-lock")
            .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
    }
    val enabled = !state.busy && state.remainingSeconds == 0L
    Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
            Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
                row.forEach { number -> SecondaryButton(number.toString(), { onEvent(OnboardingUiEvent.Digit(number)) },
                    Modifier.weight(1f).testTag("pin-digit-$number"), enabled) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            SecondaryButton("0", { onEvent(OnboardingUiEvent.Digit(0)) }, Modifier.weight(1f).testTag("pin-digit-0"), enabled)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton({ onEvent(OnboardingUiEvent.Backspace) }, Modifier.testTag("pin-backspace"), enabled) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "删除上一位")
                }
            }
        }
    }
    PrimaryButton(if (state.phase == OnboardingPhase.SET_PIN) "继续" else "确认", { onEvent(OnboardingUiEvent.SubmitPin) },
        Modifier.fillMaxWidth().testTag("pin-submit"), enabled && state.enteredCount == 4)
    if (state.phase == OnboardingPhase.CONFIRM_PIN) SecondaryButton("重新设置", { onEvent(OnboardingUiEvent.RestartPin) }, enabled = enabled)
}

@Composable
private fun ProfileForm(state: OnboardingUiState, onEvent: (OnboardingUiEvent) -> Unit) {
    Text("创建孩子档案", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(state.nickname, { onEvent(OnboardingUiEvent.Nickname(it)) },
        Modifier.fillMaxWidth().testTag("profile-nickname"), enabled = !state.busy, label = { Text("昵称") }, singleLine = true)
    Text("头像", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
        listOf("sun" to "太阳", "moon" to "月亮", "star" to "星星").forEach { (id, label) ->
            FilterChip(state.avatar == id, { onEvent(OnboardingUiEvent.Avatar(id)) }, { Text(label) },
                Modifier.heightIn(min = LazyEngTouchTarget.minimum).testTag("avatar-$id"), enabled = !state.busy)
        }
    }
    OutlinedTextField(state.level, { onEvent(OnboardingUiEvent.Level(it)) },
        Modifier.fillMaxWidth().testTag("profile-level"), enabled = !state.busy, label = { Text("英语水平") }, singleLine = true)
    PrimaryButton("保存并进入首页", { onEvent(OnboardingUiEvent.CreateProfile) }, Modifier.fillMaxWidth().testTag("profile-save"),
        enabled = !state.busy && state.nickname.isNotBlank() && state.level.isNotBlank())
}

@Preview(showBackground = true, widthDp = 412, fontScale = 1.3f)
@Composable
private fun PinSetupPreview() = LazyEngTheme { OnboardingScreen(OnboardingUiState(phase = OnboardingPhase.SET_PIN), {}) }
@Preview(showBackground = true, widthDp = 412)
@Composable
private fun ProfilePreview() = LazyEngTheme { OnboardingScreen(OnboardingUiState(phase = OnboardingPhase.CREATE_PROFILE, nickname = "示例学员"), {}) }
@Preview(showBackground = true, widthDp = 412)
@Composable
private fun LockPreview() = LazyEngTheme { OnboardingScreen(OnboardingUiState(phase = OnboardingPhase.VERIFY_PIN, remainingSeconds = 30), {}) }
