package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.data.db.MangaCategoryMapping
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.MangaDownloadedCount
import com.haise.jiyu.data.db.MangaTotalCount
import com.haise.jiyu.data.db.MangaUnreadCount
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.mangadex.MangaDexSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaRepository @Inject constructor(
    private val sourceManager: SourceManager,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val customSourceDao: CustomSourceDao,
    private val mangaDexSource: MangaDexSource,
) {
    // ── Library ──────────────────────────────────────────────────────────────

    fun observeLibrary(): Flow<List<MangaEntity>> = mangaDao.observeLibrary()
    fun observeLibraryInCategory(categoryId: String) = categoryDao.observeMangaInCategory(categoryId)
    fun observeMangaById(mangaId: String): Flow<MangaEntity?> = mangaDao.observeById(mangaId)
    suspend fun getAllLibraryManga(): List<MangaEntity> = mangaDao.getAllLibrary()
    fun observeRecentlyRead(): Flow<List<MangaEntity>> = mangaDao.observeRecentlyRead()

    // ── Chapters ─────────────────────────────────────────────────────────────

    fun observeChapters(mangaId: String): Flow<List<ChapterEntity>> = chapterDao.observeForManga(mangaId)
    suspend fun countChapters(mangaId: String): Int = chapterDao.countForManga(mangaId)
    suspend fun getAllChapters(mangaId: String): List<ChapterEntity> = chapterDao.getAllForManga(mangaId)
    suspend fun markAllChaptersRead(mangaIds: List<String>) = chapterDao.markAllReadForMangas(mangaIds)
    suspend fun resetActiveDownloads() = chapterDao.resetActiveDownloads()
    suspend fun countReadChapters(): Int = chapterDao.countRead()
    fun observeReadChaptersCount(): Flow<Int> = chapterDao.observeReadCount()
    suspend fun getAllLibraryChapters(): List<ChapterEntity> = chapterDao.getAllForLibrary()
    fun observeUnreadCounts(): Flow<List<MangaUnreadCount>> = chapterDao.observeUnreadCounts()
    fun observeTotalCounts(): Flow<List<MangaTotalCount>> = chapterDao.observeTotalCounts()
    fun observeDownloadedCountPerManga(): Flow<List<MangaDownloadedCount>> = chapterDao.observeDownloadedCountPerManga()
    fun observeNonEmptyDownloads(): Flow<List<ChapterEntity>> = chapterDao.observeNonEmptyDownloads()
    fun observeDownloadedCount(): Flow<Int> = chapterDao.observeDownloadedCount()
    suspend fun clearAllDownloaded() = chapterDao.clearAllDownloaded()
    suspend fun resetDownloadForChapter(chapterId: String) = chapterDao.resetDownloadForChapter(chapterId)
    suspend fun upsertAllManga(manga: List<com.haise.jiyu.data.db.entity.MangaEntity>) = mangaDao.upsertAll(manga)
    suspend fun upsertAllChapters(chapters: List<ChapterEntity>) = chapterDao.upsertAll(chapters)
    suspend fun getAllCategories(): List<com.haise.jiyu.data.db.entity.CategoryEntity> = categoryDao.getAllOnce()
    suspend fun getCategoryIdsForManga(mangaId: String): List<String> = categoryDao.getCategoryIdsForManga(mangaId)
    suspend fun getAllCategoryMappings(): List<MangaCategoryMapping> = categoryDao.getAllMappings()
    suspend fun upsertAllCategories(categories: List<com.haise.jiyu.data.db.entity.CategoryEntity>) = categoryDao.upsertAll(categories)

    // ── Browse / Search ──────────────────────────────────────────────────────

    suspend fun search(sourceId: String, query: String, page: Int = 1, filter: MangaFilter = MangaFilter()): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.search(query, page, filter)
    }

    suspend fun getPopular(sourceId: String, page: Int = 1, filter: MangaFilter = MangaFilter()): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getPopular(page, filter)
    }

    // ── Manga CRUD ───────────────────────────────────────────────────────────

    suspend fun addToLibrary(manga: SManga) {
        val id = mangaId(manga.sourceId, manga.url)
        mangaDao.upsert(
            MangaEntity(
                id = id,
                sourceId = manga.sourceId,
                url = manga.url,
                title = manga.title,
                coverUrl = manga.coverUrl,
                description = manga.description,
                status = manga.status,
                inLibrary = true,
                author = manga.author,
                artist = manga.artist,
                genres = manga.genres.joinToString(","),
                year = manga.year,
                contentType = manga.contentType,
                addedAt = System.currentTimeMillis(),
            )
        )
        refreshChapters(id, manga)
    }

    suspend fun setMangaReaderDirection(mangaId: String, direction: String?) =
        mangaDao.setReaderDirection(mangaId, direction)

    suspend fun setAutoDownload(mangaId: String, enabled: Boolean) =
        mangaDao.setAutoDownload(mangaId, enabled)

    suspend fun setRating(mangaId: String, rating: Int?) =
        mangaDao.setRating(mangaId, rating)

    suspend fun setExcludeFromUpdates(mangaId: String, exclude: Boolean) =
        mangaDao.setExcludeFromUpdates(mangaId, exclude)

    suspend fun getMangaByUrl(url: String): MangaEntity? = mangaDao.getMangaByUrl(url)
    suspend fun upsertManga(manga: MangaEntity) = mangaDao.upsert(manga)

    suspend fun setMalId(mangaId: String, malId: Int?) = mangaDao.setMalId(mangaId, malId)
    suspend fun setMalScore(mangaId: String, score: Float?) = mangaDao.setMalScore(mangaId, score)
    suspend fun setMalStatus(mangaId: String, status: String?) = mangaDao.setMalStatus(mangaId, status)

    suspend fun updateMangaMetadata(mangaId: String, manga: SManga) =
        mangaDao.updateMetadata(
            mangaId = mangaId,
            author = manga.author,
            artist = manga.artist,
            genres = manga.genres.joinToString(","),
            year = manga.year,
        )

    suspend fun removeFromLibrary(mangaId: String) = mangaDao.setInLibrary(mangaId, false)

    suspend fun refreshChapters(mangaId: String, manga: SManga) {
        val source = sourceManager.getById(manga.sourceId) ?: return
        val chapters = source.getChapterList(manga)
        chapterDao.insertNewOnly(
            chapters.map { chapter ->
                ChapterEntity(
                    id = chapterId(chapter),
                    mangaId = mangaId,
                    sourceId = chapter.sourceId,
                    url = chapter.url,
                    name = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    dateUpload = chapter.dateUpload,
                    scanlationGroup = chapter.scanlationGroup,
                    volume = chapter.volume,
                )
            }
        )
    }

    suspend fun getChapterPages(sourceId: String, chapterUrl: String, mangaUrl: String): List<com.haise.jiyu.source.Page> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        val chapter = SChapter(sourceId, mangaUrl, chapterUrl, "", 0f, 0L)
        return source.getPageList(chapter)
    }

    suspend fun setDownloadStatus(chapterEntityId: String, status: DownloadStatus) =
        chapterDao.setDownloadStatus(chapterEntityId, status)

    suspend fun markDownloaded(chapterEntityId: String, localPath: String, pageCount: Int) =
        chapterDao.markDownloaded(chapterEntityId, DownloadStatus.DOWNLOADED, localPath, pageCount)

    suspend fun updateReadProgress(chapterEntityId: String, read: Boolean, lastPageRead: Int) =
        chapterDao.updateProgress(chapterEntityId, read, lastPageRead)

    suspend fun updateLastReadChapter(mangaId: String, chapterId: String) =
        mangaDao.updateLastReadChapterAndTime(mangaId, chapterId, System.currentTimeMillis())

    suspend fun getChapter(chapterEntityId: String) = chapterDao.getById(chapterEntityId)
    suspend fun getManga(mangaId: String) = mangaDao.getById(mangaId)

    // ── Categories ───────────────────────────────────────────────────────────

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()
    fun observeCategoryIdsForManga(mangaId: String) = categoryDao.observeCategoryIdsForManga(mangaId)
    suspend fun createCategory(category: CategoryEntity) = categoryDao.upsert(category)
    suspend fun deleteCategory(category: CategoryEntity) = categoryDao.delete(category)
    suspend fun addMangaToCategory(mangaId: String, categoryId: String) =
        categoryDao.addMangaToCategory(MangaCategoryEntity(mangaId, categoryId))
    suspend fun upsertAllMangaCategories(pairs: List<Pair<String, String>>) =
        categoryDao.addAllMangaToCategories(pairs.map { (mId, cId) -> MangaCategoryEntity(mId, cId) })
    suspend fun removeMangaFromCategory(mangaId: String, categoryId: String) =
        categoryDao.removeMangaFromCategory(mangaId, categoryId)

    // ── Vlastní zdroje (Madara) ──────────────────────────────────────────────

    fun observeCustomSources(): Flow<List<CustomSourceEntity>> = customSourceDao.observeAll()
    suspend fun addCustomSource(
        name: String,
        baseUrl: String,
        listItemSelector: String? = null,
        titleLinkSelector: String? = null,
        descriptionSelector: String? = null,
        statusSelector: String? = null,
        chapterListSelector: String? = null,
        pageImageSelector: String? = null,
        contentType: String = "MANGA",
    ) = customSourceDao.upsert(
        CustomSourceEntity(
            name = name,
            baseUrl = baseUrl,
            listItemSelector = listItemSelector,
            titleLinkSelector = titleLinkSelector,
            descriptionSelector = descriptionSelector,
            statusSelector = statusSelector,
            chapterListSelector = chapterListSelector,
            pageImageSelector = pageImageSelector,
            contentType = contentType,
        )
    )
    suspend fun deleteCustomSource(source: CustomSourceEntity) = customSourceDao.delete(source)
    suspend fun getAllCustomSourcesOnce(): List<CustomSourceEntity> = customSourceDao.getAllOnce()
    /** Zachová původní id (na rozdíl od addCustomSource) - potřeba pro obnovu zálohy, kde na id ukazují sourceId manga. */
    suspend fun upsertAllCustomSources(sources: List<CustomSourceEntity>) = customSourceDao.upsertAll(sources)

    // ── Related manga (MangaDex) ──────────────────────────────────────────────

    suspend fun getRelatedManga(mangaId: String): List<SManga> {
        val mdMangaId = mangaId.substringAfterLast("/")
        return mangaDexSource.getRelatedManga(mdMangaId)
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    fun mangaId(sourceId: String, url: String) = "$sourceId::$url"
    fun chapterId(chapter: SChapter) = "${chapter.sourceId}::${chapter.url}"
}
