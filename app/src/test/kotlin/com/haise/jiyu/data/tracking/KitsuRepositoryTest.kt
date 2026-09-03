package com.haise.jiyu.data.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy na [parseKitsuSearchResults]/[parseKitsuLibraryEntry] - čisté JSON->doménové
 * mapování vytažené z [KitsuRepository], aby šlo otestovat bez OkHttp/síťové vrstvy.
 */
class KitsuRepositoryTest {

    @Test
    fun `title prefers english over english-romanized over japanese`() {
        val json = """
            {"data":[{"id":"1","attributes":{
                "titles": {"en": "English Title", "en_jp": "Romaji Title", "ja_jp": "日本語"}
            }}]}
        """.trimIndent()

        assertEquals("English Title", parseKitsuSearchResults(json).single().title)
    }

    @Test
    fun `title falls back to romanized japanese when english is blank`() {
        val json = """
            {"data":[{"id":"1","attributes":{
                "titles": {"en": "", "en_jp": "Romaji Title", "ja_jp": "日本語"}
            }}]}
        """.trimIndent()

        assertEquals("Romaji Title", parseKitsuSearchResults(json).single().title)
    }

    @Test
    fun `title falls back to japanese when nothing else is available`() {
        val json = """{"data":[{"id":"1","attributes":{"titles": {"ja_jp": "日本語"}}}]}"""

        assertEquals("日本語", parseKitsuSearchResults(json).single().title)
    }

    @Test
    fun `title is an empty string, not a crash, when no titles object exists at all`() {
        val json = """{"data":[{"id":"1","attributes":{}}]}"""

        assertEquals("", parseKitsuSearchResults(json).single().title)
    }

    @Test
    fun `averageRating on Kitsu's 0-100 scale converts to a 0-5 score`() {
        val json = """{"data":[{"id":"1","attributes":{"titles":{},"averageRating":"75.5"}}]}"""

        assertEquals(3.775f, parseKitsuSearchResults(json).single().score!!, 0.001f)
    }

    @Test
    fun `a missing or non-numeric averageRating maps to a null score, not zero`() {
        val json = """{"data":[{"id":"1","attributes":{"titles":{}}}]}"""

        assertNull(parseKitsuSearchResults(json).single().score)
    }

    @Test
    fun `an empty search result set parses as an empty list`() {
        assertTrue(parseKitsuSearchResults("""{"data":[]}""").isEmpty())
    }

    @Test
    fun `a library entry with every field set maps completely`() {
        val json = """
            {"data":[{"attributes":{"status":"current","ratingTwenty":16,"progress":12}}]}
        """.trimIndent()

        val result = parseKitsuLibraryEntry(json)

        assertEquals("current", result?.status)
        assertEquals(16, result?.ratingTwenty)
        assertEquals(12, result?.progress)
    }

    @Test
    fun `a rating or progress of zero is treated as unset, not a real value`() {
        val json = """{"data":[{"attributes":{"status":"planned","ratingTwenty":0,"progress":0}}]}"""

        val result = parseKitsuLibraryEntry(json)

        assertNull(result?.ratingTwenty)
        assertNull(result?.progress)
    }

    @Test
    fun `no library entry for this manga returns null instead of crashing on an empty array`() {
        assertNull(parseKitsuLibraryEntry("""{"data":[]}"""))
    }

    @Test
    fun `a missing data field returns null instead of crashing`() {
        assertNull(parseKitsuLibraryEntry("""{}"""))
    }
}
