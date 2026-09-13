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
    @param:ApplicationContext private val context: Context,
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
        filterAndTagForExport(prefs.asMap().mapKeys { it.key.name }, EXCLUDED_KEYS).forEach { entry ->
            entries.put(JSONObject().apply {
                put("key", entry.key)
                put("type", entry.type)
                put("value", if (entry.value is Set<*>) JSONArray(entry.value.toList()) else entry.value)
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
        val entries = parseSettingsEntries(json, EXCLUDED_KEYS)
        dataStore.edit { prefs ->
            entries.forEach { entry ->
                when (entry.type) {
                    "boolean" -> prefs[booleanPreferencesKey(entry.key)] = entry.value as Boolean
                    "int" -> prefs[intPreferencesKey(entry.key)] = entry.value as Int
                    "long" -> prefs[longPreferencesKey(entry.key)] = entry.value as Long
                    "float" -> prefs[floatPreferencesKey(entry.key)] = entry.value as Float
                    "string" -> prefs[stringPreferencesKey(entry.key)] = entry.value as String
                    "stringSet" -> {
                        @Suppress("UNCHECKED_CAST")
                        prefs[stringSetPreferencesKey(entry.key)] = entry.value as Set<String>
                    }
                    // "unknown" - neznamy typ z (napr. rucne upraveneho) souboru zalohy se
                    // nikam nezapise, ale i puvodni kod ho pocital do vysledneho poctu -
                    // zachovano beze zmeny chovani.
                }
            }
        }
        entries.size
    }
}

/** Jeden záznam nastavení - typ určuje, jak se `value` zapíše do DataStore [Preferences]. */
internal data class SettingsEntry(val key: String, val type: String, val value: Any)

/**
 * Vytaženo z [SettingsBackupManager.exportToUri] jako čistá funkce (bez [DataStore]/
 * [Preferences] typů), aby šlo otestovat bez Android runtime.
 */
internal fun filterAndTagForExport(prefs: Map<String, Any>, excludedKeys: Set<String>): List<SettingsEntry> {
    val result = mutableListOf<SettingsEntry>()
    prefs.forEach { (name, value) ->
        if (name in excludedKeys) return@forEach
        val type = when (value) {
            is Boolean -> "boolean"
            is Int     -> "int"
            is Long    -> "long"
            is Float   -> "float"
            is String  -> "string"
            is Set<*>  -> "stringSet"
            else       -> null
        } ?: return@forEach
        result.add(SettingsEntry(name, type, value))
    }
    return result
}

/**
 * Vytaženo z [SettingsBackupManager.importFromUri] jako čistá funkce, aby šlo otestovat
 * bez [DataStore]. Neznámý "type" (poškozený/ručně upravený soubor zálohy) vrátí záznam
 * typu "unknown" místo pádu - stejné chování jako původní `when` bez `else` větve.
 */
internal fun parseSettingsEntries(json: String, excludedKeys: Set<String>): List<SettingsEntry> {
    val root = JSONObject(json)
    val entries = root.optJSONArray("settings") ?: JSONArray()
    val result = mutableListOf<SettingsEntry>()
    for (i in 0 until entries.length()) {
        // Per-polozkovy try/catch - bez nej jedna poskozena/rucne upravena polozka
        // (JSONException z chybejiciho/spatne typovaneho pole) shodila parsovani VSECH
        // ostatnich platnych zaznamu a cela obnova zalohy skoncila s 0 obnovenymi
        // nastavenimi (nahlaseno v auditu).
        try {
            val e = entries.getJSONObject(i)
            val name = e.getString("key")
            if (name in excludedKeys) continue
            val entry = when (e.getString("type")) {
                "boolean" -> SettingsEntry(name, "boolean", e.getBoolean("value"))
                "int" -> SettingsEntry(name, "int", e.getInt("value"))
                "long" -> SettingsEntry(name, "long", e.getLong("value"))
                "float" -> SettingsEntry(name, "float", e.getDouble("value").toFloat())
                "string" -> SettingsEntry(name, "string", e.getString("value"))
                "stringSet" -> {
                    val arr = e.getJSONArray("value")
                    SettingsEntry(name, "stringSet", (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
                else -> SettingsEntry(name, "unknown", Unit)
            }
            result.add(entry)
        } catch (_: org.json.JSONException) {
            // Preskocit tenhle jeden zaznam, zbytek zalohy se obnovi normalne.
        }
    }
    return result
}
