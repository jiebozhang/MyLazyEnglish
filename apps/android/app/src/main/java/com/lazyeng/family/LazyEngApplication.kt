package com.lazyeng.family

import android.app.Application
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.datastore.DataStoreCurrentProfileStore
import com.lazyeng.family.core.datastore.PreferencesOnboardingDraftStore
import com.lazyeng.family.core.security.KeystorePinRecordStore
import com.lazyeng.family.core.security.PinService
import com.lazyeng.family.feature.onboarding.OnboardingCoordinator
import java.time.Instant
import java.util.UUID
import com.lazyeng.family.core.common.ProfileChanges
import com.lazyeng.family.core.common.ProfileDeletionCoordinator
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.feature.profiles.ProfileCoordinator

class LazyEngApplication : Application() {
    val clock = Clock { Instant.now() }
    private val database by lazy { FamilyDatabase.open(this) }
    private val profiles by lazy { database.profileRepository() }
    private val drafts by lazy { PreferencesOnboardingDraftStore.create(this) }
    private val selection by lazy { DataStoreCurrentProfileStore.create(this, profiles) }
    private val pins by lazy { PinService(KeystorePinRecordStore.create(this), clock) }
    val profileChanges = ProfileChanges()
    val profileDeletions by lazy {
        ProfileDeletionCoordinator(database.profileDeletionTransaction()).apply {
            register("profile-body") { request ->
                profiles.deleteProfile(request.familyId, request.profileId, request.requestedAt)
            }
        }
    }
    fun onboardingCoordinator() = OnboardingCoordinator(drafts, pins, database.familyRepository(), profiles, selection)
    suspend fun profileCoordinator(): ProfileCoordinator {
        val familyId = drafts.loadOrCreate().familyId
        return ProfileCoordinator(familyId, profiles, database.profileDirectory(), selection,
            ParentSession(familyId, pins, clock), profileDeletions, profileChanges, clock,
            { ProfileId(UUID.randomUUID().toString()) })
    }
}
