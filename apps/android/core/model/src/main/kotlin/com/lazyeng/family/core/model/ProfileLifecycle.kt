package com.lazyeng.family.core.model

import java.time.Instant

/** Deliberately limited picker metadata; never contains preferences or learning/private records. */
data class ProfileChoice(
    val id: ProfileId, val nickname: String, val avatarId: String?,
    val role: ProfileRole, val englishLevel: String,
)
interface ProfileDirectory {
    suspend fun choices(familyId: FamilyId): List<ProfileChoice>
}
data class ProfileChanged(val familyId: FamilyId, val previousId: ProfileId?, val profileId: ProfileId?)
data class ProfileDeletionRequested(val familyId: FamilyId, val profileId: ProfileId, val requestedAt: Instant)

/** All registered cleaners must participate in this SAME transaction; no irreversible external I/O. */
fun interface ProfileDeletionTransaction {
    suspend fun run(block: suspend () -> Unit)
}
fun interface ProfileCleaner {
    suspend fun clean(request: ProfileDeletionRequested)
}
