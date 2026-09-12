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
    fun `glossary entries of a deleted manga are cleaned up, not left as orphans`() = runTest {
        dao.upsert(manga("browsed"))
        db.glossaryDao().upsert(
            GlossaryEntity(id = "browsed::term::Czech", mangaId = "browsed", sourceTerm = "Term", targetTerm = "Pojem", targetLanguage = "Czech"),
        )
        db.deleteBrowsedManga()
        assertEquals(emptyList<GlossaryEntity>(), db.glossaryDao().getForMangaAndLanguage("browsed", "Czech"))
    }

    @Test
    fun `manual translations of a deleted manga's chapters are cleaned up, not left as orphans`() = runTest {
        dao.upsert(manga("browsed"))
        db.chapterDao().upsertAll(listOf(chapter("ch1", "browsed")))
        db.manualTranslationDao().upsert(
            ManualTranslationEntity(id = "ch1::0::hello", chapterId = "ch1", pageIndex = 0, originalText = "hello", text = "ahoj", updatedAt = 0L),
        )
        db.deleteBrowsedManga()
        assertEquals(emptyList<ManualTranslationEntity>(), db.manualTranslationDao().forPage("ch1", 0))
    }
}
