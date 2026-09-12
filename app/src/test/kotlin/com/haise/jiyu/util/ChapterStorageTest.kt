package com.haise.jiyu.util

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Čistý JVM test [ChapterStorage.chapterFolderName] - žádná Android/Context závislost
 * (createChapterDir samo potřebuje DocumentFile, netestováno tady, stejná konvence jako
 * zbytek projektu - jen čistá logika bez Android API). `writePage`/`pageExists`/
 * `listPageUrls` jsou tu navíc otestované pro plain-`File` větev (nedotýká se Contextu
 * ani DocumentFile, jen `mockk` placeholder pro parametr) - viz torn-file test níže.
 *
 * Reprodukuje uživatelský dotaz "stáhnu celou mangu, pošlu na PC, přečtu si to tam" -
 * dřív se kapitola pojmenovávala "sourceId::URL kapitoly" (nečitelné a na Windows
 * doslova nefunkční jméno souboru kvůli ':'/'/' v URL).
 */
class ChapterStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `whole chapter number is zero-padded without decimal point`() {
        val name = ChapterStorage.chapterFolderName(12f, "Útok")
        assertEquals("0012 - Útok", name)
    }

    @Test
    fun `fractional chapter number keeps one decimal place`() {
        val name = ChapterStorage.chapterFolderName(10.5f, "Bonus")
        assertEquals("0010.5 - Bonus", name)
    }

    @Test
    fun `windows-forbidden characters in chapter name are replaced, not dropped`() {
        // Bez mezery navíc by se slova bez oddělovače kolem zakázaného znaku slepila.
        val name = ChapterStorage.chapterFolderName(1f, "Chapter: The Return/Home?")
        assertFalse("must not contain any Windows-forbidden filename character", name.any { it in "\\/:*?\"<>|" })
        assertTrue(name.contains("Chapter"))
        assertTrue(name.contains("Return"))
        assertTrue(name.contains("Home"))
    }

    @Test
    fun `blank chapter name falls back to just the number, no trailing separator`() {
        val name = ChapterStorage.chapterFolderName(3f, "   ")
        assertEquals("0003", name)
    }

    @Test
    fun `very long chapter name is truncated to a safe length`() {
        val longName = "a".repeat(500)
        val name = ChapterStorage.chapterFolderName(1f, longName)
        assertTrue("folder name must stay under a safe path-segment length", name.length < 150)
    }

    @Test
    fun `pageExists is false when only a leftover temp file from an interrupted write remains`() {
        val dir = tempFolder.root
        File(dir, "001.jpg.tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(ChapterStorage.pageExists(mockk(relaxed = true), dir.absolutePath, "001.jpg"))
    }

    @Test
    fun `writePage is atomic - pageExists is true only after the full write, no leftover temp file`() {
        val dir = tempFolder.root
        val context = mockk<android.content.Context>(relaxed = true)

        val written = ChapterStorage.writePage(context, dir.absolutePath, "001.jpg", byteArrayOf(1, 2, 3))

        assertTrue(written)
        assertTrue(ChapterStorage.pageExists(context, dir.absolutePath, "001.jpg"))
        assertFalse("no orphaned .tmp file should remain after a successful write", File(dir, "001.jpg.tmp").exists())
    }

    @Test
    fun `listPageUrls excludes a leftover temp file from an interrupted download`() {
        val dir = tempFolder.root
        File(dir, "001.jpg").writeBytes(byteArrayOf(1))
        File(dir, "002.jpg.tmp").writeBytes(byteArrayOf(2))

        val urls = ChapterStorage.listPageUrls(mockk(relaxed = true), dir.absolutePath)

        assertEquals(1, urls.size)
        assertTrue(urls[0].endsWith("001.jpg"))
    }
}
