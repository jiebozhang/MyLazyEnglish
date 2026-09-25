package com.lazyeng.family.feature.profiles

import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*

data class ProfileDraft(
    val id: ProfileId, val nickname: String = "", val avatar: String? = "sun",
    val role: ProfileRole = ProfileRole.CHILD, val ageMode: String = "child", val level: String = "Power Up 2",
)

class ProfileCoordinator(
    val familyId: FamilyId,
    private val profiles: ProfileRepository,
    private val directory: ProfileDirectory,
    private val selection: CurrentProfileStore,
    val parent: ParentSession,
    private val deletions: ProfileDeletionCoordinator,
    private val changes: ProfileChanges,
    private val clock: Clock,
    private val newId: () -> ProfileId,
) {
    suspend fun current(): Profile? = selection.getCurrentProfileId(familyId)?.let { profiles.getProfile(familyId, it) }
    suspend fun choices() = directory.choices(familyId)
    suspend fun overview(): List<Profile> {
        val scope = parent.requireScope()
        val members = directory.choices(familyId).mapNotNull { profiles.getProfile(familyId, it.id) }
        parent.checkScope(scope)
        return members
    }
    suspend fun switchTo(profileId: ProfileId): Profile {
        parent.revoke()
        val previous = selection.getCurrentProfileId(familyId)
        checkNotNull(profiles.getProfile(familyId, profileId))
        check(selection.selectProfile(familyId, profileId))
        changes.publish(ProfileChanged(familyId, previous, profileId))
        return checkNotNull(profiles.getProfile(familyId, profileId))
    }
    suspend fun editor(profileId: ProfileId?): ProfileDraft {
        val scope = parent.requireScope()
        val draft = if (profileId == null) ProfileDraft(newId()) else {
            val p = checkNotNull(profiles.getProfile(familyId, profileId))
            ProfileDraft(p.id, p.nickname, p.avatarId, p.role, p.ageMode, p.englishLevel)
        }
        parent.checkScope(scope)
        return draft
    }
    suspend fun save(draft: ProfileDraft) {
        val scope = parent.requireScope()
        require(draft.nickname.isNotBlank() && draft.nickname.length <= 24 &&
            draft.ageMode.isNotBlank() && draft.level.isNotBlank())
        val old = profiles.getProfile(familyId, draft.id)
        parent.checkScope(scope)
        profiles.saveProfile(familyId, draft.id, Profile(draft.id, familyId, draft.nickname.trim(),
            draft.avatar, draft.role, draft.ageMode, draft.level, old?.uiPreferences ?: emptyMap(),
            old?.createdAt ?: clock.now(), null))
    }
    suspend fun delete(profileId: ProfileId, pin: CharArray): PinCheck {
        val scope = parent.requireScope()
        val result = parent.confirmDeletion(scope, pin)
        if (result != PinCheck.Accepted) return result
        val previous = selection.getCurrentProfileId(familyId)
        parent.checkScope(scope)
        deletions.request(ProfileDeletionRequested(familyId, profileId, clock.now())) { parent.checkScope(scope) }
        // Selection validation clears a stale pointer after the atomic tombstone, including on restart.
        val current = selection.getCurrentProfileId(familyId)
        if (previous != current) changes.publish(ProfileChanged(familyId, previous, current))
        return result
    }
}
