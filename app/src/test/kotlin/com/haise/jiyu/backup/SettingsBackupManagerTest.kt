package com.haise.jiyu.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy na [filterAndTagForExport] a [parseSettingsEntries], vytažené z
 * [SettingsBackupManager] mimo [androidx.datastore.core.DataStore]/`Context`, aby šly
 * otestovat bez Android runtime. Hlavní věc, která se tu hlídá: token vylučovací seznam
 * (`EXCLUDED_KEYS`) opravdu nikdy neprojde do exportu - export souboru se dá sdílet,
 * takže únik autentizačního tokenu by byl bezpečnostní incident, ne jen kosmetická chyba.
 */
class SettingsBackupManagerTest {

    private val excluded = setOf("mal_access_token", "kitsu_refresh_token")

    @Test
    fun `the real EXCLUDED_KEYS covers every credential, session and live-state key`() {
        // Regrese SET-4: exportni soubor se sdili - nesmi obsahovat tajemstvi, a obnova
        // stare zalohy nesmi prepsat novejsi zivy stav. Kdyz nekdo prida novy token/key
        // tohoto typu do SettingsKeys bez pridani sem, test ho chyti jen kdyz ho sem
        // doplni i do tehle kontrolni sady - proto kontrolujeme oba smery nezavisle.
        val mustBeExcluded = setOf(
            // Tajemstvi / session
            "mal_access_token", "mal_refresh_token", "mal_code_verifier",
            "kitsu_access_token", "kitsu_refresh_token", "kitsu_username", "kitsu_user_id",
            "mu_session_token", "mu_username",
            "anilist_access_token",
            "cloudflare_clearance_cache", "mangacloud_session_cache",
            // Stav navazany na lokalni DB / zivy beh
            "pending_removed_manga_ids", "local_data_owner_id",
            "anilist_id_map", "pending_update_download_id",
            "sync_last_chapter_push_at", "cloudflare_warmup_hosts",
            // Statistiky - maji vlastni export
            "total_reading_time_ms", "daily_reading_time_ms", "daily_reading_day",
            "total_pages_read", "reading_streak_days", "last_read_date",
            "new_chapters_count",
        )
        val missing = mustBeExcluded - SettingsBackupManager.EXCLUDED_KEYS
        assertTrue("EXCLUDED_KEYS is missing keys: $missing", missing.isEmpty())
    }

    @Test
    fun `ordinary user preferences are NOT excluded from the backup`() {
        // Ochrana proti nadmernemu vylouceni - bezne preference se maji zalohovat.
        val mustBeExported = setOf(
            "theme", "theme_accent", "reading_mode", "reading_direction",
            "target_language", "source_language", "mangadex_chapter_language",
            "webtoon_scroll_speed", "reader_text_scale", "tap_zone_grid",
            "favorite_source_ids", "saved_searches", "custom_css_inject",
            "proxy_host", "proxy_port", "proxy_type",
        )
        val wronglyExcluded = mustBeExported.intersect(SettingsBackupManager.EXCLUDED_KEYS)
        assertTrue("These preferences must stay in the backup: $wronglyExcluded", wronglyExcluded.isEmpty())
    }

    @Test
    fun `an excluded auth-token key never appears in the export, regardless of its type`() {
        val prefs = mapOf(
            "mal_access_token" to "secret-token-value",
            "kitsu_refresh_token" to "another-secret",
            "reader_dark_mode" to true,
        )

        val result = filterAndTagForExport(prefs, excluded)

        assertTrue(result.none { it.key in excluded })
        assertTrue(result.none { it.value == "secret-token-value" || it.value == "another-secret" })
        assertEquals(1, result.size)
    }

    @Test
    fun `each supported preference type is tagged correctly for export`() {
        val prefs = mapOf(
            "flag" to true,
            "count" to 42,
            "big" to 123456789012L,
            "ratio" to 1.5f,
            "label" to "text",
            "tags" to setOf("a", "b"),
        )

        val result = filterAndTagForExport(prefs, emptySet()).associateBy { it.key }

        assertEquals("boolean", result["flag"]?.type)
        assertEquals("int", result["count"]?.type)
        assertEquals("long", result["big"]?.type)
        assertEquals("float", result["ratio"]?.type)
        assertEquals("string", result["label"]?.type)
        assertEquals("stringSet", result["tags"]?.type)
    }

    @Test
    fun `an unsupported preference value type is skipped, not crashed on`() {
        val prefs = mapOf("weird" to listOf(1, 2, 3))

        val result = filterAndTagForExport(prefs, emptySet())

        assertTrue(result.isEmpty())
    }

    private fun exportJson(vararg entries: Triple<String, String, Any>) = JsonBuilder.build(entries.toList())

    @Test
    fun `an excluded key present in an imported file is dropped even if it slipped into the backup`() {
        val json = exportJson(Triple("mal_access_token", "string", "leaked-token"), Triple("reader_dark_mode", "boolean", true))

        val result = parseSettingsEntries(json, excluded)

        assertTrue(result.none { it.key == "mal_access_token" })
        assertEquals(1, result.size)
    }

    @Test
    fun `each type round-trips through JSON with the correct Kotlin type`() {
        val json = exportJson(
            Triple("flag", "boolean", true),
            Triple("count", "int", 42),
            Triple("big", "long", 123456789012L),
            Triple("ratio", "float", 1.5),
            Triple("label", "string", "text"),
        )

        val result = parseSettingsEntries(json, emptySet()).associateBy { it.key }

        assertEquals(true, result["flag"]?.value)
        assertEquals(42, result["count"]?.value)
        assertEquals(123456789012L, result["big"]?.value)
        assertEquals(1.5f, result["ratio"]?.value)
        assertEquals("text", result["label"]?.value)
    }

    @Test
    fun `an unrecognized type is kept as a placeholder entry instead of throwing`() {
        // Puvodni kod (when bez else vetve, count++ MIMO when) pocital i nerozpoznane
        // zaznamy do vysledneho poctu, i kdyz je nikam nezapsal - zachovano beze zmeny
        // chovani, viz komentar u parseSettingsEntries.
        val json = exportJson(Triple("mystery", "future_type", "???"))

        val result = parseSettingsEntries(json, emptySet())

        assertEquals(1, result.size)
        assertEquals("unknown", result.single().type)
    }

    @Test
    fun `missing settings array parses as an empty list instead of crashing`() {
        val json = """{"version":1}"""

        assertFalse(parseSettingsEntries(json, emptySet()).isNotEmpty())
    }

    @Test
    fun `a single corrupted entry is skipped, the rest of the backup still restores`() {
        // Nahlaseny bug: "int" deklarovany typ s neciselnou hodnotou hodi JSONException,
        // ktera drive shodila parsovani VSECH ostatnich platnych zaznamu - ted se ma
        // preskocit jen tenhle jeden a zbytek zalohy se obnovi normalne.
        val arr = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply { put("key", "before"); put("type", "boolean"); put("value", true) })
            put(org.json.JSONObject().apply { put("key", "corrupted"); put("type", "int"); put("value", "not_a_number") })
            put(org.json.JSONObject().apply { put("key", "after"); put("type", "string"); put("value", "ok") })
        }
        val json = org.json.JSONObject().apply { put("version", 1); put("settings", arr) }.toString()

        val result = parseSettingsEntries(json, emptySet()).associateBy { it.key }

        assertTrue(result.containsKey("before"))
        assertTrue(result.containsKey("after"))
        assertFalse(result.containsKey("corrupted"))
        assertEquals(2, result.size)
    }

    @Test
    fun `an entry missing the required value field is skipped, not fatal`() {
        val arr = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply { put("key", "no_value"); put("type", "string") })
            put(org.json.JSONObject().apply { put("key", "fine"); put("type", "boolean"); put("value", true) })
        }
        val json = org.json.JSONObject().apply { put("version", 1); put("settings", arr) }.toString()

        val result = parseSettingsEntries(json, emptySet())

        assertEquals(1, result.size)
        assertEquals("fine", result.single().key)
    }
}

/** Malý pomocník pro sestavení stejného JSON tvaru, jaký zapisuje [SettingsBackupManager.exportToUri]. */
private object JsonBuilder {
    fun build(entries: List<Triple<String, String, Any>>): String {
        val arr = org.json.JSONArray()
        entries.forEach { (key, type, value) ->
            arr.put(org.json.JSONObject().apply {
                put("key", key)
                put("type", type)
                put("value", value)
            })
        }
        return org.json.JSONObject().apply {
            put("version", 1)
            put("settings", arr)
        }.toString()
    }
}
