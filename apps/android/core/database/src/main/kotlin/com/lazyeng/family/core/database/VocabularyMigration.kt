package com.lazyeng.family.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object VocabularyMigration {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE vocabulary_items ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vocabulary_items ADD COLUMN lapse_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vocabulary_items ADD COLUMN consecutive_remembered INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vocabulary_items ADD COLUMN last_reviewed_at_ms INTEGER")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_review_attempts_profile_id_event_uuid ON review_attempts(profile_id, event_uuid)")
        }
    }
}
