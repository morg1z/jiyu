package com.haise.jiyu.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perzistence kandidatu pro prepinac zdroju v ctecce - [SourceResolutionCodec].
 * Chyba v (de)serializaci by ctecka ukazala prazdny seznam zdroju, proto
 * roundtrip + odolnost proti poskozenemu/staremu JSONu.
 */
class SourceResolutionCodecTest {

    @Test
    fun `roundtrip zachova vsechna pole`() {
        val candidates = listOf(
            CachedSourceCandidate(
                sourceId = "asura",
                mangaUrl = "/comic/abc",
                mangaTitle = "Solo Leveling",
                coverUrl = "https://img/c.jpg",
                minChapter = 0f,
                maxChapter = 179f,
                chapterCount = 180,
            ),
            CachedSourceCandidate(
                sourceId = "comickart",
                mangaUrl = "/t/xyz",
                mangaTitle = "Solo Leveling",
                coverUrl = null,
                minChapter = null,
                maxChapter = null,
                chapterCount = 0,
            ),
        )

        val decoded = SourceResolutionCodec.decode(SourceResolutionCodec.encode(candidates))

        assertEquals(candidates, decoded)
    }

    @Test
    fun `decode prazdneho a poskozeneho jsonu vrati prazdny seznam`() {
        assertTrue(SourceResolutionCodec.decode("").isEmpty())
        assertTrue(SourceResolutionCodec.decode("[]").isEmpty())
        assertTrue(SourceResolutionCodec.decode("neni-json").isEmpty())
        assertTrue(SourceResolutionCodec.decode("{}").isEmpty())
    }

    @Test
    fun `decode prezije polozku s chybejicimi poli`() {
        // Starsi verze zaznamu / rucne upraveny zaznam - chybejici klice nesmi
        // shodit cely seznam (o chybejici kandidaty by ctecka prišla potichu).
        val json = """[{"sourceId":"asura","mangaUrl":"/m"},{"sourceId":"b","mangaUrl":"/n","mangaTitle":"T","coverUrl":null,"minChapter":1.0,"maxChapter":5.0,"chapterCount":4}]"""
        val decoded = SourceResolutionCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("asura", decoded[0].sourceId)
        assertNull(decoded[0].coverUrl)
        assertNull(decoded[0].minChapter)
        assertEquals(4, decoded[1].chapterCount)
    }

    @Test
    fun `roundtrip zachova desetinna cisla kapitol`() {
        val c = CachedSourceCandidate("s", "/m", "T", null, 1.5f, 10.5f, 9)
        val decoded = SourceResolutionCodec.decode(SourceResolutionCodec.encode(listOf(c))).single()
        assertEquals(1.5f, decoded.minChapter!!, 0.001f)
        assertEquals(10.5f, decoded.maxChapter!!, 0.001f)
    }
}
