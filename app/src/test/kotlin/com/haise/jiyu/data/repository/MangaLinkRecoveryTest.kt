package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaLinkRecoveryTest {

    private fun sManga(title: String, url: String = "https://example.com/$title") =
        SManga(sourceId = "test", url = url, title = title, coverUrl = null)

    // ── findBestTitleMatch ──────────────────────────────────────────────────

    @Test
    fun `exact case-insensitive match among multiple candidates wins`() {
        val candidates = listOf(sManga("Other Title"), sManga("Solo Leveling"), sManga("Another"))
        val result = findBestTitleMatch(candidates, "solo leveling")
        assertEquals("Solo Leveling", result?.title)
    }

    @Test
    fun `no exact match but exactly one candidate is used`() {
        val candidates = listOf(sManga("Solo Leveling: Ragnarok"))
        val result = findBestTitleMatch(candidates, "Solo Leveling")
        assertEquals("Solo Leveling: Ragnarok", result?.title)
    }

    @Test
    fun `no exact match and multiple candidates gives up`() {
        val candidates = listOf(sManga("Solo Leveling: Ragnarok"), sManga("Solo Leveling Side Story"))
        assertNull(findBestTitleMatch(candidates, "Solo Leveling"))
    }

    @Test
    fun `two exact matches is ambiguous and gives up`() {
        val candidates = listOf(sManga("Solo Leveling", "https://a.com"), sManga("Solo Leveling", "https://b.com"))
        assertNull(findBestTitleMatch(candidates, "Solo Leveling"))
    }

    @Test
    fun `empty candidate list gives up`() {
        assertNull(findBestTitleMatch(emptyList(), "Solo Leveling"))
    }

    // ── planChapterMigration ────────────────────────────────────────────────

    private fun oldChapter(id: String, number: Float) = ChapterEntity(
        id = id, mangaId = "manga-1", sourceId = "test", url = "https://old.com/$id",
        name = "Ch $number", chapterNumber = number, dateUpload = 0L,
    )

    private fun newChapter(url: String, number: Float) = SChapter(
        sourceId = "test", mangaUrl = "https://new.com/manga", url = url,
        name = "Ch $number", chapterNumber = number, dateUpload = 100L,
    )

    @Test
    fun `matching chapter numbers are planned for relink`() {
        val old = listOf(oldChapter("old-1", 1f), oldChapter("old-2", 2f))
        val new = listOf(newChapter("https://new.com/1", 1f), newChapter("https://new.com/2", 2f))

        val plan = planChapterMigration(old, new)

        assertEquals(2, plan.relink.size)
        assertEquals("old-1", plan.relink[0].first.id)
        assertEquals("https://new.com/1", plan.relink[0].second.url)
        assertEquals(0, plan.newOnly.size)
    }

    @Test
    fun `new chapter number with no old match is newOnly`() {
        val old = listOf(oldChapter("old-1", 1f))
        val new = listOf(newChapter("https://new.com/1", 1f), newChapter("https://new.com/2", 2f))

        val plan = planChapterMigration(old, new)

        assertEquals(1, plan.relink.size)
        assertEquals(1, plan.newOnly.size)
        assertEquals(2f, plan.newOnly[0].chapterNumber)
    }

    @Test
    fun `old chapter number with no new match is left out of the plan`() {
        val old = listOf(oldChapter("old-1", 1f), oldChapter("old-2", 2f))
        val new = listOf(newChapter("https://new.com/1", 1f))

        val plan = planChapterMigration(old, new)

        assertEquals(1, plan.relink.size)
        assertEquals("old-1", plan.relink[0].first.id)
    }

    @Test
    fun `duplicate chapter number in newChapters only uses the first occurrence`() {
        val old = listOf(oldChapter("old-1", 1f))
        val new = listOf(newChapter("https://new.com/1a", 1f), newChapter("https://new.com/1b", 1f))

        val plan = planChapterMigration(old, new)

        assertEquals(1, plan.relink.size)
        assertEquals("https://new.com/1a", plan.relink[0].second.url)
        assertEquals(0, plan.newOnly.size)
    }
}
