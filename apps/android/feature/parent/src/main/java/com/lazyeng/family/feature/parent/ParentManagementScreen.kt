package com.lazyeng.family.feature.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.model.Profile
import java.text.DateFormat
import java.util.Date

@Composable
fun ParentManagementRoute(
    viewModel: ParentManagementViewModel,
    onBack: () -> Unit,
) {
    BackHandler { viewModel.onEvent(ParentManagementUiEvent.Back) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect == ParentManagementUiEffect.Back) onBack()
        }
    }
    ParentManagementScreen(state, viewModel::onEvent)
}

@Composable
fun ParentManagementScreen(state: ParentManagementUiState, onEvent: (ParentManagementUiEvent) -> Unit) {
    var nickname by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("A1") }
    var avatar by remember { mutableStateOf("sun") }
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (state.section) {
                ParentManagementSection.MEMBERS -> "成员管理"
                ParentManagementSection.CONTENT -> "视频与内容管理"
                ParentManagementSection.STORAGE -> "数据与存储管理"
            }, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onEvent(ParentManagementUiEvent.Back) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回家长控制台") }
        }
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("management-error")) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("management-message")) }
        when (state.section) {
            ParentManagementSection.MEMBERS -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("添加孩子档案", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(nickname, { nickname = it.take(24) }, label = { Text("昵称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(level, { level = it.take(24) }, label = { Text("初始分级") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("sun", "moon", "star").forEach { id ->
                            OutlinedButton(onClick = { avatar = id }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (avatar == id) "✓ $id" else id) }
                        }
                    }
                    Button(onClick = { onEvent(ParentManagementUiEvent.CreateMember(nickname, level, avatar)); nickname = "" },
                        enabled = !state.working && nickname.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("添加档案") }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                items(state.snapshot.members, key = { it.id.value }) { profile -> MemberRow(profile) { onEvent(ParentManagementUiEvent.RequestDeleteMember(profile.id)) } }
            }
            ParentManagementSection.CONTENT -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.snapshot.videos.isEmpty()) item { Text("还没有导入的视频。") }
                items(state.snapshot.videos, key = { it.id.value }) { video ->
                    Card(Modifier.fillMaxWidth().testTag("managed-video-${video.id.value}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(video.title, style = MaterialTheme.typography.titleMedium)
                            Text("${video.sourceName} · ${formatDuration(video.durationMs)} · ${formatBytes(video.bytes)}")
                            Text("状态：${video.status}")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row {
                                    Checkbox(video.visibleToChildren, { onEvent(ParentManagementUiEvent.SetVideoVisible(video.id, it)) },
                                        modifier = Modifier.testTag("visibility-${video.id.value}"))
                                    Text(if (video.visibleToChildren) "孩子端可见" else "孩子端已隐藏")
                                }
                                TextButton(onClick = { onEvent(ParentManagementUiEvent.RequestDeleteVideo(video.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除视频") }
                            }
                        }
                    }
                }
            }
            ParentManagementSection.STORAGE -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val usage = state.snapshot.storage
                if (usage == null) item { Text("正在读取存储占用…") } else item {
                    Text("应用管理的视频：${formatBytes(usage.videosBytes)}")
                    Text("离线词库与数据库：${formatBytes(usage.dictionaryAndDatabaseBytes)}")
                    Text("临时缓存：${formatBytes(usage.cacheBytes)}")
                    Text("设备可用空间：${formatBytes(usage.freeBytes)}")
                }
                item {
                    Button(onClick = { onEvent(ParentManagementUiEvent.RequestClearCache) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清理临时缓存") }
                    OutlinedButton(onClick = { onEvent(ParentManagementUiEvent.ExportBackup) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导出家庭 JSON 备份") }
                }
            }
        }
    }
    when (val confirmation = state.confirmation) {
        is ParentManagementConfirmation.DeleteMember -> AlertDialog(
            onDismissRequest = { onEvent(ParentManagementUiEvent.DismissConfirmation) },
            title = { Text("删除孩子档案？") },
            text = { Text("将删除 ${confirmation.profile.nickname} 的档案、生词、复习记录、查词记录和观看进度。此操作不可撤销，且需要再次验证家长 PIN。") },
            confirmButton = { TextButton(onClick = { onEvent(ParentManagementUiEvent.Confirm) }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { onEvent(ParentManagementUiEvent.DismissConfirmation) }) { Text("取消") } },
        )
        is ParentManagementConfirmation.DeleteVideo -> AlertDialog(
            onDismissRequest = { onEvent(ParentManagementUiEvent.DismissConfirmation) },
            title = { Text("彻底移除视频记录？") },
            text = { Text("将移除“${confirmation.video.title}”的应用目录记录、字幕、断点与导入任务。SAF 选择器中的原始文件不会被删除，其他视频中的生词记录会保留。") },
            confirmButton = { TextButton(onClick = { onEvent(ParentManagementUiEvent.Confirm) }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { onEvent(ParentManagementUiEvent.DismissConfirmation) }) { Text("取消") } },
        )
        ParentManagementConfirmation.ClearCache -> AlertDialog(
            onDismissRequest = { onEvent(ParentManagementUiEvent.DismissConfirmation) },
            title = { Text("清理临时缓存？") },
            text = { Text("只清理临时文件与图片缓存，不删除视频、离线词库、数据库或学习记录。") },
            confirmButton = { TextButton(onClick = { onEvent(ParentManagementUiEvent.Confirm) }) { Text("清理") } },
            dismissButton = { TextButton(onClick = { onEvent(ParentManagementUiEvent.DismissConfirmation) }) { Text("取消") } },
        )
        ParentManagementConfirmation.MemberPin -> AlertDialog(
            onDismissRequest = { pin = ""; onEvent(ParentManagementUiEvent.DismissConfirmation) },
            title = { Text("再次验证家长 PIN") },
            text = {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("4 位 PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true,
                    modifier = Modifier.testTag("member-delete-pin"))
            },
            confirmButton = { TextButton(enabled = pin.length == 4 && !state.working, onClick = {
                onEvent(ParentManagementUiEvent.SubmitDeletePin(pin.toCharArray())); pin = ""
            }) { Text(if (state.working) "处理中…" else "确认删除") } },
            dismissButton = { TextButton(onClick = { pin = ""; onEvent(ParentManagementUiEvent.DismissConfirmation) }) { Text("取消") } },
        )
        null -> Unit
    }
}

@Composable
private fun MemberRow(profile: Profile, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("managed-member-${profile.id.value}")) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(profile.nickname, style = MaterialTheme.typography.titleMedium)
                Text("${profile.englishLevel} · ${profile.ageMode} · 创建于 ${DateFormat.getDateInstance().format(Date.from(profile.createdAt))}")
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除") }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatDuration(value: Long?): String = value?.let { "%d:%02d".format(it / 60_000, (it / 1_000) % 60) } ?: "时长未知"
