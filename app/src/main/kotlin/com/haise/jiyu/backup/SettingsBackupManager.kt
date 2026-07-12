package com.haise.jiyu.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exportuje/importuje uživatelská nastavení (DataStore Preferences) do JSON.
 * Autentizační tokeny (MAL/Kitsu/MangaUpdates/AniList) se záměrně vynechávají -
 * export souboru se dá sdílet/nahrát do cloudu a token uvnitř by byl bezpečnostní riziko.
 */
@Singleton
class SettingsBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val EXCLUDED_KEYS = setOf(
            "mal_access_token", "mal_refresh_token", "mal_code_verifier",
            "kitsu_access_token", "kitsu_refresh_token", "kitsu_username", "kitsu_user_id",
            "mu_session_token", "mu_username",
            "anilist_access_token",
        )
    }

    suspend fun exportToUri(uri: Uri): Result<Unit> = runCatching {
        val prefs = dataStore.data.first()
        val entries = JSONArray()
        prefs.asMap().forEach { (key, value) ->
            if (key.name in EXCLUDED_KEYS) return@forEach
            val type = when (value) {
                is Boolean -> "boolean"
                is Int     -> "int"
                is Long    -> "long"
                is Float   -> "float"
                is String  -> "string"
                is Set<*>  -> "stringSet"
                else       -> null
            } ?: return@forEach
            entries.put(JSONObject().apply {
                put("key", key.name)
                put("type", type)
                put("value", if (value is Set<*>) JSONArray(value.toList()) else value)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("exportedAt", java.time.Instant.now().toString())
            put("settings", entries)
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
            ?: error("Nelze otevřít výstupní soubor")
    }

    suspend fun importFromUri(uri: Uri): Result<Int> = runCatching {
        val json = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Nelze otevřít soubor zálohy")
        val root = JSONObject(json)
        val entries = root.optJSONArray("settings") ?: JSONArray()
        var count = 0
        dataStore.edit { prefs ->
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val name = e.getString("key")
                if (name in EXCLUDED_KEYS) continue
                when (e.getString("type")) {
                    "boolean" -> prefs[booleanPreferencesKey(name)] = e.getBoolean("value")
                    "int"     -> prefs[intPreferencesKey(name)] = e.getInt("value")
                    "long"    -> prefs[longPreferencesKey(name)] = e.getLong("value")
                    "float"   -> prefs[floatPreferencesKey(name)] = e.getDouble("value").toFloat()
                    "string"  -> prefs[stringPreferencesKey(name)] = e.getString("value")
                    "stringSet" -> {
                        val arr = e.getJSONArray("value")
                        prefs[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                }
                count++
            }
        }
        count
    }
}
