package com.lazyeng.family.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AiContentMigration {
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS ai_generated_content (
                id TEXT NOT NULL PRIMARY KEY, family_id TEXT NOT NULL, feature TEXT NOT NULL,
                cache_key TEXT NOT NULL, input_ref TEXT NOT NULL, output_json TEXT,
                schema_version TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, safety_flag TEXT,
                FOREIGN KEY(family_id) REFERENCES families(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_generated_content_family_id_cache_key ON ai_generated_content(family_id, cache_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_generated_content_family_id_feature ON ai_generated_content(family_id, feature)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS ai_jobs (
                id TEXT NOT NULL PRIMARY KEY, family_id TEXT NOT NULL, feature TEXT NOT NULL, status TEXT NOT NULL,
                progress INTEGER NOT NULL, error_code TEXT, usage_bucket TEXT, idempotency_key TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(family_id) REFERENCES families(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_jobs_family_id_idempotency_key ON ai_jobs(family_id, idempotency_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_jobs_family_id_created_at_ms ON ai_jobs(family_id, created_at_ms)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS ai_budget_usage (
                family_id TEXT NOT NULL, period TEXT NOT NULL, window_key TEXT NOT NULL,
                request_count INTEGER NOT NULL, cache_hits INTEGER NOT NULL, usage_bucket TEXT NOT NULL,
                request_limit INTEGER, usage_limit INTEGER, PRIMARY KEY(family_id, period, window_key),
                FOREIGN KEY(family_id) REFERENCES families(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        }
    }
}
