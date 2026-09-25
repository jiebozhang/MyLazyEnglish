package com.lazyeng.family.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.lazyeng.family.core.model.ProfileDirectory
import com.lazyeng.family.core.model.ProfileDeletionTransaction
import com.lazyeng.family.core.model.FamilyRepository
import com.lazyeng.family.core.model.ProfileRepository
import com.lazyeng.family.core.model.VideoAssetRepository
import com.lazyeng.family.core.model.ImportJobRepository
import com.lazyeng.family.core.model.WatchProgressRepository
import com.lazyeng.family.core.common.*

@Database(entities = [FamilyEntity::class, ProfileEntity::class, VideoEntity::class, VideoAssetEntity::class,
    ImportJobEntity::class, WatchProgressEntity::class, SubtitleTrackEntity::class,
    SubtitleVersionEntity::class, SubtitleLineEntity::class], version = 3, exportSchema = true)
@TypeConverters(FamilyConverters::class, VideoConverters::class)
abstract class FamilyDatabase : RoomDatabase() {
    internal abstract fun familyDao(): FamilyDao
    internal abstract fun profileDao(): ProfileDao
    internal abstract fun videoDao(): VideoDao
    internal abstract fun subtitleDao(): SubtitleDao

    fun subtitleRepository(): SubtitleCatalog = RoomSubtitleRepository(this)
    fun subtitleReadinessChecker(): SubtitleReadinessChecker = RoomSubtitleReadinessChecker(this)

    fun videoRepository(media: MediaReadinessChecker = UnavailableMediaReadiness,
        subtitles: SubtitleReadinessChecker = subtitleReadinessChecker()): VideoCatalog = RoomVideoRepository(this, media, subtitles)
    fun videoAssetRepository(): VideoAssetRepository = RoomVideoRepository(this, UnavailableMediaReadiness, UnavailableSubtitleReadiness)
    fun importJobRepository(): ImportJobRepository = RoomVideoRepository(this, UnavailableMediaReadiness, UnavailableSubtitleReadiness)
    fun watchProgressRepository(): WatchProgressRepository = RoomVideoRepository(this, UnavailableMediaReadiness, UnavailableSubtitleReadiness)

    fun familyRepository(): FamilyRepository = RoomFamilyRepository(this)
    fun profileRepository(): ProfileRepository = RoomProfileRepository(this)
    fun profileDirectory(): ProfileDirectory = RoomProfileRepository(this)
    fun profileDeletionTransaction(): ProfileDeletionTransaction = ProfileDeletionTransaction { block ->
        withTransaction { block() }
    }

    companion object {
        /** The application owns one instance and closes it only when its storage scope ends. */
        fun open(context: Context): FamilyDatabase = Room.databaseBuilder(
            context.applicationContext, FamilyDatabase::class.java, "family.db",
        ).addMigrations(VideoMigration.MIGRATION_1_2, SubtitleMigration.MIGRATION_2_3).build()
    }
}
