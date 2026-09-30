package com.lazyeng.family.feature.importmedia

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.core.media.ImportOpenDocument
import androidx.activity.result.contract.ActivityResultContracts
import com.lazyeng.family.core.model.FileReference
import com.lazyeng.family.core.model.VideoStatus
import com.lazyeng.family.core.common.subtitle.SubtitleEncoding

@Composable
fun ImportRoute(viewModel: ImportViewModel, onBack: () -> Unit, onDetail: ((com.lazyeng.family.core.model.VideoId) -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val video = rememberImportDocumentPicker { viewModel.onEvent(ImportUiEvent.Selected(false, it)) }
    val subtitle = rememberImportDocumentPicker { viewModel.onEvent(ImportUiEvent.Selected(true, it)) }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onEvent(ImportUiEvent.DirectorySelected(it?.toString()?.let(::FileReference)))
    }
    LaunchedEffect(viewModel) { viewModel.effects.collect { effect ->
        when (effect) {
            is ImportUiEffect.OpenDocument -> if (effect.subtitle) subtitle.launch(arrayOf("*/*")) else video.launch(arrayOf("video/*"))
            ImportUiEffect.OpenDirectory -> directory.launch(null)
        }
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.onBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.onBackground() }
    }
    ImportScreen(state, viewModel::onEvent, onBack, onDetail)
}

/** Shared by the import and detail-repair route; instrumentation stubs ACTION_OPEN_DOCUMENT. */
@Composable
fun rememberImportDocumentPicker(onSelected: (FileReference?) -> Unit) =
    rememberLauncherForActivityResult(ImportOpenDocument()) { onSelected(it?.toString()?.let(::FileReference)) }

@Composable
fun ImportScreen(state: ImportScreenState, onEvent: (ImportUiEvent) -> Unit, onBack: () -> Unit,
    onDetail: ((com.lazyeng.family.core.model.VideoId) -> Unit)? = null) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(LazyEngSpacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large)) {
            SectionHeader("导入视频")
            if (!state.initialized) {
                Text(state.message ?: "正在读取家庭设置…")
            } else if (!state.authorized) {
                Text("家长验证", style = MaterialTheme.typography.titleLarge)
                PinDots(state.enteredCount)
                if (state.remainingSeconds > 0) Text("请等待 ${state.remainingSeconds} 秒后再试")
                PinKeypad(state.enteredCount, !state.busy && state.remainingSeconds == 0L,
                    if (state.busy) "验证中…" else "确认", { onEvent(ImportUiEvent.Digit(it)) },
                    { onEvent(ImportUiEvent.Erase) }, { onEvent(ImportUiEvent.Verify) })
            } else when (state.import.phase) {
                ImportPhase.SELECT -> {
                    PrimaryButton(if (state.videoSelected) "重新选择视频" else "选择视频", { onEvent(ImportUiEvent.Choose(false)) }, Modifier.fillMaxWidth().testTag("select-video"), !state.busy)
                    if (state.videoSelected) Text("已选择视频")
                    SecondaryButton(if (state.subtitleSelected) "重新选择字幕" else "选择英文字幕（可选）", { onEvent(ImportUiEvent.Choose(true)) }, Modifier.fillMaxWidth().testTag("select-subtitle"), !state.busy)
                    if (state.subtitleSelected) Text("已选择字幕")
                    SecondaryButton(if (state.directorySelected) "更换字幕候选目录" else "在所选目录查找同名字幕",
                        { onEvent(ImportUiEvent.ChooseDirectory) }, Modifier.fillMaxWidth().testTag("select-subtitle-directory"),
                        state.videoSelected && !state.busy)
                    if (state.directorySelected) Text("只会搜索你明确选择的目录；找到的字幕只是候选，不会自动关联。")
                    PrimaryButton("读取文件", { onEvent(ImportUiEvent.Inspect) }, Modifier.fillMaxWidth().testTag("inspect-import"), state.videoSelected && !state.busy)
                }
                ImportPhase.READING, ImportPhase.SAVING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.import.phase == ImportPhase.READING) "正在读取与检查视频… 已读取 ${state.import.bytesRead / 1024} KB" else "正在检查文件并保存…")
                }
                ImportPhase.PREVIEW -> state.import.preview?.let { preview ->
                    Text(preview.media.technical.displayName, style = MaterialTheme.typography.titleLarge)
                    Text("${preview.media.durationMs} 毫秒 · ${preview.media.technical.width} × ${preview.media.technical.height}")
                    Text("${preview.media.technical.mimeType} · 文件 ${preview.media.bytes / 1024} KB")
                    Text(if (preview.media.technical.audioMimeTypes.isEmpty()) "未发现音轨" else "音轨：${preview.media.technical.audioMimeTypes.joinToString()}")
                    Text("预计额外占用 ${preview.additionalBytesEstimate / 1024} KB；保留原文件位置，不复制视频。")
                    if (preview.subtitle == null) Text("尚未解析可发布的字幕，视频保存后仍需关联英文字幕。")
                    preview.subtitleName?.let { Text("预览文件：$it", Modifier.testTag("subtitle-preview-name")) }
                    preview.candidates.forEach { candidate ->
                        SecondaryButton("候选字幕：${candidate.displayName}${if (state.selectedSubtitleName == candidate.displayName) " · 已选择，待预览" else ""}",
                            { onEvent(ImportUiEvent.SelectCandidate(candidate)) }, Modifier.fillMaxWidth().testTag("subtitle-candidate-${candidate.displayName}"), !state.busy)
                    }
                    preview.candidateDiscoveryFailure?.let { ErrorState("未能查找同名候选字幕", it.message) }
                    SecondaryButton("手动选择其他字幕", { onEvent(ImportUiEvent.Choose(true)) }, Modifier.fillMaxWidth().testTag("replace-subtitle-selection"), !state.busy)
                    if (state.videoSelected) SecondaryButton("选择另一个候选目录", { onEvent(ImportUiEvent.ChooseDirectory) }, Modifier.fillMaxWidth(), !state.busy)
                    state.selectedSubtitleName?.let { Text("已选择 $it；请重新读取并预览，确认前不会关联。") }
                    if (state.subtitleSelected || preview.subtitleFailure != null) {
                        Text("字幕编码：${preview.subtitle?.encoding?.charsetName ?: "未确认"}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                            listOf(null to "自动", SubtitleEncoding.UTF8 to "UTF-8", SubtitleEncoding.GBK to "GBK",
                                SubtitleEncoding.UTF16_LE to "UTF-16LE", SubtitleEncoding.UTF16_BE to "UTF-16BE").forEach { (encoding, label) ->
                                FilterChip(selected = state.subtitleEncoding == encoding, onClick = { onEvent(ImportUiEvent.Encoding(encoding)) }, label = { Text(label) })
                            }
                        }
                        preview.subtitleFailure?.let { ErrorState("字幕暂不可发布", it.message) }
                        if (preview.subtitleFailure != null) SecondaryButton("不关联字幕，稍后再处理",
                            { onEvent(ImportUiEvent.ContinueWithoutSubtitle) }, Modifier.fillMaxWidth().testTag("continue-without-subtitle"), !state.busy)
                        if (preview.subtitleFailure != null || preview.subtitle == null && state.subtitleSelected) {
                            SecondaryButton("重新解析并预览", { onEvent(ImportUiEvent.Inspect) }, Modifier.fillMaxWidth().testTag("reparse-subtitle"), !state.busy)
                        }
                    }
                    preview.subtitle?.cues?.take(10)?.forEach { Text("${it.startMs}–${it.endMs} ms\n${it.text}") }
                    if (preview.subtitle != null) {
                        Text("整体偏移：${state.subtitleOffsetMs} ms（范围 ±5000 ms）", Modifier.testTag("subtitle-offset-label"))
                        Slider(value = state.subtitleOffsetMs / 100f, onValueChange = { onEvent(ImportUiEvent.Offset((it * 100).toLong())) },
                            valueRange = -50f..50f, steps = 99, enabled = !state.busy, modifier = Modifier.testTag("subtitle-offset-slider"))
                        if (preview.offsetMs != state.subtitleOffsetMs) SecondaryButton("应用偏移并重新校验",
                            { onEvent(ImportUiEvent.Inspect) }, Modifier.fillMaxWidth().testTag("apply-subtitle-offset"), !state.busy)
                    }
                    preview.timeline?.findings?.forEach { finding ->
                        val severity = if (finding.severity == com.lazyeng.family.core.common.subtitle.TimelineSeverity.ERROR) "错误" else "提示"
                        Text("$severity：${finding.issue.previewText()}（第 ${finding.sequence ?: "?"} 句）",
                            color = if (finding.severity == com.lazyeng.family.core.common.subtitle.TimelineSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        Checkbox(state.rights, { onEvent(ImportUiEvent.Rights(it)) }, Modifier.testTag("import-rights"))
                        Text("我有权使用这些素材，并确认所选英文或双语字幕与视频对应。", Modifier.weight(1f))
                    }
                    preview.duplicates.filter { state.repairVideoId == null }.forEach { existing ->
                        SecondaryButton("复用已有：${existing.metadata.title}", { onEvent(ImportUiEvent.Confirm(DuplicateChoice.Reuse(existing.id))) }, Modifier.fillMaxWidth().testTag("reuse-video"), state.rights && !state.busy)
                    }
                    val confirmLabel = when {
                        state.repairVideoId != null -> "确认并更新此视频的字幕"
                        preview.subtitle != null && preview.duplicates.isNotEmpty() -> "确认并发布新字幕版本"
                        preview.subtitle != null -> "确认字幕并发布，完成导入"
                        preview.duplicates.isEmpty() -> "确认导入"
                        else -> "仍然导入"
                    }
                    PrimaryButton(confirmLabel, { onEvent(ImportUiEvent.Confirm(DuplicateChoice.ImportNew)) }, Modifier.fillMaxWidth().testTag("confirm-import"), state.rights && !state.busy && preview.subtitleFailure == null && preview.offsetMs == state.subtitleOffsetMs && (state.repairVideoId == null || preview.subtitle != null))
                    if (state.subtitleSelected || state.selectedSubtitleName != null) SecondaryButton("重新读取并预览字幕",
                        { onEvent(ImportUiEvent.Inspect) }, Modifier.fillMaxWidth().testTag("inspect-selected-subtitle"), !state.busy)
                }
                ImportPhase.COMPLETE -> {
                    Text(if (state.import.video?.status == VideoStatus.READY) "导入完成，可以学习" else "视频已保存，等待英文字幕", Modifier.testTag("import-complete"), style = MaterialTheme.typography.titleLarge)
                    Text(state.import.video?.metadata?.title.orEmpty())
                    if (onDetail != null) state.import.video?.let { video ->
                        PrimaryButton("查看视频详情", { onDetail(video.id) }, Modifier.testTag("open-video-detail"))
                    }
                    if (state.import.rollbackAvailable) SecondaryButton("撤销本次字幕关联（一次）",
                        { onEvent(ImportUiEvent.RollbackSubtitle) }, Modifier.fillMaxWidth().testTag("rollback-subtitle"), !state.busy)
                    state.import.failure?.let { ErrorState("无法撤销字幕关联", it.message) }
                    SecondaryButton("继续导入", { onEvent(ImportUiEvent.Reset) }, Modifier.testTag("reset-import"))
                }
                ImportPhase.ERROR -> ErrorState("未能导入", state.import.failure?.message.orEmpty(),
                    retryLabel = "重新选择", onRetry = { onEvent(ImportUiEvent.Reset) })
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SecondaryButton("返回", onBack, Modifier.testTag("leave-import"))
        }
    }
}

private fun com.lazyeng.family.core.common.subtitle.TimelineIssue.previewText(): String = when (this) {
    com.lazyeng.family.core.common.subtitle.TimelineIssue.EMPTY -> "字幕为空"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.NEGATIVE_TIME -> "时间不能为负数"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.REVERSED_TIME -> "结束时间早于开始时间"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.ZERO_DURATION -> "字幕持续时间为零"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.INVALID_SEQUENCE -> "序号无效或重复"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.EMPTY_TEXT -> "字幕文本为空"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.OUT_OF_ORDER -> "字幕顺序与时间轴不一致"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.TOO_SHORT -> "显示时间过短"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.TOO_LONG -> "显示时间过长"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.LONG_TEXT -> "单句文本过长"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.OVERLAP -> "与其他字幕时间重叠"
    com.lazyeng.family.core.common.subtitle.TimelineIssue.OFFSET_OVERFLOW -> "偏移超出可表示的时间范围"
}
