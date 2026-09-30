package com.lazyeng.family.core.model

/** Non-sensitive selection pointer, validated against live profiles on every restoration. */
interface CurrentProfileStore {
    /** Null means the caller must show profile selection; never substitutes another profile. */
    suspend fun getCurrentProfileId(familyId: FamilyId): ProfileId?
    /** Returns false for a missing/deleted/cross-household profile, leaving selection unchanged. */
    suspend fun selectProfile(familyId: FamilyId, profileId: ProfileId): Boolean
    suspend fun clearSelection(familyId: FamilyId)
}
