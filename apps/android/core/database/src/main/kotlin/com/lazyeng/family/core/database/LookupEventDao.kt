package com.lazyeng.family.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Deliberately exposes no update or delete operation: lookup analytics are append-only. */
@Dao
internal interface LookupEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun append(event: LookupEventEntity): Long

    @Query("SELECT * FROM lookup_events WHERE profile_id = :profileId AND occurred_at >= :from AND occurred_at <= :to ORDER BY occurred_at, id")
    suspend fun list(profileId: String, from: java.time.Instant, to: java.time.Instant): List<LookupEventEntity>

    @Query("DELETE FROM lookup_events WHERE profile_id = :profileId")
    suspend fun deleteForProfile(profileId: String): Int
}
