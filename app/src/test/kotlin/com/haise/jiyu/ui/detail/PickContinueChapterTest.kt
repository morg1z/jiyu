package com.haise.jiyu.ui.detail

import com.haise.jiyu.data.db.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testy [pickContinueChapter] - "Pokračovat" na detailu titulu.
 *
 * Audit importu z ComicK: remote progress.number = POSLEDNÍ PŘEČTENÁ kapitola,
 * takže lastReadChapterId ukazuje na dočtenou kapitolu a "Pokračovat" ji nabízelo
 * znovu (Jiyu "Pokračovat 131" vs ComicK "continue 132"). Po opravě má dočtená
 * poslední přeskočit na první nepřečtenou za ní.
 */
class PickContinueChapterTest {

    private fun ch(num: Float, read: Boolean = false, id: String = "ch$num") =
        ChapterEntity(
            id = id, mangaId = "m1", sourceId = "comick", url = "u$num",
            name = "Ch.$num", chapterNumber = num, dateUpload = 0L, read = read,
        )

    @Test
    fun `empty chapter list returns null`() {
        assertNull(pickContinueChapter(emptyList(), "ch1"))
    }

    @Test
    fun `no history starts at the first chapter`() {
        val chs = listOf(ch(3f), ch(1f), ch(2f))
        assertEquals(1f, pickContinueChapter(chs, null)?.chapterNumber)
    }

    @Test
    fun `finished last-read chapter continues at the next unread`() {
        // Audit: import oznacil <=131 jako prectene, lastRead=131 -> "Pokracovat" ma
        // jit na 132, ne znovu na 131.
        val chs = listOf(ch(130f, read = true), ch(131f, read = true), ch(132f), ch(133f))
        assertEquals(132f, pickContinueChapter(chs, "ch131.0")?.chapterNumber)
    }

    @Test
    fun `an in-progress chapter resumes on itself`() {
        // read=false + lastPageRead>0 = rozctena - resume na te same.
        val chs = listOf(ch(130f, read = true), ch(131f), ch(132f))
        assertEquals(131f, pickContinueChapter(chs, "ch131.0")?.chapterNumber)
    }

    @Test
    fun `finished series stays on the last chapter`() {
        // Vsechno precteno - zadna dalsi neni, ukaz posledni.
        val chs = listOf(ch(130f, read = true), ch(131f, read = true))
        assertEquals(131f, pickContinueChapter(chs, "ch131.0")?.chapterNumber)
    }

    @Test
    fun `unread gap before last-read does not pull continue backwards`() {
        // Uzivatel preskocil kapitolu (neprectena 120), pokracovat ma jit ZA posledni
        // prectenou, ne na diru v historii.
        val chs = listOf(ch(120f), ch(131f, read = true), ch(132f))
        assertEquals(132f, pickContinueChapter(chs, "ch131.0")?.chapterNumber)
    }

    @Test
    fun `missing last-read id falls back to the first chapter`() {
        // lastReadChapterId ukazuje na smazanou/nenactenou kapitolu.
        val chs = listOf(ch(1f), ch(2f))
        assertEquals(1f, pickContinueChapter(chs, "ghost")?.chapterNumber)
    }
}
