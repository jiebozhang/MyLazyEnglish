package com.lazyeng.family.feature.importmedia

import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.common.subtitle.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ImportPhase { SELECT, READING, PREVIEW, SAVING, COMPLETE, ERROR }
data class ImportPreview(
    val media: ProbedVideo, val subtitle: ParsedSubtitle?, val timeline: TimelineReport?,
    val duplicates: List<Video>, val additionalBytesEstimate: Long,
    val candidates: List<SubtitleCandidate> = emptyList(), val subtitleName: String? = null,
    val subtitleFailure: ImportFailure? = null, val offsetMs: Long = 0,
    val candidateDiscoveryFailure: ImportFailure? = null,
)
data class ImportUiState(val phase: ImportPhase = ImportPhase.SELECT, val bytesRead: Long = 0,
    val preview: ImportPreview? = null, val video: Video? = null, val failure: ImportFailure? = null,
    val failureRecorded: Boolean = false, val rollbackAvailable: Boolean = false)
private data class PreviousSubtitle(val version: PublishedSubtitleVersion, val lines: List<SubtitleLine>)
private data class RollbackSnapshot(val trackId: SubtitleTrackId, val publishedVersionId: SubtitleVersionId,
    val previous: PreviousSubtitle?)
data class ImportPolicy(val maxDurationMs: Long? = null, val metadataReserveBytes: Long = 1024 * 1024) {
    init { require(maxDurationMs == null || maxDurationMs > 0); require(metadataReserveBytes > 0) }
}
sealed interface DuplicateChoice {
    data object ImportNew : DuplicateChoice
    data class Reuse(val videoId: VideoId) : DuplicateChoice
}

/** One profile-bound import session. Authorization is rechecked before selection and commit. */
class VideoImportCoordinator(
    val profileId: ProfileId,
    private val documents: ImportDocuments,
    private val videos: VideoCatalog,
    private val assets: VideoAssetRepository,
    private val jobs: ImportJobRepository,
    private val subtitles: SubtitleCatalog,
    private val transaction: ImportTransaction,
    private val authorize: suspend () -> Unit,
    private val clock: Clock,
    private val newId: () -> String,
    private val policy: ImportPolicy = ImportPolicy(),
    private val listVideosInAuthorizedScope: suspend () -> List<Video> = { videos.listVideos(profileId) },
    private val getVideoInAuthorizedScope: suspend (VideoId) -> Video? = { videos.getVideo(profileId, it) },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()
    private val grants = mutableSetOf<FileReference>()
    private var selected: FileReference? = null
    private var rollbackSnapshot: RollbackSnapshot? = null

    suspend fun prepare(video: FileReference?, subtitle: FileReference? = null, encoding: SubtitleEncoding? = null,
        directory: FileReference? = null, offsetMs: Long = 0) = mutex.withLock {
        cleanup()
        rollbackSnapshot = null
        mutableState.value = ImportUiState()
        if (video == null) return@withLock
        try {
            authorize()
            selected = video
            mutableState.value = ImportUiState(ImportPhase.READING)
            retain(video)
            if (directory != null) retain(directory)
            val media = documents.probe(video) { mutableState.value = ImportUiState(ImportPhase.READING, bytesRead = it) }
            if (media.durationMs <= 0 || policy.maxDurationMs?.let { media.durationMs > it } == true) fail(ImportErrors.duration)
            var candidateDiscoveryFailure: ImportFailure? = null
            val candidates = if (directory == null) emptyList() else try {
                documents.findSubtitleCandidates(directory, media.technical.displayName)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                candidateDiscoveryFailure = ImportErrors.failure("SUBTITLE_CANDIDATE_DISCOVERY_FAILED",
                    "无法读取所选目录中的候选字幕，请手动选择字幕文件或更换目录。")
                emptyList()
            }
            var subtitleBytes = 0
            var subtitleFailure: ImportFailure? = null
            var subtitleName: String? = null
            val parsed = subtitle?.let { reference ->
                retain(reference)
                val source = documents.readSubtitle(reference)
                subtitleName = source.displayName
                subtitleBytes = source.bytes.size
                val format = when (source.displayName.substringAfterLast('.', "").lowercase()) {
                    "srt" -> SubtitleFormat.SRT; "vtt" -> SubtitleFormat.VTT; else -> fail(ImportErrors.subtitle)
                }
                when (val result = SubtitleParser.parse(source.bytes, format, encoding)) {
                    is SubtitleParseResult.Success -> result.subtitle
                    is SubtitleParseResult.Failure -> {
                        subtitleFailure = ImportFailure(result.report.error, result.report.userMessage)
                        null
                    }
                }
            }
            var timeline = parsed?.let { TimelineValidator.validate(it.cues.map { cue ->
                SubtitleLine(SubtitleLineId("preview-${cue.sequence}"), SubtitleVersionId("preview"), cue.sequence, cue.startMs, cue.endMs, cue.text)
            }) }
            var adjusted = parsed
            if (parsed != null && offsetMs != 0L) {
                val lines = parsed.cues.map { cue -> SubtitleLine(SubtitleLineId("preview-${cue.sequence}"),
                    SubtitleVersionId("preview"), cue.sequence, cue.startMs, cue.endMs, cue.text) }
                val shifted = TimelineValidator.previewOffset(lines, offsetMs)
                timeline = shifted.report
                if (shifted.report.canPublish) adjusted = parsed.copy(cues = shifted.lines.map { line ->
                    ParsedCue(line.sequence, line.startMs, line.endMs, line.text)
                })
            }
            if (timeline?.canPublish == false) subtitleFailure = ImportErrors.subtitle
            if (adjusted?.cues?.any { it.endMs > media.durationMs } == true) fail(ImportErrors.failure("SUBTITLE_EXCEEDS_VIDEO", "字幕超出了视频时长，请检查是否选择了对应的字幕。"))
            val estimate = Math.addExact(policy.metadataReserveBytes, subtitleBytes.toLong() * 4)
            if (documents.availableStorageBytes() < estimate) fail(ImportErrors.storage)
            authorize()
            val duplicates = listVideosInAuthorizedScope().filter { it.checksum == media.checksum && it.status in setOf(VideoStatus.READY, VideoStatus.NEEDS_SUBTITLE) }
            mutableState.value = ImportUiState(ImportPhase.PREVIEW, preview = ImportPreview(media, adjusted, timeline, duplicates,
                estimate, candidates, subtitleName, subtitleFailure, offsetMs, candidateDiscoveryFailure))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cleanup() }
            mutableState.value = ImportUiState()
            throw cancelled
        } catch (failure: Exception) {
            cleanup()
            mutableState.value = ImportUiState(ImportPhase.ERROR, failure = classify(failure))
        }
    }

    suspend fun confirm(choice: DuplicateChoice, rightsAcknowledged: Boolean) = mutex.withLock {
        val preview = mutableState.value.preview ?: return@withLock
        val source = selected ?: return@withLock
        if (mutableState.value.phase != ImportPhase.PREVIEW) return@withLock
        try {
            authorize()
            if (preview.subtitleFailure != null) fail(preview.subtitleFailure)
            if (!rightsAcknowledged) fail(ImportErrors.failure("RIGHTS_CONFIRMATION_REQUIRED", "请先确认有权使用所选视频与字幕。"))
            mutableState.value = ImportUiState(ImportPhase.SAVING, preview = preview)
            if (choice is DuplicateChoice.Reuse) {
                val existing = getVideoInAuthorizedScope(choice.videoId)
                if (existing == null || existing.checksum != preview.media.checksum || existing.status !in setOf(VideoStatus.READY, VideoStatus.NEEDS_SUBTITLE)) fail(ImportErrors.changed)
                if (!documents.accessible(FileReference(existing.sourceReference.value))) fail(ImportErrors.permission)
                if (preview.subtitle != null) withContext(NonCancellable) { transaction.run {
                    rollbackSnapshot = publishSubtitle(existing.id, preview.subtitle)
                    if (!videos.markReadyIfEligible(profileId, existing.id)) fail(ImportErrors.subtitle)
                } }
                cleanup()
                mutableState.value = ImportUiState(ImportPhase.COMPLETE, video = getVideoInAuthorizedScope(existing.id) ?: existing,
                    rollbackAvailable = rollbackSnapshot != null)
                return@withLock
            }
            // Re-probe before commit so replacement/moved files cannot use stale preview metadata.
            val checked = documents.probe(source) { }
            if (checked != preview.media) fail(ImportErrors.changed)
            if (documents.availableStorageBytes() < preview.additionalBytesEstimate) fail(ImportErrors.storage)
            authorize()
            val videoId = VideoId(newId())
            val jobId = ImportJobId(newId())
            var completed: Video? = null
            // Once the short DB commit begins, cancellation cannot release a committed video's grant.
            // Expensive file work above remains cancellable; authorization is checked inside the commit.
            withContext(NonCancellable) { transaction.run {
                authorize()
                val media = preview.media
                val video = draft(videoId, source, preview)
                videos.writeVideo(profileId, video).value()
                val job = ImportJob(jobId, videoId, ImportStatus.Draft, jobId.value)
                jobs.saveImportJob(profileId, job)
                videos.transition(profileId, videoId, VideoStatus.PROCESSING).value()
                jobs.saveImportJob(profileId, job.copy(status = ImportStatus.Processing))
                assets.saveAsset(profileId, VideoAsset(VideoAssetId(newId()), videoId, source, media.checksum, media.codec, media.bytes))
                preview.subtitle?.let { parsed -> rollbackSnapshot = publishSubtitle(videoId, parsed) }
                val target = if (preview.subtitle == null) VideoStatus.NEEDS_SUBTITLE else VideoStatus.READY
                completed = videos.transition(profileId, videoId, target,
                    if (target == VideoStatus.NEEDS_SUBTITLE) "NO_SUBTITLE" else null,
                    if (target == VideoStatus.NEEDS_SUBTITLE) "请家长关联英文字幕。" else null).value()
                jobs.saveImportJob(profileId, job.copy(status = completed!!.importStatus, errorCode = completed!!.errorCode))
            }
            // Video keeps its grant. Subtitle text is materialized in Room and needs no ongoing source access.
            grants.remove(source)
            cleanup()
            mutableState.value = ImportUiState(ImportPhase.COMPLETE, video = completed, rollbackAvailable = rollbackSnapshot != null)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cleanup() }
            mutableState.value = ImportUiState()
            throw cancelled
        } catch (failure: Exception) {
            rollbackSnapshot = null
            val classified = classify(failure)
            // Only an explicitly confirmed new import gets a durable failed receipt, never a selection/cancel.
            val recorded = rightsAcknowledged && choice == DuplicateChoice.ImportNew && recordFailure(source, preview, classified)
            cleanup()
            mutableState.value = ImportUiState(ImportPhase.ERROR, failure = classified, failureRecorded = recorded)
        }
    }

    suspend fun cancel() = mutex.withLock { cleanup(); mutableState.value = ImportUiState() }
    suspend fun rollbackLastSubtitlePublication() = mutex.withLock {
        val snapshot = rollbackSnapshot ?: return@withLock
        try {
            authorize()
            withContext(NonCancellable) { transaction.run {
                if (snapshot.previous == null) {
                    val result = subtitles.rollbackInitialPublication(profileId, snapshot.trackId, snapshot.publishedVersionId)
                    if (result is AppResult.Failure) throw SubtitleOperationException(result.error)
                } else {
                    val previous = snapshot.previous
                    val draft = DraftSubtitleVersion(SubtitleVersionId(newId()), snapshot.trackId, previous.version.sourceHash,
                        previous.version.parserVersion, previous.version.sourceEncoding)
                    subtitles.writeDraft(profileId, draft, previous.lines.map { line -> line.copy(
                        id = SubtitleLineId(newId()), versionId = draft.id) }).value()
                    when (val result = subtitles.publish(profileId, draft.id, clock.now())) {
                        is SubtitlePublishResult.Failure -> throw SubtitleOperationException(result.error)
                        is SubtitlePublishResult.Success -> Unit
                    }
                }
            } }
            rollbackSnapshot = null
            val current = mutableState.value.video ?: return@withLock
            mutableState.value = mutableState.value.copy(video = getVideoInAuthorizedScope(current.id) ?: current,
                rollbackAvailable = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = mutableState.value.copy(failure = classify(failure)) }
    }
    private suspend fun publishSubtitle(videoId: VideoId, parsed: ParsedSubtitle): RollbackSnapshot {
        val existingTrack = subtitles.listTracks(profileId, videoId).firstOrNull { it.language.equals("en", ignoreCase = true) }
        val track = existingTrack ?: SubtitleTrack(SubtitleTrackId(newId()), videoId, "en", "LOCAL_FILE", null).also {
            subtitles.writeTrack(profileId, it).value()
        }
        val currentId = track.currentVersionId
        val previous = currentId?.let { active -> subtitles.getPublishedVersion(profileId, active)?.let { version ->
            PreviousSubtitle(version, subtitles.listLines(profileId, version.id, 0, Int.MAX_VALUE))
        } }
        val version = DraftSubtitleVersion(SubtitleVersionId(newId()), track.id, parsed.sourceHash, parsed.parserVersion, parsed.encoding.charsetName)
        subtitles.writeDraft(profileId, version, parsed.cues.map { cue ->
            SubtitleLine(SubtitleLineId(newId()), version.id, cue.sequence, cue.startMs, cue.endMs, cue.text)
        }).value()
        when (val result = subtitles.publish(profileId, version.id, clock.now())) {
            is SubtitlePublishResult.Failure -> throw ImportException(ImportFailure(result.error, "字幕发布失败，请检查字幕后重试。"))
            is SubtitlePublishResult.Success -> Unit
        }
        return RollbackSnapshot(track.id, version.id, previous)
    }
    private suspend fun retain(reference: FileReference) = withContext(NonCancellable) {
        // Do not lose the acquired grant at a dispatcher cancellation boundary before tracking it.
        if (documents.retain(reference)) grants += reference
    }
    private suspend fun cleanup() {
        for (reference in grants.toList()) {
            try { documents.release(reference); grants.remove(reference) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep failed releases in the set for the next explicit cleanup. */ }
        }
        selected = null
    }
    private fun draft(id: VideoId, source: FileReference, preview: ImportPreview): Video = Video(
        id, VideoSourceType.LOCAL_FILE, VideoSourceReference(source.value), preview.media.checksum,
        VideoMetadata(preview.media.technical.displayName, null, preview.media.durationMs, emptyList(), null,
            preview.subtitle?.cues?.sumOf { SubtitleTokenizer.tokenize(it.text).count { token -> token.kind == SubtitleTokenKind.WORD } },
            null, "unknown", technical = preview.media.technical), VideoStatus.DRAFT, ImportStatus.Draft,
        SubtitleStatus.NoSubtitle, TranslationStatus.Unknown("NOT_REQUESTED"), false,
        VideoRights("FAMILY", null, profileId, true))

    private suspend fun recordFailure(source: FileReference, preview: ImportPreview, failure: ImportFailure): Boolean = try {
        transaction.run {
            authorize()
            val id = VideoId(newId())
            val job = ImportJobId(newId())
            videos.writeVideo(profileId, draft(id, source, preview)).value()
            videos.transition(profileId, id, VideoStatus.PROCESSING).value()
            videos.transition(profileId, id, VideoStatus.FAILED, failure.error.code.value, failure.message).value()
            jobs.saveImportJob(profileId, ImportJob(job, id, ImportStatus.Failed, job.value, failure.error.code.value))
        }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false } // Storage or scope failures can also prevent writing the audit receipt.
    private fun fail(failure: ImportFailure): Nothing = throw ImportException(failure)
    private fun <T> AppResult<T>.value(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw ImportException(ImportFailure(error, "导入未能保存，请检查存储空间和文件权限后重试。"))
    }
    private fun classify(error: Exception): ImportFailure = when (error) {
        is ImportException -> error.failure
        is VideoOperationException -> ImportFailure(error.error, "视频导入失败，请重新选择文件。")
        is SubtitleOperationException -> ImportFailure(error.error, "字幕保存失败，请修复字幕后重试。")
        else -> ImportErrors.io
    }
}
