package com.lazyeng.family.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object LookupEventMigration {
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS lookup_events (
                id TEXT NOT NULL PRIMARY KEY,
                event_uuid TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                word TEXT NOT NULL,
                lemma TEXT NOT NULL,
                line_id TEXT,
                video_id TEXT,
                occurred_at TEXT NOT NULL,
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lookup_events_profile_id_lemma_line_id ON lookup_events(profile_id, lemma, line_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lookup_events_event_uuid ON lookup_events(event_uuid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_lookup_events_profile_id_occurred_at ON lookup_events(profile_id, occurred_at)")
        }
    }
}
