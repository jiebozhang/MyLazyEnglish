package com.lazyeng.family.core.common

import com.lazyeng.family.core.model.*

data class ProbedVideo(val technical: VideoTechnicalMetadata, val durationMs: Long, val bytes: Long, val checksum: String, val codec: String?)
data class SelectedSubtitle(val bytes: ByteArray, val displayName: String)
data class SubtitleCandidate(val reference: FileReference, val displayName: String)
interface ImportDocuments {
    /** Returns true only when this operation acquired a new persistent read grant. */
    suspend fun retain(reference: FileReference): Boolean
    suspend fun release(reference: FileReference)
    suspend fun probe(reference: FileReference, progress: (Long) -> Unit): ProbedVideo
    suspend fun readSubtitle(reference: FileReference): SelectedSubtitle
    /** Enumerates only a directory explicitly selected through SAF. */
    suspend fun findSubtitleCandidates(directory: FileReference, videoDisplayName: String): List<SubtitleCandidate>
    suspend fun accessible(reference: FileReference): Boolean
    suspend fun availableStorageBytes(): Long
}
fun interface ImportTransaction { suspend fun run(block: suspend () -> Unit) }

data class ImportFailure(val error: AppError, val message: String)
class ImportException(val failure: ImportFailure) : IllegalStateException(failure.error.code.value)
object ImportErrors {
    fun failure(code: String, message: String) = ImportFailure(AppError(AppErrorCode(code), ErrorHandling.USER_ACTION,
        UserMessageKey("error.import.${code.lowercase()}")), message)
    val permission = failure("PERMISSION_REVOKED", "文件访问授权已失效，请重新选择文件。")
    val missing = failure("SOURCE_FILE_MISSING", "原文件已不存在，请重新选择文件。")
    val access = failure("SOURCE_ACCESS_DENIED", "文件提供方拒绝访问，请检查文件是否移动并重新选择。")
    val invalid = failure("MEDIA_INVALID", "无法读取视频或视频已损坏，请选择可播放的视频。")
    val io = failure("MEDIA_READ_FAILED", "读取文件失败，请检查存储设备后重新选择。")
    val duration = failure("MEDIA_DURATION_INVALID", "视频时长无效或超过当前配置的上限，请选择其他视频。")
    val storage = failure("STORAGE_LOW", "应用存储空间不足，请清理空间后重试。")
    val subtitle = failure("SUBTITLE_IMPORT_INVALID", "字幕无法解析或时间轴有错误，请修复或重新选择字幕。")
    val scope = failure("IMPORT_SCOPE_UNAVAILABLE", "家长授权或当前档案已失效，请重新验证。")
    val changed = failure("SOURCE_CHANGED", "文件已变化或访问失效，请重新选择并确认。")
}
