package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
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
) {
    fun observeLibrary(): Flow<List<MangaEntity>> = mangaDao.observeLibrary()

    fun observeChapters(mangaId: String): Flow<List<ChapterEntity>> = chapterDao.observeForManga(mangaId)

    suspend fun search(sourceId: String, query: String): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.search(query)
    }

    suspend fun getPopular(sourceId: String): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getPopular()
    }

    /** Přidá mangu do knihovny a rovnou stáhne aktuální seznam kapitol. */
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

    suspend fun removeFromLibrary(mangaId: String) {
        mangaDao.setInLibrary(mangaId, false)
    }

    suspend fun refreshChapters(mangaId: String, manga: SManga) {
        val source = sourceManager.getById(manga.sourceId) ?: return
        val chapters = source.getChapterList(manga)
        chapterDao.upsertAll(
            chapters.map { chapter ->
                ChapterEntity(
                    id = chapterId(chapter),
                    mangaId = mangaId,
                    sourceId = chapter.sourceId,
                    url = chapter.url,
                    name = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    dateUpload = chapter.dateUpload,
                )
            }
        )
    }

    suspend fun getChapterPages(sourceId: String, chapterUrl: String, mangaUrl: String) : List<com.haise.jiyu.source.Page> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        val chapter = SChapter(sourceId, mangaUrl, chapterUrl, "", 0f, 0L)
        return source.getPageList(chapter)
    }

    suspend fun setDownloadStatus(chapterEntityId: String, status: DownloadStatus) {
        chapterDao.setDownloadStatus(chapterEntityId, status)
    }

    suspend fun markDownloaded(chapterEntityId: String, localPath: String, pageCount: Int) {
        chapterDao.markDownloaded(chapterEntityId, DownloadStatus.DOWNLOADED, localPath, pageCount)
    }

    suspend fun updateReadProgress(chapterEntityId: String, read: Boolean, lastPageRead: Int) {
        chapterDao.updateProgress(chapterEntityId, read, lastPageRead)
    }

    suspend fun getChapter(chapterEntityId: String) = chapterDao.getById(chapterEntityId)

    suspend fun getManga(mangaId: String) = mangaDao.getById(mangaId)

    fun mangaId(sourceId: String, url: String) = "$sourceId::$url"
    fun chapterId(chapter: SChapter) = "${chapter.sourceId}::${chapter.url}"
}
