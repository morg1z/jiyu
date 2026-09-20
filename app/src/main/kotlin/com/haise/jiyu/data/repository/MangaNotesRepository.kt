package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.MangaNoteDao
import com.haise.jiyu.data.db.MangaTagDao
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Poznámky a tagy k titulu - tenká vrstva nad [MangaNoteDao]/[MangaTagDao], aby k DAO nesahaly ViewModely přímo. */
@Singleton
class MangaNotesRepository @Inject constructor(
    private val noteDao: MangaNoteDao,
    private val tagDao: MangaTagDao,
) {
    fun observeNote(mangaId: String): Flow<MangaNoteEntity?> = noteDao.observeForManga(mangaId)

    /** Prázdný obsah poznámku smaže. */
    suspend fun saveNote(mangaId: String, content: String) {
        if (content.isBlank()) noteDao.deleteForManga(mangaId) else noteDao.upsert(MangaNoteEntity(mangaId = mangaId, content = content))
    }

    fun observeTags(mangaId: String): Flow<List<MangaTagEntity>> = tagDao.observeForManga(mangaId)
    suspend fun addTag(mangaId: String, tag: String) = tagDao.insert(MangaTagEntity(mangaId = mangaId, tag = tag))
    suspend fun removeTag(mangaId: String, tag: String) = tagDao.delete(MangaTagEntity(mangaId = mangaId, tag = tag))
}
