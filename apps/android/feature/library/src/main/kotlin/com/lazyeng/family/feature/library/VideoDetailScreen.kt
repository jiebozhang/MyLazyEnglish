package com.lazyeng.family.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.core.model.VideoStatus

@Composable
fun ChildVideoDetailRoute(model: VideoDetailViewModel, onBack: () -> Unit, onParent: () -> Unit,
    onEffect: (VideoDetailEffect) -> Unit) {
    DetailLifecycle(model, onEffect)
    val state by model.state.collectAsStateWithLifecycle()
    ChildVideoDetailScreen(state, model::onEvent, onBack, onParent)
}

@Composable
fun ParentVideoDetailRoute(model: VideoDetailViewModel, onBack: () -> Unit, onEffect: (VideoDetailEffect) -> Unit) {
    DetailLifecycle(model, onEffect)
    val state by model.state.collectAsStateWithLifecycle()
    ParentVideoDetailScreen(state, model::onEvent, onBack)
}

@Composable
private fun DetailLifecycle(model: VideoDetailViewModel, onEffect: (VideoDetailEffect) -> Unit) {
    val callback by rememberUpdatedState(onEffect)
    LaunchedEffect(model) { model.effects.collect { callback(it) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.onBackground()
            if (event == Lifecycle.Event.ON_START) model.onEvent(VideoDetailEvent.Refresh)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.onBackground() }
    }
}

@Composable
private fun DetailLayout(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(LazyEngSpacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large)) {
            SectionHeader(title)
            content()
            SecondaryButton("返回", onBack, Modifier.testTag("detail-back"))
        }
    }
}

@Composable
fun ChildVideoDetailScreen(state: VideoDetailState, onEvent: (VideoDetailEvent) -> Unit,
    onBack: () -> Unit, onParent: () -> Unit) {
    DetailLayout("视频详情", onBack) {
        if (state.loading) LoadingSkeleton(Modifier.fillMaxWidth())
        else state.child?.let { detail ->
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LazyEngShapes.largeCard)
                .background(LazyEngColors.BlueSoft), contentAlignment = Alignment.Center) {
                Text("本地视频", style = MaterialTheme.typography.titleLarge, color = LazyEngColors.BlueDeep)
            }
            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
            Text("时长 ${timeLabel(detail.durationMs)}")
            detail.level?.let { StatusChip(it, StatusTone.LEARNING) }
            if (detail.topics.isNotEmpty()) Text(detail.topics.joinToString(" · "))
            detail.completion?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                Text("已观看 ${(it * 100).toInt()}% · ${timeLabel(detail.positionMs)}")
            }
            StatusChip(detail.availability.label(), if (detail.canWatch) StatusTone.SUCCESS else StatusTone.WARNING)
            if (detail.canWatch) PrimaryButton(if (detail.positionMs > 0) "继续观看" else "观看",
                { onEvent(VideoDetailEvent.Watch) }, Modifier.fillMaxWidth().testTag("watch-video"))
            else Text("请家长帮忙检查，准备好后就能观看。")
            SecondaryButton("家长管理", onParent, Modifier.fillMaxWidth().testTag("parent-video-detail"))
        }
        DetailMessage(state, onEvent)
    }
}

@Composable
fun ParentVideoDetailScreen(state: VideoDetailState, onEvent: (VideoDetailEvent) -> Unit, onBack: () -> Unit) {
    DetailLayout("视频管理", onBack) {
        if (!state.authorized) {
            Text("家长验证", style = MaterialTheme.typography.titleLarge)
            PinDots(state.enteredCount)
            if (state.remainingSeconds > 0) Text("请等待 ${state.remainingSeconds} 秒后再试")
            PinKeypad(state.enteredCount, !state.loading && state.remainingSeconds == 0L,
                if (state.loading) "验证中…" else "确认", { onEvent(VideoDetailEvent.Digit(it)) },
                { onEvent(VideoDetailEvent.Erase) }, { onEvent(VideoDetailEvent.Verify) })
        } else if (state.loading) LoadingSkeleton(Modifier.fillMaxWidth())
        else state.parent?.let { detail ->
            Text(detail.child.title, style = MaterialTheme.typography.titleLarge)
            StatusChip(detail.child.availability.label(), StatusTone.NEUTRAL)
            Text("记录状态：${detail.status.name}")
            Text("来源类型：${detail.sourceType}")
            Text("来源引用：${detail.source}", Modifier.testTag("detail-source"))
            detail.child.level?.let { Text(it) }
            Text("英文单词数：${detail.wordCount?.toString() ?: "暂无已发布英文字幕"}")
            SectionHeader("字幕版本历史")
            if (detail.versions.isEmpty()) EmptyState("暂无字幕", "选择字幕并预览确认后才能发布。")
            detail.versions.forEach { version ->
                Text("${version.language} · ${if (version.published) "已发布" else "草稿"}${if (version.current) " · 当前版本" else ""}\n版本 ${version.id}\n${version.publishedAt ?: "未发布"} · ${version.encoding ?: "编码未记录"}")
                HorizontalDivider()
            }
            SectionHeader("素材权利")
            Text("权利类型：${detail.ownerType}")
            Text("权利备注：${detail.usageNote ?: "未填写"}")
            Text(if (detail.rightsAcknowledged) "导入时已确认使用权利" else "尚未确认使用权利")
            detail.errorCode?.let { Text("错误类型：$it") }
            detail.repairAdvice?.let { Text(it) }
            if (detail.canRepairSubtitle) SecondaryButton("重新关联英文字幕", { onEvent(VideoDetailEvent.Repair) },
                Modifier.fillMaxWidth().testTag("repair-subtitle"))
            if (detail.child.availability in setOf(DetailAvailability.MISSING, DetailAvailability.PERMISSION, DetailAvailability.UNAVAILABLE)) {
                Text("请返回导入入口重新选择原文件。文件丢失时可重新导入有效副本；历史字幕不会被删除。")
            }
            SecondaryButton("重新检查", { onEvent(VideoDetailEvent.Refresh) }, Modifier.testTag("refresh-detail"))
            if (detail.status == VideoStatus.READY) SecondaryButton("归档视频", { onEvent(VideoDetailEvent.RequestArchive) }, Modifier.testTag("archive-video"))
        }
        DetailMessage(state, onEvent)
    }
    if (state.authorized && state.archiveConfirmation) AlertDialog(onDismissRequest = { onEvent(VideoDetailEvent.CancelArchive) },
        title = { Text("归档这个视频？") }, text = { Text("归档后不再推荐。字幕版本和已有观看进度仍然保留，不会删除原文件。") },
        confirmButton = { PrimaryButton("确认归档", { onEvent(VideoDetailEvent.ConfirmArchive) }, enabled = !state.loading) },
        dismissButton = { SecondaryButton("取消", { onEvent(VideoDetailEvent.CancelArchive) }, enabled = !state.loading) })
}

@Composable private fun DetailMessage(state: VideoDetailState, onEvent: (VideoDetailEvent) -> Unit) {
    state.message?.let { ErrorState("暂时无法打开", it, onRetry = { onEvent(VideoDetailEvent.Refresh) }) }
}
internal fun timeLabel(ms: Long?): String = ms?.let { "${it.coerceAtLeast(0) / 60_000}:${(it.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}" } ?: "未记录"
fun DetailAvailability.label(): String = when (this) {
    DetailAvailability.READY -> "可以观看"
    DetailAvailability.DRAFT -> "视频还未准备好"
    DetailAvailability.PROCESSING -> "正在准备视频"
    DetailAvailability.NEEDS_SUBTITLE -> "等待英文字幕"
    DetailAvailability.FAILED -> "视频准备失败"
    DetailAvailability.ARCHIVED -> "视频已归档"
    DetailAvailability.MISSING -> "找不到原视频文件"
    DetailAvailability.PERMISSION -> "需要重新授权访问视频"
    DetailAvailability.UNAVAILABLE -> "暂时无法读取视频"
}
