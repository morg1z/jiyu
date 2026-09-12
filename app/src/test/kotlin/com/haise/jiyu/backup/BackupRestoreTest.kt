package com.haise.jiyu.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Testy obnovy ze zálohy.
 *
 * Proč zrovna tohle: `restoreFromJson` prováděl sedm samostatných zápisů za sebou. Když
 * pátý spadl na poškozeném JSONu, kategorie a manga už v databázi byly, kapitoly ne -
 * `runCatching` sice nahlásilo neúspěch, ale knihovna zůstala v rozečteném stavu, o kterém
 * uživatel nic nevěděl. Zálohu přitom lidi obnovují typicky ve chvíli, kdy o data přijít
 * nechtějí.
 *
 * Testy jedou nad skutečnou Room databází v paměti, protože jinak by se zrušení
 * transakce nedalo pozorovat - mock by zápis jen zahodil a rollback by nebylo na čem
 * ukázat. `MangaRepository` zamockovaný je: jeho část zápisů tady není podstatná,
 * podstatné je, že DAO zápisy před chybou v databázi NEZŮSTANOU.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MangaRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = mockk(relaxed = true)
        manager = BackupManager(
            context = context,
            repository = repository,
            mangaNoteDao = db.mangaNoteDao(),
            mangaTagDao = db.mangaTagDao(),
            readHistoryDao = db.readHistoryDao(),
            db = db,
        )
    }

    @After
    fun tearDown() = db.close()

    /** Poznámky jsou v pořádku, ale historie má záznam bez `chapterId` -> parsování spadne. */
    private fun backupWithBrokenHistory() = """
        {
          "version": 3,
          "categories": [],
          "customSources": [],
          "manga": [],
          "chapters": [],
          "notes": [ { "mangaId": "m1", "content": "moje poznamka", "updatedAt": 1 } ],
          "tags": [],
          "readHistory": [ { "mangaId": "m1", "mangaTitle": "T", "chapterName": "c", "readAt": 1 } ]
        }
    """.trimIndent()

    private fun validBackup() = """
        {
          "version": 3,
          "categories": [],
          "customSources": [],
          "manga": [],
          "chapters": [],
          "notes": [ { "mangaId": "m1", "content": "moje poznamka", "updatedAt": 1 } ],
          "tags": [],
          "readHistory": []
        }
    """.trimIndent()

    @Test
    fun `a valid backup writes its contents through`() = runTest {
        val result = manager.importFromJson(validBackup())

        assertTrue("platná záloha se má obnovit", result.isSuccess)
        assertEquals(1, db.mangaNoteDao().getAll().size)
    }

    @Test
    fun `a backup that breaks half way through leaves nothing behind`() = runTest {
        // JÁDRO NÁLEZU: poznámky se zapisovaly PŘED historií, takže bez transakce
        // zůstaly v databázi i poté, co obnova skončila chybou.
        val result = manager.importFromJson(backupWithBrokenHistory())

        assertTrue("poškozená záloha musí skončit chybou", result.isFailure)
        assertEquals(
            "po neúspěšné obnově nesmí v databázi zůstat nic rozepsaného",
            0, db.mangaNoteDao().getAll().size,
        )
    }

    @Test
    fun `a backup from an unknown future format is refused before anything is written`() = runTest {
        // Export si verzi zapisoval, ale import ji nikdy nečetl - novější formát by se
        // tedy naparsoval jako ten současný a tiše by nadělal nesmysly.
        val future = validBackup().replace("\"version\": 3", "\"version\": 99")

        val result = manager.importFromJson(future)

        assertTrue("neznámý formát se má odmítnout", result.isFailure)
        assertEquals(0, db.mangaNoteDao().getAll().size)
        io.mockk.coVerify(exactly = 0) { repository.upsertAllManga(any()) }
    }

    @Test
    fun `a backup with no version at all is still accepted as the oldest format`() = runTest {
        // Nejstarší zálohy pole "version" nemají vůbec. Odmítnout je by uživatele připravilo
        // o data, která jinak jdou obnovit bez problémů.
        val legacy = validBackup().replace("\"version\": 3,", "")

        val result = manager.importFromJson(legacy)

        assertTrue("stará záloha bez verze musí projít", result.isSuccess)
        assertEquals(1, db.mangaNoteDao().getAll().size)
    }

    @Test
    fun `a chapter marked DOWNLOADED with a localPath that no longer exists is downgraded on restore`() = runTest {
        // Zaloha z jineho telefonu (nebo po reinstalu) klidne rekne "downloaded" s cestou,
        // ktera na TOMHLE zarizeni nikdy neexistovala - appka by jinak navzdy ukazovala
        // "stazeno" u kapitoly, ktera se ve skutecnosti musi stahnout znovu.
        val backup = """
            {
              "version": 4,
              "categories": [], "customSources": [], "manga": [],
              "chapters": [ {
                "id": "c1", "mangaId": "m1", "sourceId": "src", "url": "/c1", "name": "Ch 1",
                "chapterNumber": 1.0, "dateUpload": 0, "read": false, "lastPageRead": 0,
                "downloadStatus": "DOWNLOADED", "localPath": "/nonexistent/path/that/has/no/pages",
                "pageCount": 20
              } ],
              "notes": [], "tags": [], "readHistory": []
            }
        """.trimIndent()
        val slot = slot<List<ChapterEntity>>()
        coEvery { repository.upsertAllChapters(capture(slot)) } returns Unit

        val result = manager.importFromJson(backup)

        assertTrue(result.isSuccess)
        val restored = slot.captured.single()
        assertEquals(DownloadStatus.NOT_DOWNLOADED, restored.downloadStatus)
        assertEquals(null, restored.localPath)
        assertEquals(0, restored.pageCount)
    }
}
