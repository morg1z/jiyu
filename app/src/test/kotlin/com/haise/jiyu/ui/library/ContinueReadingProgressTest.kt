package com.haise.jiyu.ui.library

import com.haise.jiyu.data.db.ContinueReadingItem
import com.haise.jiyu.data.db.entity.MangaEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * % na kartách "Pokračovat ve čtení" - regresní testy pro nahlášený bug "jedna
 * přečtená stránka = 100 %": badge má ukazovat průběh v POSLEDNÍ rozečtené kapitole,
 * ne podíl přečtených kapitol celé série (dřívější sémantika).
 */
class ContinueReadingProgressTest {

    private fun item(
        read: Boolean? = false,
        lastPageRead: Int? = null,
        pageCount: Int? = null,
        mangaId: String = "m1",
    ) = ContinueReadingItem(
        manga = MangaEntity(
            id = mangaId, sourceId = "s", url = "u", title = "T",
            coverUrl = null, description = null, status = null,
        ),
        lastChapterName = "Ch 12",
        lastChapterNumber = 12f,
        lastChapterRead = read,
        lastPageRead = lastPageRead,
        lastPageCount = pageCount,
    )

    @Test
    fun `fully read chapter shows 100 percent`() {
        assertEquals(100, progressPercentFor(item(read = true), emptyMap(), emptyMap()))
    }

    @Test
    fun `one page into a 30-page chapter shows a few percent, not 100`() {
        assertEquals(3, progressPercentFor(item(read = false, lastPageRead = 0, pageCount = 30), emptyMap(), emptyMap()))
    }

    @Test
    fun `half-read chapter shows its real progress`() {
        // 0-based index 14 ze 30 = přečteno 15 stránek = 50 %.
        assertEquals(50, progressPercentFor(item(read = false, lastPageRead = 14, pageCount = 30), emptyMap(), emptyMap()))
    }

    @Test
    fun `in-progress chapter never reports 100 percent even at the last index`() {
        assertEquals(99, progressPercentFor(item(read = false, lastPageRead = 29, pageCount = 30), emptyMap(), emptyMap()))
    }

    @Test
    fun `unknown page count falls back to fraction of read chapters`() {
        // Staré záznamy bez verifiedPageCount/pageCount - zachováno dřívější chování.
        val unread = mapOf("m1" to 1)
        val total = mapOf("m1" to 12)
        assertEquals(91, progressPercentFor(item(read = false, lastPageRead = 5, pageCount = null), unread, total))
    }

    @Test
    fun `no chapter data and no counts shows zero`() {
        assertEquals(0, progressPercentFor(item(), emptyMap(), emptyMap()))
    }
}
