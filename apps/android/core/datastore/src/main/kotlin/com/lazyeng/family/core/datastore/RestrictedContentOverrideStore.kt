package com.lazyeng.family.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lazyeng.family.core.common.RestrictedContentOverrideAudit
import com.lazyeng.family.core.common.RestrictedContentOverrideEvent
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import java.time.Instant
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.contentOverrideAuditDataStore by preferencesDataStore(name = "content_override_audit")

/** Stores only scope IDs and authorization time; no PIN, name, media URI, or content is recorded. */
class PreferencesRestrictedContentOverrideAudit(
    private val store: DataStore<Preferences>,
) : RestrictedContentOverrideAudit {
    override suspend fun append(event: RestrictedContentOverrideEvent) {
        store.edit { preferences ->
            val entries = preferences[EVENTS]?.takeIf(String::isNotBlank)?.let(::JSONArray) ?: JSONArray()
            entries.put(JSONObject()
                .put("familyId", event.familyId.value)
                .put("profileId", event.profileId.value)
                .put("authorizedAt", event.authorizedAt.toString()))
            preferences[EVENTS] = entries.toString()
        }
    }

    override suspend fun list(profileId: ProfileId): List<RestrictedContentOverrideEvent> =
        (store.data.first()[EVENTS]?.takeIf(String::isNotBlank)?.let(::JSONArray) ?: JSONArray()).let { entries ->
            (0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    RestrictedContentOverrideEvent(
                        FamilyId(entry.getString("familyId")),
                        ProfileId(entry.getString("profileId")),
                        Instant.parse(entry.getString("authorizedAt")),
                    )
                }.getOrNull()?.takeIf { it.profileId == profileId }
            }
        }

    companion object {
        private val EVENTS = stringPreferencesKey("events")

        fun create(context: Context): RestrictedContentOverrideAudit =
            PreferencesRestrictedContentOverrideAudit(context.applicationContext.contentOverrideAuditDataStore)
    }
}
