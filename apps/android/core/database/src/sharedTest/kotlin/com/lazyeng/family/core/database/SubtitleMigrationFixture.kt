package com.lazyeng.family.core.database

import android.database.Cursor

/** Synthetic rows with populated optional fields, tombstones and distinct household ownership. */
internal object SubtitleMigrationFixture {
    val tables = listOf("families", "profiles", "videos", "video_assets", "import_jobs", "watch_progress")
    fun seed(exec: (String) -> Unit) {
        for (suffix in listOf("a", "b")) {
            exec("""INSERT INTO families VALUES ('family-$suffix', '{"example":"$suffix"}', '2026-01-01T00:00:00.123456789Z')""")
            exec("""INSERT INTO profiles VALUES ('profile-$suffix', 'family-$suffix', 'Synthetic $suffix', 'avatar-example',
                'CHILD', 'child', 'Power Up 2', '{"font":"large"}', '2026-01-01T00:00:00.123456789Z', NULL)""")
            exec("""INSERT INTO videos VALUES ('video-$suffix', 'family-$suffix', 'LOCAL_FILE', 'content://fixture.example/document/$suffix',
                'synthetic-checksum', '{"title":"Synthetic clip","coverAssetId":null,"durationMs":12000,"topicTags":["example"],"cefrLevel":"A1","subtitleWordCount":8,"accentTag":"example","ageFit":"child","levelSource":"MANUAL"}',
                'NEEDS_SUBTITLE', 'NEEDS_SUBTITLE', 'NO_SUBTITLE', 'NOT_REQUESTED', 0,
                '{"ownerType":"FAMILY","usageNote":"Synthetic","addedBy":"profile-$suffix","sourceTermsAcknowledged":true}',
                'NO_SUBTITLE', 'Attach a synthetic subtitle')""")
            exec("""INSERT INTO video_assets VALUES ('asset-$suffix', 'video-$suffix', 'content://fixture.example/document/$suffix', 'synthetic-checksum', 'video/avc', 1024)""")
            exec("""INSERT INTO import_jobs VALUES ('job-$suffix', 'video-$suffix', 'NEEDS_SUBTITLE', 'synthetic-key-$suffix', 'NO_SUBTITLE')""")
            exec("""INSERT INTO watch_progress VALUES ('profile-$suffix', 'video-$suffix', 1250, 0.25, '2026-01-01T00:00:01.123456789Z')""")
        }
        exec("""INSERT INTO profiles VALUES ('profile-deleted', 'family-a', 'Synthetic tombstone', NULL,
            'ADULT', 'adult', 'A1', '{}', '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z')""")
    }
    fun snapshot(query: (String) -> Cursor): Map<String, List<List<String?>>> = tables.associateWith { table ->
        query("SELECT * FROM $table ORDER BY 1, 2").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
}
