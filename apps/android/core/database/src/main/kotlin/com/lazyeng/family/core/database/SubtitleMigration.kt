package com.lazyeng.family.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SubtitleMigration {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS subtitle_tracks (id TEXT NOT NULL PRIMARY KEY, video_id TEXT NOT NULL,
                language TEXT NOT NULL, source_type TEXT NOT NULL, current_version_id TEXT,
                FOREIGN KEY(video_id) REFERENCES videos(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(id, current_version_id) REFERENCES subtitle_versions(track_id, id) ON UPDATE NO ACTION ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subtitle_tracks_video_id ON subtitle_tracks(video_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subtitle_tracks_id_current_version_id ON subtitle_tracks(id, current_version_id)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS subtitle_versions (id TEXT NOT NULL PRIMARY KEY, track_id TEXT NOT NULL,
                source_hash TEXT NOT NULL, parser_ver TEXT NOT NULL, status TEXT NOT NULL, source_encoding TEXT, published_at TEXT,
                FOREIGN KEY(track_id) REFERENCES subtitle_tracks(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_subtitle_versions_track_id_id ON subtitle_versions(track_id, id)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS subtitle_lines (id TEXT NOT NULL PRIMARY KEY, version_id TEXT NOT NULL,
                seq INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL,
                FOREIGN KEY(version_id) REFERENCES subtitle_versions(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_subtitle_lines_version_id_seq ON subtitle_lines(version_id, seq)")
        }
    }
}
