package com.lazyeng.family.feature.onboarding

import com.lazyeng.family.core.datastore.OnboardingDraft
import com.lazyeng.family.core.datastore.OnboardingDraftStore
import com.lazyeng.family.core.model.CurrentProfileStore
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyRepository
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileRepository
import com.lazyeng.family.core.model.ProfileRole
import com.lazyeng.family.core.security.PinCheck
import com.lazyeng.family.core.security.PinService

enum class OnboardingPhase { RESTORING, SET_PIN, CONFIRM_PIN, VERIFY_PIN, CREATE_PROFILE, COMPLETE, ERROR }
data class RestoredOnboarding(val draft: OnboardingDraft, val phase: OnboardingPhase, val lockUntil: Long = 0)

class OnboardingCoordinator(
    private val drafts: OnboardingDraftStore,
    private val pins: PinService,
    private val families: FamilyRepository,
    private val profiles: ProfileRepository,
    private val selection: CurrentProfileStore,
) {
    private var authorized = false

    suspend fun restore(): RestoredOnboarding {
        val draft = drafts.loadOrCreate()
        val status = pins.status(draft.familyId)
        if (!status.configured) {
            check(families.getFamily(draft.familyId) == null) { "PIN record missing for existing family" }
            return RestoredOnboarding(draft, OnboardingPhase.SET_PIN)
        }
        if (profiles.getProfile(draft.familyId, draft.profileId) != null) {
            check(selection.selectProfile(draft.familyId, draft.profileId))
            return RestoredOnboarding(draft, OnboardingPhase.COMPLETE)
        }
        return RestoredOnboarding(draft,
            if (authorized) OnboardingPhase.CREATE_PROFILE else OnboardingPhase.VERIFY_PIN, status.lockUntil)
    }
    suspend fun setPin(pin: CharArray) {
        val draft = drafts.loadOrCreate()
        pins.setup(draft.familyId, pin)
        authorized = true
        ensureFamily(draft)
    }
    suspend fun verify(pin: CharArray): PinCheck {
        val draft = drafts.loadOrCreate()
        val result = pins.verify(draft.familyId, pin)
        if (result == PinCheck.Accepted) {
            authorized = true
            ensureFamily(draft)
        }
        return result
    }
    suspend fun saveDetails(nickname: String, level: String, avatar: String): OnboardingDraft =
        drafts.saveDetails(nickname, level, avatar)

    suspend fun finish() {
        check(authorized) { "PIN verification required" }
        val draft = drafts.loadOrCreate()
        require(draft.nickname.isNotBlank() && draft.nickname.length <= 24 && draft.level.isNotBlank())
        ensureFamily(draft)
        val profile = profiles.getProfile(draft.familyId, draft.profileId) ?: Profile(
            draft.profileId, draft.familyId, draft.nickname.trim(), draft.avatar, ProfileRole.CHILD,
            "child", draft.level, emptyMap(), draft.createdAt, null,
        )
        profiles.saveProfile(draft.familyId, draft.profileId, profile)
        check(selection.selectProfile(draft.familyId, draft.profileId))
    }
    private suspend fun ensureFamily(draft: OnboardingDraft) {
        if (families.getFamily(draft.familyId) == null) {
            families.saveFamily(Family(draft.familyId, emptyMap(), draft.createdAt))
        }
    }
}
