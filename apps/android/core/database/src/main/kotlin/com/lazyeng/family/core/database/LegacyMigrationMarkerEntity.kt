package com.lazyeng.family.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "legacy_migration_markers")
internal data class LegacyMigrationMarkerEntity(@PrimaryKey val id: String)
