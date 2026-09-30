package com.lazyeng.family.core.database

import androidx.room.TypeConverter
import com.lazyeng.family.core.model.ProfileRole
import java.time.Instant
import org.json.JSONObject

internal class FamilyConverters {
    @TypeConverter fun encodeInstant(value: Instant?): String? = value?.toString()
    @TypeConverter fun decodeInstant(value: String?): Instant? = value?.let(Instant::parse)
    @TypeConverter fun encodeRole(value: ProfileRole): String = value.name
    @TypeConverter fun decodeRole(value: String): ProfileRole = ProfileRole.valueOf(value)
    @TypeConverter fun encodeMap(value: Map<String, String>): String = JSONObject(value).toString()
    @TypeConverter fun decodeMap(value: String): Map<String, String> {
        val json = JSONObject(value)
        return json.keys().asSequence().associateWith { key ->
            val entry = json.get(key)
            require(entry is String) { "Stored preference must be a string" }
            entry
        }
    }
}
