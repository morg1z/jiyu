package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.MangaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {

    @Upsert
    suspend fun upsert(manga: MangaEntity)

    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY title ASC")
    fun observeLibrary(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun getById(id: String): MangaEntity?

    @Query("UPDATE manga SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean)

    @Query("UPDATE manga SET lastReadChapterId = :chapterId WHERE id = :mangaId")
    suspend fun updateLastReadChapter(mangaId: String, chapterId: String)

    @Query("SELECT * FROM manga WHERE id = :id")
    fun observeById(id: String): Flow<MangaEntity?>

    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY title ASC")
    suspend fun getAllLibrary(): List<MangaEntity>

    @Upsert
    suspend fun upsertAll(manga: List<MangaEntity>)

    @Query("UPDATE manga SET lastReadAt = :time WHERE id = :mangaId")
    suspend fun updateLastReadAt(mangaId: String, time: Long)

    @Query("SELECT * FROM manga WHERE inLibrary = 1 AND lastReadAt > 0 ORDER BY lastReadAt DESC LIMIT 20")
    fun observeRecentlyRead(): Flow<List<MangaEntity>>

    @Query("UPDATE manga SET readerDirectionOverride = :direction WHERE id = :mangaId")
    suspend fun setReaderDirection(mangaId: String, direction: String?)

    @Query("UPDATE manga SET author = :author, artist = :artist, genres = :genres, year = :year WHERE id = :mangaId")
    suspend fun updateMetadata(mangaId: String, author: String?, artist: String?, genres: String, year: Int?)

    @Query("SELECT genres FROM manga WHERE inLibrary = 1 AND genres != ''")
    suspend fun getAllLibraryGenres(): List<String>

    @Query("SELECT author FROM manga WHERE inLibrary = 1 AND author IS NOT NULL AND author != ''")
    suspend fun getAllLibraryAuthors(): List<String?>

    @Query("UPDATE manga SET autoDownload = :enabled WHERE id = :id")
    suspend fun setAutoDownload(id: String, enabled: Boolean)

    @Query("UPDATE manga SET userRating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int?)
}
