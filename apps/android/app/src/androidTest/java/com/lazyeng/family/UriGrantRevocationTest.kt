package com.lazyeng.family

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.ImportErrors
import com.lazyeng.family.core.common.ImportException
import com.lazyeng.family.core.common.subtitle.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.media.SafImportDocuments
import com.lazyeng.family.core.media.SafMediaReadiness
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import com.lazyeng.family.feature.importmedia.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device check for the system-held single-Uri grant and the production recovery classification. */
@RunWith(AndroidJUnit4::class)
class UriGrantRevocationTest {
    private val profile = ProfileId("fixture-import-device")
    private val databaseName = "e2-t2-acceptance.db"

    @Test fun revokedReadyUriReturnsPermissionErrorAndRecoveryGuidance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = context.getSharedPreferences("e2-t2-evidence", Context.MODE_PRIVATE)
            .getString("video", null) ?: throw AssertionError("Run the SAF import device test first")
        val database = Room.databaseBuilder(context, FamilyDatabase::class.java, databaseName).build()
        try {
            val videoId = VideoId(id)
            val familyId = FamilyId("fixture-import-device-family")
            val parent = parentSession(familyId)
            val repository = database.videoRepository(parentSession = parent)
            val parentVideos = repository as ParentVideoAccess
            val scope = parent.requireScope()
            val saved = parentVideos.getForAuthorizedParent(profile, videoId, scope)
                ?: throw AssertionError("Expected the imported Ready record")
            assertEquals(VideoStatus.READY, saved.status)
            val reference = FileReference(saved.sourceReference.value)
            val documents = SafImportDocuments(context)
            assertFalse("The prior device recovery run released this Ready Uri grant", documents.accessible(reference))
            val rowCountBefore = parentVideos.listForAuthorizedParent(profile, scope).size

            val importer = VideoImportCoordinator(profile, documents,
                repository, database.videoAssetRepository(),
                database.importJobRepository(), database.subtitleRepository(), database.importTransaction(),
                { parent.checkScope(parent.requireScope()) }, Clock { Instant.now() }, { UUID.randomUUID().toString() },
                listVideosInAuthorizedScope = { parentVideos.listForAuthorizedParent(profile, parent.requireScope()) },
                getVideoInAuthorizedScope = { target -> parentVideos.getForAuthorizedParent(profile, target, parent.requireScope()) })
            importer.prepare(reference)
            val failure = importer.state.value.failure ?: throw AssertionError("Expected a classified access failure")
            assertEquals(ImportPhase.ERROR, importer.state.value.phase)
            assertEquals("PERMISSION_REVOKED", failure.error.code.value)
            assertNotEquals(ImportErrors.missing.error.code, failure.error.code)
            assertEquals("文件访问授权已失效，请重新选择文件。", failure.message)
            assertEquals(rowCountBefore, parentVideos.listForAuthorizedParent(profile, scope).size)
            assertEquals(VideoStatus.READY, parentVideos.getForAuthorizedParent(profile, videoId, scope)?.status)

            // ImportScreen forwards this message to ErrorState and wires its action to Reset.
            val report = buildString {
                appendLine("Ready record exists=true; its exact persisted Uri grant is revoked")
                appendLine("Coordinator phase=${importer.state.value.phase}; error=${failure.error.code.value}")
                appendLine("Distinct from ${ImportErrors.missing.error.code.value}; userMessage=${failure.message}")
                appendLine("Ready record preserved=true; no crash")
            }
            File(context.filesDir, "import-evidence").apply { mkdirs() }
                .resolve("uri-grant-revocation.txt").writeText(report)
            assertTrue("Screen source must present the specific recovery guidance",
                failure.message.contains("重新选择文件") && failure.message.contains("授权已失效"))
        } finally {
            database.close()
        }
    }

    private fun parentSession(familyId: FamilyId): ParentSession {
        val store = object : PinRecordStore {
            private var record: PinRecord? = null
            override suspend fun read() = record
            override suspend fun write(record: PinRecord) { this.record = record }
        }
        val pins = PinService(store, Clock { Instant.now() }, PinDerivation { _, _, _ -> ByteArray(32) { 9 } },
            kotlinx.coroutines.Dispatchers.Unconfined)
        val pin = CharArray(4) { '9' }
        runBlocking { pins.setup(familyId, pin) }
        return ParentSession(familyId, pins, Clock { Instant.now() }).also { session ->
            runBlocking { session.unlock(pin) }
            pin.fill('\u0000')
        }
    }
}
