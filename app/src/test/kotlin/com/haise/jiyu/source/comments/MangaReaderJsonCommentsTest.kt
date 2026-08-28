package com.haise.jiyu.source.comments

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderJsonCommentsTest {

    @Test
    fun `parses a single comment with all fields`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "abc123", "content": "Love it", "user": {"name": "Tester"}, "created_at": "2026-08-28T21:51:22.000Z"}
              ],
              "comments_count": 1
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("abc123", c.id)
        assertEquals("Tester", c.author)
        assertEquals("Love it", c.content)
        assertTrue(c.createdAt > 0L)
    }

    @Test
    fun `parses multiple comments`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "1", "content": "First", "user": {"name": "A"}, "created_at": "2026-08-28T21:00:00.000Z"},
                {"id": "2", "content": "Second", "user": {"name": "B"}, "created_at": "2026-08-28T22:00:00.000Z"}
              ]
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(2, result.size)
        assertEquals("A", result[0].author)
        assertEquals("B", result[1].author)
    }

    @Test
    fun `missing latest_comments field returns empty list`() {
        val json = JSONObject("""{"comments_count": 0}""")

        assertEquals(emptyList<ChapterComment>(), parseMangaReaderJsonComments(json))
    }

    @Test
    fun `null latest_comments returns empty list`() {
        val json = JSONObject("""{"latest_comments": null}""")

        assertEquals(emptyList<ChapterComment>(), parseMangaReaderJsonComments(json))
    }

    @Test
    fun `comment without id is skipped`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"content": "No id here", "user": {"name": "C"}, "created_at": "2026-08-28T21:00:00.000Z"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(emptyList<ChapterComment>(), parseMangaReaderJsonComments(json))
    }

    @Test
    fun `comment without user falls back to unknown author`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "1", "content": "Anonymous-ish", "created_at": "2026-08-28T21:00:00.000Z"}
              ]
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(1, result.size)
        assertEquals("?", result[0].author)
    }

    @Test
    fun `unparseable created_at defaults to zero`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "1", "content": "Bad date", "user": {"name": "D"}, "created_at": "not-a-date"}
              ]
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(1, result.size)
        assertEquals(0L, result[0].createdAt)
    }
}
