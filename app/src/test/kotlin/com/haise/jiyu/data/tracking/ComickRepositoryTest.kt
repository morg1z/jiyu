package com.haise.jiyu.data.tracking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy cistych parseru z [ComickRepository] - JSON -> domene bez OkHttp/site.
 * Kriticka pravidla z dokumentace: `hid` je opaque STRING (nikdy cislo), `progress.number`
 * muze byt "12.5" i neciselny label, rating pri cteni byva desetinny, `next_cursor`
 * se preda zpet beze zmeny (null = konec).
 */
class ComickRepositoryTest {

    private fun entryJson(
        hid: String = "aB12_-xY",
        extra: String = "",
        progress: String? = """{"unit":"chapter","hid":"chHid1","number":"12.5","volume":"2"}""",
    ): String = """
        {"hid":"$hid","slug":"solo-leveling","title":"Solo Leveling","media_type":"manga",
         "status":1,"rating":7.5,"cover_url":"https://meo.comick.dev/x.jpg","country":"kr",
         "titles":[{"title":"Na Honjaman Rebelebeu","lang":"ko","is_default":true}],
         "followed_at":"2024-01-05T10:00:00Z","updated_at":"2024-02-01T10:00:00Z",
         "progressed_at":"2024-02-02T10:00:00Z","publication_status":2,
         "translation_completed":false,"translation_status":"active",
         "last_released_number":"179"${progress?.let { ",\"progress\":$it" } ?: ""}$extra}
    """.trimIndent()

    @Test
    fun `library page parses entries and opaque cursor verbatim`() {
        val body = """{"data":[${entryJson()}],"next_cursor":"eyJjX29w+c/=="}"""
        val page = parseComickLibraryPage(body)

        assertEquals(1, page.entries.size)
        // Cursor je opaque/encrypted - ulozi se verbatim, nikdy se neparsovany.
        assertEquals("eyJjX29w+c/==", page.nextCursor)
    }

    @Test
    fun `next_cursor null or the literal string null both end pagination`() {
        assertNull(parseComickLibraryPage("""{"data":[],"next_cursor":null}""").nextCursor)
        assertNull(parseComickLibraryPage("""{"data":[],"next_cursor":"null"}""").nextCursor)
    }

    @Test
    fun `hid stays an opaque string, never coerced to a number`() {
        val e = parseComickEntry(JSONObject(entryJson(hid = "9xYzA_-")))
        assertEquals("9xYzA_-", e.hid)
    }

    @Test
    fun `fractional progress number survives as a string "12-5"`() {
        val e = parseComickEntry(JSONObject(entryJson()))
        assertEquals("12.5", e.progress?.number)
        assertEquals("2", e.progress?.volume)
        assertEquals("chHid1", e.progress?.chapterHid)
        // Aplikacni vrstva ho umi precist jako Float.
        assertEquals(12.5f, e.progress?.number?.toFloatOrNull())
    }

    @Test
    fun `non-numeric progress label stays a string and toFloatOrNull is null`() {
        val e = parseComickEntry(JSONObject(entryJson(progress = """{"number":"special-1"}""")))
        assertEquals("special-1", e.progress?.number)
        assertNull(e.progress?.number?.toFloatOrNull())
    }

    @Test
    fun `missing progress and null rating parse as null, not a crash`() {
        val e = parseComickEntry(JSONObject(entryJson(progress = null, extra = "")))
        // rating je v entryJson vzdy 7.5 - otestujem ho zvlast pres null variantu.
        assertNull(e.progress)
    }

    @Test
    fun `explicit null rating parses as null while decimal rating survives`() {
        val withNull = JSONObject("""{"hid":"h1","rating":null}""")
        assertNull(parseComickEntry(withNull).rating)

        val e = parseComickEntry(JSONObject(entryJson()))
        assertEquals(7.5, e.rating!!, 0.001)
    }

    @Test
    fun `status outside 1-5 range maps to null`() {
        val e = parseComickEntry(JSONObject("""{"hid":"h","status":9}"""))
        assertNull(e.status)
    }

    @Test
    fun `anime entries parse media_type so the importer can skip them`() {
        val e = parseComickEntry(JSONObject("""{"hid":"h","media_type":"anime"}"""))
        assertEquals("anime", e.mediaType)
    }

    @Test
    fun `iso timestamps parse to epoch millis`() {
        val e = parseComickEntry(JSONObject(entryJson()))
        assertEquals(1704448800000L, e.followedAt)
        assertTrue(e.progressedAt!! > e.updatedAt!! - 1)
    }

    @Test
    fun `alternate titles keep lang and default flag`() {
        val e = parseComickEntry(JSONObject(entryJson()))
        assertEquals(1, e.titles.size)
        assertEquals("ko", e.titles[0].lang)
        assertTrue(e.titles[0].isDefault)
    }

    @Test
    fun `me response parses id username and avatar`() {
        val me = parseComickMe("""{"id":"u1","username":"reader","avatar_url":"https://img/x.png"}""")
        assertEquals("u1", me?.id)
        assertEquals("reader", me?.username)
    }

    @Test
    fun `titles search parses title results for manual linking`() {
        val body = """{"data":[{"hid":"h9","slug":"one-piece","title":"One Piece","year":1997,"country":"jp",
            "titles":[],"cover_url":"https://img/c.jpg"}]}"""
        val titles = parseComickTitles(body)
        assertEquals(1, titles.size)
        assertEquals("h9", titles[0].hid)
        assertEquals("one-piece", titles[0].slug)
        assertEquals(1997, titles[0].year)
    }

    @Test
    fun `malformed titles body yields empty list instead of throwing`() {
        assertTrue(parseComickTitles("not json").isEmpty())
    }
}
