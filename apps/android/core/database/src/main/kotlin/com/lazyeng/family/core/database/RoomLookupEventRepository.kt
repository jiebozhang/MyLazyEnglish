package com.lazyeng.family.core.database

import androidx.room.withTransaction
import com.lazyeng.family.core.model.LookupEvent
import com.lazyeng.family.core.model.LookupEventRepository
import com.lazyeng.family.core.model.ProfileId
import java.time.Instant

internal class RoomLookupEventRepository(private val db: FamilyDatabase) : LookupEventRepository {
    override suspend fun append(profileId: ProfileId, event: LookupEvent) {
        require(profileId == event.profileId) { "Lookup event profile scope mismatch" }
        require(event.word.isNotBlank() && event.lemma.isNotBlank() && event.eventUuid.value.isNotBlank())
        db.withTransaction { db.lookupEventDao().append(event.toEntity()) }
    }

    override suspend fun listEvents(profileId: ProfileId, from: Instant, to: Instant): List<LookupEvent> {
        require(from <= to)
        return db.lookupEventDao().list(profileId.value, from, to).map(LookupEventEntity::toDomain)
    }
}
