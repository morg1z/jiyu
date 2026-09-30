package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.db.entity.ManualTranslationEntity
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Testy úklidu jen prohlédnuté mangy (viz [deleteBrowsedManga]).
 *
 * Tohle je jediné místo v appce, které maže uživatelova data, takže se každá pojistka testuje
 * zvlášť. Chyba tady se neprojeví jako pád, ale jako tiše zmizelá manga - a to se pozná až
 * ve chvíli, kdy je pozdě.
 */
@RunWith(RobolectricTestRunner::class)
class BrowsedMangaCleanupTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MangaDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.mangaDao()
    }

    @After
    fun tearDown() = db.close()

    private fun manga(
        id: String,
        inLibrary: Boolean = false,
        isFavorite: Boolean = false,
        lastReadAt: Long = 0L,
    ) = MangaEntity(
        id = id, sourceId = "mangadex", url = "https://example.com/$id",
        title = "Test $id", coverUrl = null, description = null, status = null,
        inLibrary = inLibrary, isFavorite = isFavorite, lastReadAt = lastReadAt,
    )

    private fun chapter(id: String, mangaId: String, localPath: String? = null) = ChapterEntity(
        id = id, mangaId = mangaId, sourceId = "mangadex", url = "https://example.com/$id",
        name = "Kapitola", chapterNumber = 1f, dateUpload = 0L, localPath = localPath,
    )

    @Test
    fun `a merely browsed manga is deleted`() = runTest {
        dao.upsert(manga("browsed"))
        assertEquals(1, db.deleteBrowsedManga())
        assertNull(dao.getById("browsed"))
    }

    @Test
    fun `a manga in the library survives`() = runTest {
        dao.upsert(manga("lib", inLibrary = true))
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("lib"))
    }

    @Test
    fun `a favourite survives even outside the library`() = runTest {
        dao.upsert(manga("fav", isFavorite = true))
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("fav"))
    }

    @Test
    fun `a manga that was read survives even though it was never added`() = runTest {
        dao.upsert(manga("read", lastReadAt = 1_700_000_000_000L))
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("read"))
    }

    @Test
    fun `a manga with read history survives`() = runTest {
        dao.upsert(manga("hist"))
        db.chapterDao().upsertAll(listOf(chapter("ch1", "hist")))
        db.readHistoryDao().upsertAll(
            listOf(
                ReadHistoryEntity(
                    chapterId = "ch1", mangaId = "hist", mangaTitle = "Test hist",
                    coverUrl = null, chapterName = "Kapitola", readAt = 1L,
                )
            )
        )
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("hist"))
    }

    @Test
    fun `a manga with a downloaded chapter survives`() = runTest {
        // NEJZAKERNEJSI POJISTKA: smazani zaznamu by nechalo soubory lezet na disku a uz by
        // na ne nic neukazovalo - uzivatel by je nemel jak najit ani smazat.
        dao.upsert(manga("dl"))
        db.chapterDao().upsertAll(listOf(chapter("ch1", "dl", localPath = "/data/ch1")))
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("dl"))
    }

    @Test
    fun `a manga whose chapter is referenced as a fallback target survives`() = runTest {
        // SourceResolverViewModel.resolveCompleteChapter presmerovava kapitolu s podezrele
        // malo strankami na alternativu z jineho zdroje/mangy pres fallbackChapterId - ta
        // preview-manga ma jinak presne profil "jen prohlizene", ale jde o trvale zapsanou
        // naucenou nahradu, ne nahodny bordel (nahlaseny bug - jinak by ji uklid smazal).
        dao.upsert(manga("browsed"))
        dao.upsert(manga("fallback-target"))
        db.chapterDao().upsertAll(
            listOf(
                chapter("ch-original", "browsed").copy(fallbackChapterId = "ch-alt"),
                chapter("ch-alt", "fallback-target"),
            )
        )
        assertEquals(1, db.deleteBrowsedManga())
        assertNull(dao.getById("browsed"))
        assertNotNull(dao.getById("fallback-target"))
    }

    @Test
    fun `a manga sorted into a category survives`() = runTest {
        // Zarazeni do kategorie je vedome usporadani uzivatele - i kdyz mangu nema v knihovne
        // a necetl ji, rekl o ni "tahle patri sem" a to nesmi uklid smazat.
        dao.upsert(manga("sorted"))
        db.categoryDao().upsert(CategoryEntity(id = "cat1", name = "Ke čtení"))
        db.categoryDao().addMangaToCategory(
            MangaCategoryEntity(mangaId = "sorted", categoryId = "cat1")
        )
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("sorted"))
    }

    @Test
    fun `a manga with a user note survives`() = runTest {
        // Audit DB-1: uzivatel u preview-mangy napsal poznamku - je to user-authored data
        // a mangu tak musi uklid chranit, i kdyz neni v knihovne.
        dao.upsert(manga("noted"))
        db.mangaNoteDao().upsert(MangaNoteEntity(mangaId = "noted", content = "pokracovat od ch. 12"))
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("noted"))
        assertNotNull(db.mangaNoteDao().getAll().firstOrNull { it.mangaId == "noted" })
    }

    @Test
    fun `a manga with a user tag survives`() = runTest {
        dao.upsert(manga("tagged"))
        db.mangaTagDao().insert(MangaTagEntity(mangaId = "tagged", tag = "dark fantasy"))
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("tagged"))
    }

    @Test
    fun `a manga with a glossary entry survives`() = runTest {
        dao.upsert(manga("glossed"))
        db.glossaryDao().upsert(
            GlossaryEntity(id = "glossed::term::Czech", mangaId = "glossed", sourceTerm = "Term", targetTerm = "Pojem", targetLanguage = "Czech"),
        )
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("glossed"))
    }

    @Test
    fun `a manga whose chapter has a manual translation survives`() = runTest {
        dao.upsert(manga("handedited"))
        db.chapterDao().upsertAll(listOf(chapter("ch1", "handedited")))
        db.manualTranslationDao().upsert(
            ManualTranslationEntity(id = "ch1::0::Czech::hello", chapterId = "ch1", pageIndex = 0, targetLanguage = "Czech", originalText = "hello", text = "ahoj", updatedAt = 0L),
        )
        assertEquals(0, db.deleteBrowsedManga())
        assertNotNull(dao.getById("handedited"))
        assertNotNull(db.manualTranslationDao().forPage("ch1", 0, "Czech").firstOrNull())
    }

    @Test
    fun `a truly unreferenced browsed manga is still deleted`() = runTest {
        // Regrese DB-1: nove exclusion podminky nesmi udelat uklid mrtvym - manga bez
        // jakehokoliv zapisu se ma mazat dal.
        dao.upsert(manga("keep", inLibrary = true))
        dao.upsert(manga("junk1"))
        dao.upsert(manga("junk2"))
        assertEquals(2, db.deleteBrowsedManga())
        assertNull(dao.getById("junk1"))
        assertNull(dao.getById("junk2"))
        assertNotNull(dao.getById("keep"))
    }

    @Test
    fun `chapters of a deleted manga go with it`() = runTest {
        dao.upsert(manga("browsed"))
        db.chapterDao().upsertAll(listOf(chapter("ch1", "browsed"), chapter("ch2", "browsed")))
        db.deleteBrowsedManga()
        assertEquals(emptyList<ChapterEntity>(), db.chapterDao().getAllForManga("browsed"))
    }

    @Test
    fun `chapters of a surviving manga are left alone`() = runTest {
        dao.upsert(manga("browsed"))
        dao.upsert(manga("lib", inLibrary = true))
        db.chapterDao().upsertAll(listOf(chapter("a", "browsed"), chapter("b", "lib")))
        db.deleteBrowsedManga()
        assertEquals(1, db.chapterDao().getAllForManga("lib").size)
    }

    @Test
    fun `an empty database is a no-op`() = runTest {
        assertEquals(0, db.deleteBrowsedManga())
    }

    @Test
    fun `deleteChildrenOfManga still removes glossary entries when a manga is deleted directly`() = runTest {
        // DB-1: glossary uz chrani mangu proti browsed-uklidu, takze ji to nikdy nesmaze;
        // deleteChildrenOfManga je ale porad pojistka pro jine cesty mazani (neprincipialni
        // orphan cleanup) - overuje se primo.
        dao.upsert(manga("doomed", inLibrary = true))
        db.glossaryDao().upsert(
            GlossaryEntity(id = "doomed::term::Czech", mangaId = "doomed", sourceTerm = "Term", targetTerm = "Pojem", targetLanguage = "Czech"),
        )
        dao.deleteChildrenOfManga(listOf("doomed"))
        dao.deleteMangaByIds(listOf("doomed"))
        assertEquals(emptyList<GlossaryEntity>(), db.glossaryDao().getForMangaAndLanguage("doomed", "Czech"))
        assertNull(dao.getById("doomed"))
    }

    @Test
    fun `deleteChildrenOfManga still removes manual translations when a manga is deleted directly`() = runTest {
        dao.upsert(manga("doomed2", inLibrary = true))
        db.chapterDao().upsertAll(listOf(chapter("ch1", "doomed2")))
        db.manualTranslationDao().upsert(
            ManualTranslationEntity(id = "ch1::0::Czech::hello", chapterId = "ch1", pageIndex = 0, targetLanguage = "Czech", originalText = "hello", text = "ahoj", updatedAt = 0L),
        )
        dao.deleteChildrenOfManga(listOf("doomed2"))
        dao.deleteMangaByIds(listOf("doomed2"))
        assertEquals(emptyList<ManualTranslationEntity>(), db.manualTranslationDao().forPage("ch1", 0, "Czech"))
    }
}
