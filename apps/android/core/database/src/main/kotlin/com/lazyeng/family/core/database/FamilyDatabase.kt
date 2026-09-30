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
import com.lazyeng.family.core.model.VocabularyRepository
import com.lazyeng.family.core.model.ReviewAttemptRepository
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.security.ParentSession

@Database(entities = [FamilyEntity::class, ProfileEntity::class, VideoEntity::class, VideoAssetEntity::class,
    ImportJobEntity::class, WatchProgressEntity::class, SubtitleTrackEntity::class,
    SubtitleVersionEntity::class, SubtitleLineEntity::class, LookupEventEntity::class,
    VocabularyItemEntity::class, VocabularyContextEntity::class, ReviewAttemptEntity::class,
    LegacyMigrationMarkerEntity::class, AiGeneratedContentEntity::class, AiJobEntity::class,
    AiBudgetUsageEntity::class], version = 6, exportSchema = true)
@TypeConverters(FamilyConverters::class, VideoConverters::class)
abstract class FamilyDatabase : RoomDatabase() {
    internal abstract fun familyDao(): FamilyDao
    internal abstract fun profileDao(): ProfileDao
    internal abstract fun videoDao(): VideoDao
    internal abstract fun subtitleDao(): SubtitleDao
    internal abstract fun lookupEventDao(): LookupEventDao
    internal abstract fun legacyMigrationDao(): LegacyMigrationDao
    internal abstract fun vocabularyDao(): VocabularyDao
    internal abstract fun aiContentDao(): AiContentDao

    fun subtitleRepository(): SubtitleCatalog = RoomSubtitleRepository(this)
    fun lookupEventRepository(): com.lazyeng.family.core.model.LookupEventRepository = RoomLookupEventRepository(this)
    fun vocabularyRepository(): VocabularyRepository = RoomVocabularyRepository(this)
    fun vocabularyRepository(context: Context): VocabularyRepository = LegacyMigratingVocabularyRepository(
        RoomVocabularyRepository(this), LegacyVocabularyMigration(context.applicationContext, this),
    )
    fun reviewAttemptRepository(): ReviewAttemptRepository = RoomReviewAttemptRepository(this)
    fun aiContentRepository(): com.lazyeng.family.core.model.AiContentRepository = RoomAiContentRepository(this)
    fun aiJobRepository(): com.lazyeng.family.core.model.AiJobRepository = RoomAiContentRepository(this)
    fun aiBudgetUsageRepository(): com.lazyeng.family.core.model.AiBudgetUsageRepository = RoomAiContentRepository(this)
    fun subtitleReadinessChecker(): SubtitleReadinessChecker = RoomSubtitleReadinessChecker(this)
    fun importTransaction(): ImportTransaction = ImportTransaction { block -> withTransaction { block() } }

    fun videoRepository(media: MediaReadinessChecker = UnavailableMediaReadiness,
        subtitles: SubtitleReadinessChecker = subtitleReadinessChecker(),
        parentSession: ParentSession? = null): VideoCatalog = RoomVideoRepository(this, media, subtitles, parentSession)
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
        ).addMigrations(VideoMigration.MIGRATION_1_2, SubtitleMigration.MIGRATION_2_3,
            LookupEventMigration.MIGRATION_3_4, FamilyVocabularyMigration.MIGRATION_4_5,
            AiContentMigration.MIGRATION_5_6).build()
    }
}
