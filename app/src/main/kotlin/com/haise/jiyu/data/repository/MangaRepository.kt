package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.MangaDownloadedCount
import com.haise.jiyu.data.db.MangaTotalCount
import com.haise.jiyu.data.db.MangaUnreadCount
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaRepository @Inject constructor(
    private val sourceManager: SourceManager,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
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
    suspend fun countReadChapters(): Int = chapterDao.countRead()
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
    suspend fun upsertAllCategories(categories: List<com.haise.jiyu.data.db.entity.CategoryEntity>) = categoryDao.upsertAll(categories)

    // ── Browse / Search ──────────────────────────────────────────────────────

    suspend fun search(sourceId: String, query: String, page: Int = 1): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.search(query, page)
    }

    suspend fun getPopular(sourceId: String, page: Int = 1): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getPopular(page)
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
            )
        )
        refreshChapters(id, manga)
    }

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

    suspend fun updateLastReadChapter(mangaId: String, chapterId: String) {
        mangaDao.updateLastReadChapter(mangaId, chapterId)
        mangaDao.updateLastReadAt(mangaId, System.currentTimeMillis())
    }

    suspend fun getChapter(chapterEntityId: String) = chapterDao.getById(chapterEntityId)
    suspend fun getManga(mangaId: String) = mangaDao.getById(mangaId)

    // ── Categories ───────────────────────────────────────────────────────────

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()
    fun observeCategoryIdsForManga(mangaId: String) = categoryDao.observeCategoryIdsForManga(mangaId)
    suspend fun createCategory(category: CategoryEntity) = categoryDao.upsert(category)
    suspend fun deleteCategory(category: CategoryEntity) = categoryDao.delete(category)
    suspend fun addMangaToCategory(mangaId: String, categoryId: String) =
        categoryDao.addMangaToCategory(MangaCategoryEntity(mangaId, categoryId))
    suspend fun removeMangaFromCategory(mangaId: String, categoryId: String) =
        categoryDao.removeMangaFromCategory(mangaId, categoryId)

    // ── Utils ─────────────────────────────────────────────────────────────────

    fun mangaId(sourceId: String, url: String) = "$sourceId::$url"
    fun chapterId(chapter: SChapter) = "${chapter.sourceId}::${chapter.url}"
}
