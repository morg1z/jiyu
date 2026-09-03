package com.haise.jiyu.data.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy na [parseMalSearchResults]/[parseMalUserStatus] - čisté JSON->doménové mapování
 * vytažené z [MalRepository], aby šlo otestovat bez OkHttp/síťové vrstvy.
 */
class MalRepositoryTest {

    @Test
    fun `search results map every field, including nested main_picture and mean score`() {
        val json = """
            {"data":[{"node":{
                "id": 42, "title": "Test Manga",
                "main_picture": {"medium": "https://example.com/cover.jpg"},
                "mean": 8.5, "status": "currently_publishing",
                "synopsis": "A story about testing."
            }}]}
        """.trimIndent()

        val result = parseMalSearchResults(json).single()

        assertEquals(42, result.id)
        assertEquals("Test Manga", result.title)
        assertEquals("https://example.com/cover.jpg", result.coverUrl)
        assertEquals(8.5f, result.score)
        assertEquals("currently_publishing", result.status)
        assertEquals("A story about testing.", result.synopsis)
    }

    @Test
    fun `a mean score of exactly zero is treated as no score, not a real 0 rating`() {
        // MAL skore je 1-10 - 0 vzdy znamena "zatim nehodnoceno", nikdy realnou hodnotu.
        val json = """{"data":[{"node":{"id":1,"title":"T","mean":0.0}}]}"""

        assertNull(parseMalSearchResults(json).single().score)
    }

    @Test
    fun `missing optional fields fall back to null instead of throwing`() {
        val json = """{"data":[{"node":{"id":1,"title":"T"}}]}"""

        val result = parseMalSearchResults(json).single()

        assertNull(result.coverUrl)
        assertNull(result.score)
        assertNull(result.status)
        assertNull(result.synopsis)
    }

    @Test
    fun `a long synopsis is truncated to 200 characters`() {
        val long = "x".repeat(500)
        val json = """{"data":[{"node":{"id":1,"title":"T","synopsis":"$long"}}]}"""

        assertEquals(200, parseMalSearchResults(json).single().synopsis?.length)
    }

    @Test
    fun `an empty results array parses as an empty list`() {
        assertTrue(parseMalSearchResults("""{"data":[]}""").isEmpty())
    }

    @Test
    fun `a user status with a real score and progress maps every field`() {
        val json = """
            {"my_list_status": {"status": "reading", "score": 7, "num_chapters_read": 42}}
        """.trimIndent()

        val result = parseMalUserStatus(json)

        assertEquals("reading", result?.status)
        assertEquals(7, result?.score)
        assertEquals(42, result?.numChaptersRead)
    }

    @Test
    fun `a score or chapter count of zero is treated as unset, not a real value`() {
        val json = """{"my_list_status": {"status": "plan_to_read", "score": 0, "num_chapters_read": 0}}"""

        val result = parseMalUserStatus(json)

        assertNull(result?.score)
        assertNull(result?.numChaptersRead)
    }

    @Test
    fun `a manga the user never added to their list has no my_list_status and maps to null`() {
        assertNull(parseMalUserStatus("""{}"""))
    }
}
