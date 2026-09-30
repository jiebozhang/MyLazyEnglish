package com.lazyeng.family.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FamilyVocabularyMigration {
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `vocabulary_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `profile_id` TEXT NOT NULL, `lemma` TEXT NOT NULL, `status` TEXT NOT NULL,
                `stage` INTEGER NOT NULL, `next_review_at_ms` INTEGER,
                `review_count` INTEGER NOT NULL DEFAULT 0, `lapse_count` INTEGER NOT NULL DEFAULT 0,
                `consecutive_remembered` INTEGER NOT NULL DEFAULT 0, `last_reviewed_at_ms` INTEGER,
                FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`id`) ON UPDATE RESTRICT ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `vocabulary_contexts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `profile_id` TEXT NOT NULL, `lemma` TEXT NOT NULL, `surface` TEXT NOT NULL,
                `phonetic` TEXT, `definition` TEXT, `sense_id` TEXT, `context_sentence` TEXT NOT NULL,
                `video_id` TEXT NOT NULL, `line_id` TEXT NOT NULL, `timestamp_ms` INTEGER NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                FOREIGN KEY(`profile_id`, `lemma`) REFERENCES `vocabulary_items`(`profile_id`, `lemma`)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `review_attempts` (
                `id` TEXT NOT NULL, `event_uuid` TEXT NOT NULL, `profile_id` TEXT NOT NULL,
                `lemma` TEXT NOT NULL, `rating` TEXT NOT NULL, `occurred_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`id`) ON UPDATE RESTRICT ON DELETE CASCADE
            )""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_items_profile_id_lemma` ON `vocabulary_items` (`profile_id`, `lemma`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_items_profile_id` ON `vocabulary_items` (`profile_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_contexts_profile_id_lemma_video_id_line_id_timestamp_ms` ON `vocabulary_contexts` (`profile_id`, `lemma`, `video_id`, `line_id`, `timestamp_ms`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_contexts_profile_id_lemma` ON `vocabulary_contexts` (`profile_id`, `lemma`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_attempts_profile_id_lemma_occurred_at_ms` ON `review_attempts` (`profile_id`, `lemma`, `occurred_at_ms`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_review_attempts_profile_id_event_uuid` ON `review_attempts` (`profile_id`, `event_uuid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `legacy_migration_markers` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }
}
