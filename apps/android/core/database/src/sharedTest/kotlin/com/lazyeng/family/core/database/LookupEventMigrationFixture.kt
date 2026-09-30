package com.lazyeng.family.core.database

import android.database.Cursor

internal object LookupEventMigrationFixture {
    val tables = listOf(
        "families", "profiles", "videos", "video_assets", "import_jobs", "watch_progress",
        "subtitle_tracks", "subtitle_versions", "subtitle_lines",
    )

    fun snapshot(query: (String) -> Cursor): Map<String, List<List<String?>>> = tables.associateWith { table ->
        query("SELECT * FROM $table ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }
    }
}
