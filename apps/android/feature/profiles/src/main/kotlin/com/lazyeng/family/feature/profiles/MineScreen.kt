package com.lazyeng.family.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.ErrorState
import com.lazyeng.family.core.designsystem.LazyEngColors
import com.lazyeng.family.core.designsystem.LazyEngShapes
import com.lazyeng.family.core.designsystem.LazyEngSpacing
import com.lazyeng.family.core.designsystem.LoadingSkeleton
import com.lazyeng.family.core.designsystem.PrimaryButton
import com.lazyeng.family.core.designsystem.ProfileAvatar
import com.lazyeng.family.core.designsystem.SecondaryButton
import com.lazyeng.family.core.designsystem.SectionHeader

private fun ageModeLabel(value: String): String = when (value.lowercase()) {
    "child" -> "儿童模式"
    "adult" -> "成人模式"
    else -> "年龄模式：$value"
}

@Composable
fun MineRoute(
    viewModel: MineViewModel,
    listState: LazyListState,
    onEffect: (MineUiEffect) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val effectHandler by rememberUpdatedState(onEffect)
    LaunchedEffect(viewModel) { viewModel.effects.collect { effectHandler(it) } }
    MineScreen(state, viewModel::onEvent, listState)
}

@Composable
fun MineScreen(state: MineUiState, onEvent: (MineUiEvent) -> Unit, listState: LazyListState) {
    when (state) {
        MineUiState.Loading -> LoadingSkeleton(Modifier.padding(LazyEngSpacing.large).testTag("mine-loading"))
        is MineUiState.Error -> ErrorState("无法读取个人信息", state.message,
            modifier = Modifier.testTag("mine-error"), retryLabel = "重试", onRetry = { onEvent(MineUiEvent.Refresh) })
        is MineUiState.Content -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("mine-screen"),
            contentPadding = PaddingValues(LazyEngSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            item { SectionHeader("我的") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = LazyEngShapes.largeCard,
                    colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface),
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(LazyEngSpacing.large),
                        horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
                    ) {
                        ProfileAvatar(state.profile.avatarId, state.profile.nickname)
                        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                            Text(state.profile.nickname, style = MaterialTheme.typography.titleLarge)
                            Text("${ageModeLabel(state.profile.ageMode)} · ${state.profile.englishLevel}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("mine-streak"),
                    shape = LazyEngShapes.largeCard,
                    colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface),
                ) {
                    Text(
                        text = if (state.profile.streakDays > 0) "连续学习 ${state.profile.streakDays} 天" else state.profile.streakMessage,
                        modifier = Modifier.padding(LazyEngSpacing.large),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            item {
                PrimaryButton("切换学习者", { onEvent(MineUiEvent.SwitchProfile) },
                    Modifier.fillMaxWidth().testTag("mine-switch-profile"))
            }
            item {
                SecondaryButton("家长控制台 · 需要 PIN", { onEvent(MineUiEvent.ParentSettings) },
                    Modifier.fillMaxWidth().testTag("mine-parent-settings"))
            }
        }
    }
}
