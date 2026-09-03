package com.haise.jiyu.data.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy na [parseMuSearchResults]/[parseMuListId]/[parseMuRating]/[combineMuStatus] -
 * čisté JSON->doménové mapování vytažené z [MangaUpdatesRepository], aby šlo otestovat
 * bez OkHttp/síťové vrstvy.
 */
class MangaUpdatesRepositoryTest {

    @Test
    fun `search results map every field, including nested image url and numeric year`() {
        val json = """
            {"results":[{"record":{
                "series_id": 12345, "title": "Test Series",
                "image": {"url": {"thumb": "https://example.com/thumb.jpg"}},
                "year": "2020", "description": "A description."
            }}]}
        """.trimIndent()

        val result = parseMuSearchResults(json).single()

        assertEquals(12345L, result.id)
        assertEquals("Test Series", result.title)
        assertEquals("https://example.com/thumb.jpg", result.coverUrl)
        assertEquals(2020, result.year)
        assertEquals("A description.", result.description)
    }

    @Test
    fun `a non-numeric year is null instead of crashing`() {
        val json = """{"results":[{"record":{"series_id":1,"title":"T","year":"unknown"}}]}"""

        assertNull(parseMuSearchResults(json).single().year)
    }

    @Test
    fun `a missing results field parses as an empty list, not a crash`() {
        assertTrue(parseMuSearchResults("""{}""").isEmpty())
    }

    @Test
    fun `list_id of exactly zero (Reading list) is a real value, not treated as missing`() {
        // list_id=0 je na MangaUpdates skutecny seznam "Reading" - musi se odlisit od
        // "API nevratilo list_id vubec" (chybejici pole/zaporna hodnota).
        assertEquals(0, parseMuListId("""{"list_id": 0}"""))
    }

    @Test
    fun `a missing list_id maps to null`() {
        assertNull(parseMuListId("""{}"""))
    }

    @Test
    fun `a rating of exactly zero is treated as unset`() {
        assertNull(parseMuRating("""{"rating": 0.0}"""))
    }

    @Test
    fun `a real rating parses correctly`() {
        assertEquals(8.5f, parseMuRating("""{"rating": 8.5}"""))
    }

    @Test
    fun `both endpoints succeeding combines into a complete status`() {
        val result = combineMuStatus(listId = 0, rating = 9.0f)

        assertEquals(0, result?.listId)
        assertEquals(9.0f, result?.rating)
    }

    @Test
    fun `one endpoint failing still returns a status with just the other field`() {
        // JADRO DUVODU EXISTENCE teto funkce: getMyStatus dela DVE nezavisla API volani -
        // kdyby jedno selhalo (napr. rating endpoint timeoutne), druhe uspesne zjistene
        // pole (list_id) se nesmi zahodit jen proto, ze soused selhal.
        val result = combineMuStatus(listId = 1, rating = null)

        assertEquals(1, result?.listId)
        assertNull(result?.rating)
    }

    @Test
    fun `both endpoints failing returns null, not an empty-but-non-null status`() {
        assertNull(combineMuStatus(listId = null, rating = null))
    }
}
