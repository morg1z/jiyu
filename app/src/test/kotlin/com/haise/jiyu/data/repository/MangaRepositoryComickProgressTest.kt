package com.haise.jiyu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.PageGapFiller
import com.haise.jiyu.source.SourceManager
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pokrývá [MangaRepository.propagateReadProgressToLinkedChapters] - postup z reálné
 * (resolved) kapitoly na ComicK katalogový titul. ComicK ukládá jednu logickou kapitolu
 * na více řádků (skupina/jazyk); `manga.lastReadChapterId` musí mířit na EN-otevřitelný
 * řádek, jinak ho EN filtr v `continueChapter` skryje a "Pokračovat" spadne přes
 * fallback `minByOrNull` na kapitolu 1 (hlášený bug "četl jsem do 3, ukazuje 1").
 */
@RunWith(RobolectricTestRunner::class)
class MangaRepositoryComickProgressTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MangaRepository

    private val resolvedMangaId = "src::/manga"
    private val comickMangaId = "comick::/manga"

    private fun comickChapter(number: Float, hid: String, lang: String?) = ChapterEntity(
        id = "comick::https://api.comick.dev/chapter/$hid",
        mangaId = comickMangaId,
        sourceId = "comick",
        url = "https://api.comick.dev/chapter/$hid",
        name = "Ch $number",
        chapterNumber = number,
        dateUpload = 0L,
        language = lang,
    )

    private fun resolvedChapter(number: Float) = ChapterEntity(
        id = "src::/manga/ch$number",
        mangaId = resolvedMangaId,
        sourceId = "src",
        url = "/manga/ch$number",
        name = "Ch $number",
        chapterNumber = number,
        dateUpload = 0L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MangaRepository(
            sourceManager = mockk(),
            mangaDao = spyk(db.mangaDao()),
            chapterDao = db.chapterDao(),
            categoryDao = db.categoryDao(),
            customSourceDao = db.customSourceDao(),
            mangaDexSource = mockk(relaxed = true),
            mangaUpdatesRepository = mockk(relaxed = true),
            manualTranslationDao = db.manualTranslationDao(),
            readHistoryDao = db.readHistoryDao(),
            translatedPageDao = db.translatedPageDao(),
            translatedNovelDao = db.translatedNovelDao(),
            settings = mockk(relaxed = true),
            db = db,
            context = context,
            contentCache = SourceContentCache(),
            pageGapFiller = PageGapFiller(
                crossSourceSearch = mockk(relaxed = true),
                mangaDao = db.mangaDao(),
                chapterDao = db.chapterDao(),
                translatedPageDao = db.translatedPageDao(),
                context = context,
                httpClient = OkHttpClient(),
            ),
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seed(resolvedLink: Boolean = true, enFirst: Boolean = false): Pair<ChapterEntity, ChapterEntity> {
        db.mangaDao().upsert(
            MangaEntity(
                id = resolvedMangaId, sourceId = "src", url = "/manga", title = "T",
                description = null, status = null, inLibrary = true, contentType = "MANGA",
                coverUrl = null,
            )
        )
        db.mangaDao().upsert(
            MangaEntity(
                id = comickMangaId, sourceId = "comick", url = "/manga", title = "T",
                description = null, status = null, inLibrary = true, contentType = "MANGA",
                coverUrl = null,
                comickHid = "manga-hid",
                resolvedMangaId = if (resolvedLink) resolvedMangaId else null,
            )
        )
        val vn = comickChapter(3f, hid = "vn3", lang = "vi")
        val en = comickChapter(3f, hid = "en3", lang = "en")
        // VN před EN - findByMangaAndNumber není řazený; staré parents.first()
        // by za takového pořadí chytilo ne-EN řádek.
        db.chapterDao().upsertAll(if (enFirst) listOf(en, vn) else listOf(vn, en))
        return vn to en
    }

    @Test
    fun `lastReadChapterId míří na EN variantu kapitoly, ne na libovolný řádek`() = runTest {
        val (vn, en) = seed()
        val resolved = resolvedChapter(3f)
        db.chapterDao().upsertAll(listOf(resolved))

        repository.propagateReadProgressToLinkedChapters(
            resolved.id, read = true, lastPageRead = 20, lastReadAt = 1000L, pageCount = 24,
        )

        val manga = db.mangaDao().getById(comickMangaId)!!
        assertEquals(en.id, manga.lastReadChapterId)
        // Postup se propagoval na obě jazykové varianty čísla
        assertTrue(db.chapterDao().getById(vn.id)!!.read)
        assertTrue(db.chapterDao().getById(en.id)!!.read)
        // Oba rodiče odkazují na resolved kapitolu
        assertEquals(resolved.id, db.chapterDao().getById(vn.id)!!.fallbackChapterId)
    }

    @Test
    fun `remote push target používá hid EN varianty`() = runTest {
        seed()
        val resolved = resolvedChapter(3f)
        db.chapterDao().upsertAll(listOf(resolved))

        val targets = repository.propagateReadProgressToLinkedChapters(
            resolved.id, read = true, lastPageRead = 20, lastReadAt = 1000L, pageCount = 24,
        )

        val target = targets.single { it.mangaHid == "manga-hid" }
        assertEquals("en3", target.chapterHid)
    }

    @Test
    fun `lastReadChapterId se zapíše i bez resolvedMangaId vazby (jen fallback link)`() = runTest {
        // ComicK titul bez zapamatovaného resolved zdroje - kapitola dostala fallback
        // link při ručním sweepu. Dřív tudy lastReadChapterId nikdy neprošlo a
        // "Pokračovat" zůstávalo na staré kapitole navždy.
        val (_, en) = seed(resolvedLink = false)
        val resolved = resolvedChapter(3f)
        db.chapterDao().upsertAll(listOf(resolved))
        db.chapterDao().setFallbackTarget(en.id, resolved.id)

        repository.propagateReadProgressToLinkedChapters(
            resolved.id, read = true, lastPageRead = 20, lastReadAt = 1000L, pageCount = 24,
        )

        assertEquals(en.id, db.mangaDao().getById(comickMangaId)!!.lastReadChapterId)
        assertTrue(db.chapterDao().getById(en.id)!!.read)
    }
}
