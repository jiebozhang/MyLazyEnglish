package com.lazyeng.family.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lazyeng.family.core.common.RestrictedContentOverrideEvent
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestrictedContentOverrideAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun auditSurvivesStoreRecreationAndContainsNoSecretOrUserContent() = runBlocking {
        val file = File(temporary.root, "audit.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        val audit = PreferencesRestrictedContentOverrideAudit(store)
        val event = RestrictedContentOverrideEvent(FamilyId("fixture-family"), ProfileId("fixture-child"), Instant.EPOCH)
        audit.append(event)
        val serialized = store.data.first()[stringPreferencesKey("events")].orEmpty()
        assertFalse(serialized.contains("PIN"))
        assertFalse(serialized.contains("content://"))
        job.cancelAndJoin()

        val reopenedJob = SupervisorJob()
        val reopenedStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(reopenedJob + Dispatchers.IO)) { file }
        try {
            val reopened = PreferencesRestrictedContentOverrideAudit(reopenedStore)
            assertEquals(listOf(event), reopened.list(ProfileId("fixture-child")))
            assertEquals(emptyList<RestrictedContentOverrideEvent>(), reopened.list(ProfileId("other-child")))
        } finally { reopenedJob.cancelAndJoin() }
    }
}
