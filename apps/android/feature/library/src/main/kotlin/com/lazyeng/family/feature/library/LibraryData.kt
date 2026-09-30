package com.lazyeng.family.feature.library

import com.lazyeng.family.core.common.VideoCatalog
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRole
import com.lazyeng.family.core.model.Video
import com.lazyeng.family.core.security.ParentAuthorizedScope
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.core.security.ParentVideoAccess

data class LibrarySession(
    val data: LibraryData,
    val parent: ParentSession,
    val isChild: Boolean,
)

class LibraryData(
    private val familyId: FamilyId,
    val profileId: ProfileId,
    private val role: ProfileRole,
    private val catalog: VideoCatalog,
    private val parentAccess: ParentVideoAccess,
    private val scopeValid: suspend () -> Boolean,
) {
    suspend fun validScope() = scopeValid()

    suspend fun list(scope: ParentAuthorizedScope? = null): List<Video> {
        check(scopeValid()) { "Profile scope changed" }
        val result = if (scope == null) catalog.listVideos(profileId)
        else {
            require(role == ProfileRole.CHILD && scope.familyId == familyId)
            parentAccess.listForAuthorizedParent(profileId, scope)
        }
        check(scopeValid()) { "Profile scope changed" }
        return result
    }
}
