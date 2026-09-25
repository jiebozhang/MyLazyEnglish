package com.lazyeng.family.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object VideoMigration {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS videos (
                id TEXT NOT NULL PRIMARY KEY, family_id TEXT NOT NULL,
                source_type TEXT NOT NULL, source_ref TEXT NOT NULL, checksum TEXT,
                metadata TEXT NOT NULL, status TEXT NOT NULL, import_status TEXT NOT NULL,
                subtitle_status TEXT NOT NULL, translation_status TEXT NOT NULL, playable INTEGER NOT NULL,
                rights TEXT NOT NULL, error_code TEXT, repair_advice TEXT,
                FOREIGN KEY(family_id) REFERENCES families(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_family_id ON videos(family_id)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS video_assets (
                id TEXT NOT NULL PRIMARY KEY, video_id TEXT NOT NULL, local_uri TEXT,
                checksum TEXT, codec TEXT, bytes INTEGER,
                FOREIGN KEY(video_id) REFERENCES videos(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_assets_video_id ON video_assets(video_id)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS import_jobs (
                id TEXT NOT NULL PRIMARY KEY, video_id TEXT NOT NULL, status TEXT NOT NULL,
                idempotency_key TEXT NOT NULL, error_code TEXT,
                FOREIGN KEY(video_id) REFERENCES videos(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_import_jobs_video_id_idempotency_key ON import_jobs(video_id, idempotency_key)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS watch_progress (
                profile_id TEXT NOT NULL, video_id TEXT NOT NULL, position_ms INTEGER NOT NULL,
                completion REAL NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(profile_id, video_id),
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(video_id) REFERENCES videos(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_progress_video_id ON watch_progress(video_id)")
        }
    }
}
