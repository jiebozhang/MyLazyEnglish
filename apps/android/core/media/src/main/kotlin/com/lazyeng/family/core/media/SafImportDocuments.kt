package com.lazyeng.family.core.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Caller selects a video or subtitle MIME type; no broad storage permission. */
class ImportOpenDocument : ActivityResultContracts.OpenDocument()

class SafImportDocuments(context: Context) : ImportDocuments {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private fun uri(reference: FileReference): Uri = Uri.parse(reference.value).also {
        if (it.scheme != "content") throw ImportException(ImportErrors.access)
    }
    private fun treeGrantFor(uri: Uri) = resolver.persistedUriPermissions.firstOrNull { permission ->
        val targetTreeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        permission.uri != uri && permission.isReadPermission && DocumentsContract.isTreeUri(permission.uri) &&
            permission.uri.authority == uri.authority && targetTreeId != null &&
            runCatching { DocumentsContract.getTreeDocumentId(permission.uri) == targetTreeId }.getOrDefault(false)
    }
    private fun hasGrant(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } || treeGrantFor(uri) != null

    override suspend fun retain(reference: FileReference): Boolean = withContext(Dispatchers.IO) {
        val uri = uri(reference)
        if (hasGrant(uri)) return@withContext false
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!hasGrant(uri)) throw ImportException(ImportErrors.permission)
            true
        } catch (_: SecurityException) { throw ImportException(ImportErrors.permission) }
    }
    override suspend fun release(reference: FileReference) = withContext(Dispatchers.IO) {
        val target = uri(reference)
        val grantUri = resolver.persistedUriPermissions.firstOrNull { it.uri == target && it.isReadPermission }?.uri
            ?: treeGrantFor(target)?.uri ?: return@withContext
        try { resolver.releasePersistableUriPermission(grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* The grant may already have been revoked. */ }
    }
    override suspend fun findSubtitleCandidates(directory: FileReference, videoDisplayName: String): List<SubtitleCandidate> = withContext(Dispatchers.IO) {
        val tree = uri(directory)
        if (!DocumentsContract.isTreeUri(tree) || !hasGrant(tree)) throw ImportException(ImportErrors.permission)
        val expectedBase = videoDisplayName.substringBeforeLast('.', videoDisplayName).trim().lowercase()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val base = name.substringBeforeLast('.', name).trim().lowercase()
                    if (base == expectedBase && extension in setOf("srt", "vtt")) {
                        val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        add(SubtitleCandidate(FileReference(child.toString()), name))
                    }
                }
            }.sortedBy { it.displayName.lowercase() }
        } ?: emptyList()
    }
    override suspend fun availableStorageBytes(): Long = withContext(Dispatchers.IO) { StatFs(context.filesDir.path).availableBytes }
    /** Lightweight live access check; never hashes or copies the video just to show its details. */
    suspend fun checkAccess(reference: FileReference): Unit = access(reference) { uri ->
        (resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException()).use {
            if (it.length == 0L) throw ImportException(ImportErrors.invalid)
        }
    }
    override suspend fun accessible(reference: FileReference): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = uri(reference)
            hasGrant(uri) && resolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } == true
        } catch (_: SecurityException) { false } catch (_: IOException) { false }
    }
    override suspend fun probe(reference: FileReference, progress: (Long) -> Unit): ProbedVideo = access(reference) { uri ->
        val name = displayName(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        (resolver.openInputStream(uri) ?: throw FileNotFoundException()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                count = Math.addExact(count, n.toLong())
                progress(count)
            }
        }
        if (count == 0L) throw ImportException(ImportErrors.invalid)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (duration <= 0) throw ImportException(ImportErrors.duration)
            if (width <= 0 || height <= 0) throw ImportException(ImportErrors.invalid)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: resolver.getType(uri) ?: "application/octet-stream"
            // Prove at least one real frame can be decoded; metadata alone is not playback evidence.
            val frame = (if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 240) else retriever.getFrameAtTime(0))
                ?: throw ImportException(ImportErrors.invalid)
            frame.recycle()
            val extractor = MediaExtractor()
            val tracks = try {
                extractor.setDataSource(context, uri, null)
                (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            } finally { extractor.release() }
            ProbedVideo(VideoTechnicalMetadata(name, width, height, mime, tracks.filter { it.startsWith("audio/") }), duration, count,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, tracks.firstOrNull { it.startsWith("video/") })
        } finally { retriever.release() } // release() is the API-26-compatible close operation.
    }
    override suspend fun readSubtitle(reference: FileReference): SelectedSubtitle = access(reference) { uri ->
        val limit = 4 * 1024 * 1024 // Bounded in-memory parser input; not a video-size limit.
        val bytes = (resolver.openInputStream(uri) ?: throw FileNotFoundException()).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > limit) throw ImportException(ImportErrors.subtitle)
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        SelectedSubtitle(bytes, displayName(uri))
    }
    private fun displayName(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: "Selected video"

    private suspend fun <T> access(reference: FileReference, block: suspend (Uri) -> T): T = withContext(Dispatchers.IO) {
        val uri = uri(reference)
        if (!hasGrant(uri)) throw ImportException(ImportErrors.permission)
        try { block(uri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ImportException) { throw failure }
        catch (_: FileNotFoundException) { throw ImportException(ImportErrors.missing) }
        catch (_: SecurityException) { throw ImportException(if (hasGrant(uri)) ImportErrors.access else ImportErrors.permission) }
        catch (_: IOException) { throw ImportException(ImportErrors.io) }
        catch (_: IllegalArgumentException) { throw ImportException(ImportErrors.invalid) }
        catch (_: RuntimeException) { throw ImportException(ImportErrors.invalid) }
    }
}

class SafMediaReadiness(private val documents: ImportDocuments) : MediaReadinessChecker {
    override suspend fun isAccessible(profileId: ProfileId, video: Video, assets: List<VideoAsset>): Boolean =
        video.sourceType == VideoSourceType.LOCAL_FILE && assets.any {
            val reference = it.localReference
            it.videoId == video.id && reference != null && reference.value == video.sourceReference.value && documents.accessible(reference)
        }
}
