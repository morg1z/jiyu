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
