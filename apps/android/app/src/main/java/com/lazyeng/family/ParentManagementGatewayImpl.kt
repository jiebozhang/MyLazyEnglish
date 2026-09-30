package com.lazyeng.family

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.StatFs
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRole
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.model.VideoDeletionRepository
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.core.security.ParentVideoAccess
import com.lazyeng.family.feature.parent.ManagedVideoRow
import com.lazyeng.family.feature.parent.ParentManagementGateway
import com.lazyeng.family.feature.parent.ParentManagementSection
import com.lazyeng.family.feature.parent.ParentManagementSnapshot
import com.lazyeng.family.feature.parent.StorageUsage
import com.lazyeng.family.feature.profiles.ProfileCoordinator
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal class ParentManagementGatewayImpl(
    private val context: Context,
    private val familyId: FamilyId,
    private val database: FamilyDatabase,
    private val coordinator: ProfileCoordinator,
    private val parentSession: ParentSession,
    private val dispatchers: DispatcherProvider,
    private val now: () -> Instant,
) : ParentManagementGateway {

    override suspend fun load(section: ParentManagementSection): ParentManagementSnapshot = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        check(scope.familyId == familyId)
        val members = coordinator.overview().filter { it.role == ProfileRole.CHILD && it.deletedAt == null }
        val videos = if (section == ParentManagementSection.CONTENT) {
            val profileId = members.firstOrNull()?.id ?: return@withContext ParentManagementSnapshot(members = members)
            val catalog = database.videoRepository(parentSession = parentSession)
            val parentAccess = catalog as ParentVideoAccess
            parentAccess.listForAuthorizedParent(profileId, scope).map { video ->
                val assets = database.videoAssetRepository().listAssets(profileId, video.id)
                ManagedVideoRow(
                    id = video.id,
                    title = video.metadata.title,
                    sourceName = displayName(video.sourceReference.value),
                    durationMs = video.metadata.durationMs,
                    bytes = assets.sumOf { it.bytes ?: 0L },
                    visibleToChildren = !video.metadata.hiddenFromChildren,
                    status = video.status.name,
                )
            }
        } else emptyList()
        val storage = if (section == ParentManagementSection.STORAGE) storageUsage() else null
        parentSession.checkScope(scope)
        ParentManagementSnapshot(members = members, videos = videos, storage = storage)
    }

    override suspend fun createMember(nickname: String, level: String, avatarId: String) = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        require(coordinator.overview().count { it.role == ProfileRole.CHILD && it.deletedAt == null } < 12)
        val draft = coordinator.editor(null).copy(
            nickname = nickname,
            avatar = avatarId,
            role = ProfileRole.CHILD,
            ageMode = "child",
            level = level,
        )
        parentSession.checkScope(scope)
        coordinator.save(draft)
    }

    override suspend fun deleteMember(profileId: ProfileId, pin: CharArray) = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        val members = coordinator.overview().filter { it.role == ProfileRole.CHILD && it.deletedAt == null }
        check(members.size > 1) { "At least one learner must remain" }
        check(members.any { it.id == profileId })
        parentSession.checkScope(scope)
        coordinator.delete(profileId, pin)
    }

    override suspend fun setVideoVisible(videoId: VideoId, visible: Boolean) = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        val profileId = coordinator.overview().firstOrNull { it.role == ProfileRole.CHILD }?.id
            ?: error("No child profile is available")
        val catalog = database.videoRepository(parentSession = parentSession)
        catalog.setChildVisibility(profileId, videoId, visible)
        parentSession.checkScope(scope)
    }

    override suspend fun deleteVideo(videoId: VideoId) = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        val profileId = coordinator.overview().firstOrNull { it.role == ProfileRole.CHILD }?.id
            ?: error("No child profile is available")
        val assets = database.videoAssetRepository().listAssets(profileId, videoId)
        val video = (database.videoRepository(parentSession = parentSession) as ParentVideoAccess)
            .getForAuthorizedParent(profileId, videoId, scope) ?: error("Video is unavailable")
        (database.videoRepository(parentSession = parentSession) as VideoDeletionRepository)
            .deleteVideoAndDetachMedia(profileId, videoId)
        parentSession.checkScope(scope)
        assets.mapNotNull { it.localReference?.value }.plus(video.sourceReference.value)
            .forEach(::deleteManagedMediaIfOwned)
        deleteThumbnailCache(videoId)
        parentSession.checkScope(scope)
    }

    override suspend fun clearCache() = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        parentSession.checkScope(scope)
        deleteDirectoryContents(context.cacheDir)
        context.externalCacheDir?.let(::deleteDirectoryContents)
        parentSession.checkScope(scope)
    }

    override suspend fun exportBackupJson(): String = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        val family = database.familyRepository().getFamily(familyId) ?: error("Family is unavailable")
        val members = coordinator.overview().filter { it.deletedAt == null }
        val profilesJson = JSONArray()
        val vocabularyJson = JSONArray()
        members.forEach { profile ->
            profilesJson.put(JSONObject()
                .put("id", profile.id.value)
                .put("nickname", profile.nickname)
                .put("avatarId", profile.avatarId ?: JSONObject.NULL)
                .put("role", profile.role.name)
                .put("ageMode", profile.ageMode)
                .put("englishLevel", profile.englishLevel)
                .put("createdAt", profile.createdAt.toString()))
            database.vocabularyRepository(context).listItems(profile.id).forEach { item ->
                val contexts = JSONArray()
                database.vocabularyRepository(context).listContexts(profile.id, item.lemma).forEach { entry ->
                    contexts.put(JSONObject().put("surface", entry.surface).put("definition", entry.definition ?: JSONObject.NULL)
                        .put("sentence", entry.contextSentence).put("videoId", entry.videoId?.value ?: JSONObject.NULL)
                        .put("timestampMs", entry.timestampMs).put("createdAt", entry.createdAt.toString()))
                }
                vocabularyJson.put(JSONObject().put("profileId", profile.id.value).put("lemma", item.lemma)
                    .put("status", item.status.name).put("stage", item.stage).put("contexts", contexts))
            }
        }
        val safeSettings = JSONObject()
        family.settings.forEach { (key, value) ->
            if (listOf("key", "secret", "token", "pin", "password").none { key.contains(it, true) }) {
                safeSettings.put(key, value)
            }
        }
        parentSession.checkScope(scope)
        JSONObject().put("format", "lazyeng-family-backup-v1")
            .put("exportedAt", now().toString())
            .put("family", JSONObject().put("id", family.id.value).put("settings", safeSettings))
            .put("profiles", profilesJson).put("vocabulary", vocabularyJson).toString(2)
    }

    override suspend fun saveBackupJson(json: String): String = withContext(dispatchers.io) {
        val scope = parentSession.requireScope()
        val filename = "lazyeng-family-backup-${now().toEpochMilli()}.json"
        val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create backup file")
        } else {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("Download storage is unavailable")
            Uri.fromFile(File(directory, filename))
        }
        parentSession.checkScope(scope)
        if (destination.scheme == "content") {
            context.contentResolver.openOutputStream(destination, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                ?: error("Unable to open backup file")
        } else {
            File(requireNotNull(destination.path)).writeText(json, Charsets.UTF_8)
        }
        parentSession.checkScope(scope)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads/$filename" else "应用下载目录/$filename"
    }

    private fun storageUsage(): StorageUsage {
        val managedVideos = File(context.filesDir, "media")
        val dictionary = File(context.filesDir, "dictionary")
        val databaseBytes = listOf("family.db", "family.db-wal", "family.db-shm")
            .sumOf { File(context.getDatabasePath("family.db").parentFile, it).takeIf(File::isFile)?.length() ?: 0L }
        val stat = StatFs(context.filesDir.absolutePath)
        return StorageUsage(
            videosBytes = directorySize(managedVideos),
            dictionaryAndDatabaseBytes = directorySize(dictionary) + databaseBytes,
            cacheBytes = directorySize(context.cacheDir) + (context.externalCacheDir?.let(::directorySize) ?: 0L),
            freeBytes = stat.availableBytes,
        )
    }

    private fun deleteManagedMediaIfOwned(reference: String) {
        val parsed = Uri.parse(reference)
        val candidate = when (parsed.scheme) {
            "file" -> parsed.path?.let(::File)
            null -> File(reference)
            else -> null
        } ?: return
        val root = File(context.filesDir, "media").canonicalFile
        val target = candidate.canonicalFile
        if (target != root && target.path.startsWith(root.path + File.separator) && target.isFile) target.delete()
    }

    private fun deleteThumbnailCache(videoId: VideoId) {
        val root = File(context.cacheDir, "video-thumbnails").canonicalFile
        val target = File(root, videoId.value).canonicalFile
        if (target.path.startsWith(root.path + File.separator)) target.deleteRecursively()
    }

    private fun displayName(reference: String): String = runCatching {
        Uri.decode(Uri.parse(reference).lastPathSegment ?: "本地视频")
    }.getOrDefault("本地视频")

    private fun directorySize(directory: File): Long = if (!directory.exists()) 0L else
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun deleteDirectoryContents(directory: File) {
        directory.listFiles()?.forEach { child ->
            val target = child.canonicalFile
            if (target.parentFile == directory.canonicalFile) child.deleteRecursively()
        }
    }
}
