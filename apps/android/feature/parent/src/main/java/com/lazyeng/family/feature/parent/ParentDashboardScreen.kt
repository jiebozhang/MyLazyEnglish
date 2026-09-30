package com.lazyeng.family.feature.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.ErrorState
import com.lazyeng.family.core.designsystem.LazyEngColors
import com.lazyeng.family.core.designsystem.LazyEngShapes
import com.lazyeng.family.core.designsystem.LazyEngSpacing
import com.lazyeng.family.core.designsystem.LoadingSkeleton
import com.lazyeng.family.core.designsystem.PrimaryButton
import com.lazyeng.family.core.designsystem.SectionHeader
import com.lazyeng.family.core.designsystem.StatusChip
import com.lazyeng.family.core.designsystem.StatusTone
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ParentDashboardRoute(
    viewModel: ParentDashboardViewModel,
    onBackToChild: () -> Unit,
    onImportVideo: () -> Unit,
    onLlmSettings: () -> Unit,
    onManagement: (ParentManagementSection) -> Unit,
    onNotAvailable: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val effectHandler by rememberUpdatedState(ManagementCallbacks(onImportVideo, onNotAvailable, onLlmSettings, onManagement))
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ParentDashboardUiEffect.ImportVideo -> effectHandler.first()
                is ParentDashboardUiEffect.NotAvailableYet -> effectHandler.onNotAvailable(effect.action)
                ParentDashboardUiEffect.OpenLlmSettings -> effectHandler.onLlmSettings()
                ParentDashboardUiEffect.OpenMemberManagement -> effectHandler.onManagement(ParentManagementSection.MEMBERS)
                ParentDashboardUiEffect.OpenContentManagement -> effectHandler.onManagement(ParentManagementSection.CONTENT)
                ParentDashboardUiEffect.OpenStorageManagement -> effectHandler.onManagement(ParentManagementSection.STORAGE)
            }
        }
    }
    ParentDashboardScreen(state, viewModel::onEvent, onBackToChild)
}

private data class ManagementCallbacks(
    val first: () -> Unit,
    val onNotAvailable: (String) -> Unit,
    val onLlmSettings: () -> Unit,
    val onManagement: (ParentManagementSection) -> Unit,
)

@Composable
fun ParentDashboardScreen(state: ParentDashboardUiState, onEvent: (ParentDashboardUiEvent) -> Unit, onBackToChild: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("parent-dashboard")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("家长控制台", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBackToChild, modifier = Modifier.testTag("parent-back-child")) { Text("返回孩子端") }
        }
        when (state) {
            ParentDashboardUiState.Loading -> LoadingSkeleton(Modifier.padding(LazyEngSpacing.large).testTag("parent-dashboard-loading"))
            is ParentDashboardUiState.Error -> ErrorState("暂时无法读取看板", state.message,
                modifier = Modifier.padding(LazyEngSpacing.large).testTag("parent-dashboard-error"),
                retryLabel = "重试", onRetry = { onEvent(ParentDashboardUiEvent.Refresh) })
            is ParentDashboardUiState.Content -> DashboardContent(state, onEvent)
        }
    }
}

@Composable
private fun DashboardContent(state: ParentDashboardUiState.Content, onEvent: (ParentDashboardUiEvent) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("parent-dashboard-content"),
        contentPadding = PaddingValues(LazyEngSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
    ) {
        item {
            Column {
                OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth().testTag("parent-profile-picker")) {
                    val selected = state.profiles.firstOrNull { it.id == state.selectedProfileId }
                    Text(selected?.let { "查看：${it.nickname}" } ?: "选择孩子")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    state.profiles.forEach { profile ->
                        DropdownMenuItem(text = { Text(profile.nickname) }, onClick = {
                            menuExpanded = false
                            onEvent(ParentDashboardUiEvent.SelectProfile(profile.id))
                        }, modifier = Modifier.testTag("parent-profile-${profile.id.value}"))
                    }
                }
            }
        }
        item { SectionHeader("学习概览") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                    MetricCard("今日观看", state.data.todayWatchMinutes?.let { "$it 分钟" } ?: "暂无记录", Modifier.weight(1f), "metric-today-watch")
                    MetricCard("本周观看", state.data.weekWatchMinutes?.let { "$it 分钟" } ?: "暂无记录", Modifier.weight(1f), "metric-week-watch")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                    MetricCard("连续学习", "${state.data.streakDays} 天", Modifier.weight(1f), "metric-streak")
                    MetricCard("生词本", "${state.data.vocabularyCount} 词 · 已掌握 ${state.data.masteredCount}", Modifier.weight(1f), "metric-vocabulary")
                }
                Text("观看时长统计将在记录前台播放会话后提供；此处不以视频断点推算时长。",
                    style = MaterialTheme.typography.bodySmall, color = LazyEngColors.TextSecondary)
            }
        }
        item { SectionHeader("最近学习动态") }
        if (state.data.recentItems.isEmpty()) {
            item { Text("还没有观看记录。", Modifier.testTag("parent-recent-empty"), color = LazyEngColors.TextSecondary) }
        } else items(state.data.recentItems, key = { it.videoId.value }) { item ->
            Card(Modifier.fillMaxWidth().testTag("recent-${item.videoId.value}"),
                shape = LazyEngShapes.card, colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface)) {
                Column(Modifier.padding(LazyEngSpacing.medium), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                            style = MaterialTheme.typography.bodySmall, color = LazyEngColors.TextSecondary)
                        StatusChip("完成 ${item.completionPercent}%", StatusTone.NEUTRAL)
                    }
                }
            }
        }
        item { SectionHeader("快捷管理") }
        item { PrimaryButton("导入视频", { onEvent(ParentDashboardUiEvent.ImportVideo) }, Modifier.fillMaxWidth().testTag("parent-import-video")) }
        item { SecondaryAction("成员管理", "添加档案、查看成员或安全删除", "parent-member-management") { onEvent(ParentDashboardUiEvent.MemberManagement) } }
        item { SecondaryAction("视频与内容管理", "管理可见状态、移除内容", "parent-content-management") { onEvent(ParentDashboardUiEvent.ContentManagement) } }
        item { SecondaryAction("数据与存储管理", "查看占用、清理缓存、导出备份", "parent-storage-management") { onEvent(ParentDashboardUiEvent.StorageManagement) } }
        item { SecondaryAction("时长与防沉迷设置", "后续版本", "parent-time-settings") { onEvent(ParentDashboardUiEvent.TimeSettings) } }
        item { SecondaryAction("大模型设置", "配置服务并测试连接", "parent-llm-settings") { onEvent(ParentDashboardUiEvent.LlmSettings) } }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier, tag: String) {
    Card(modifier.fillMaxWidth().testTag(tag), shape = LazyEngShapes.card,
        colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface)) {
        Column(Modifier.padding(LazyEngSpacing.medium), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = LazyEngColors.TextSecondary)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SecondaryAction(title: String, state: String, tag: String, onClick: () -> Unit) {
    Card(shape = LazyEngShapes.card, colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface)) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(title) }, supportingContent = { Text(state) },
            trailingContent = { TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text("查看") } },
        )
    }
}
