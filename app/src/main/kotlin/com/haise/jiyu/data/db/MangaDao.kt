package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.MangaEntity
import kotlinx.coroutines.flow.Flow

data class ContinueReadingItem(
    @Embedded val manga: MangaEntity,
    val lastChapterName: String?,
    val lastChapterNumber: Float?,
)

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

    @Query("""
        SELECT m.*, c.name as lastChapterName, c.chapterNumber as lastChapterNumber
        FROM manga m
        LEFT JOIN chapter c ON c.id = m.lastReadChapterId
        WHERE m.inLibrary = 1 AND m.lastReadAt > 0
        ORDER BY m.lastReadAt DESC LIMIT 20
    """)
    fun observeContinueReading(): Flow<List<ContinueReadingItem>>

    /** Jednorázový dotaz na jeden titul se stejným tvarem jako [observeContinueReading] -
     * pro widget s obálkou (CoverWidget), kde je titul předem vybraný v konfiguraci. */
    @Query("""
        SELECT m.*, c.name as lastChapterName, c.chapterNumber as lastChapterNumber
        FROM manga m
        LEFT JOIN chapter c ON c.id = m.lastReadChapterId
        WHERE m.id = :mangaId
    """)
    suspend fun getContinueReadingForManga(mangaId: String): ContinueReadingItem?

    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY addedAt DESC LIMIT 20")
    fun observeRecentlyAdded(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE inLibrary = 1 AND readingStatus = 'COMPLETED' ORDER BY lastUpdated DESC LIMIT 20")
    fun observeCompleted(): Flow<List<MangaEntity>>

    @Query("UPDATE manga SET readerDirectionOverride = :direction WHERE id = :mangaId")
    suspend fun setReaderDirection(mangaId: String, direction: String?)

    @Query("UPDATE manga SET author = :author, artist = :artist, genres = :genres, year = :year WHERE id = :mangaId")
    suspend fun updateMetadata(mangaId: String, author: String?, artist: String?, genres: String, year: Int?)

    @Query("SELECT genres FROM manga WHERE inLibrary = 1 AND genres != ''")
    suspend fun getAllLibraryGenres(): List<String>

    @Query("SELECT author FROM manga WHERE inLibrary = 1 AND author IS NOT NULL AND author != ''")
    // Ne List<String?> - dotaz sám filtruje `author IS NOT NULL`, takže Room do výsledku null
    // nikdy nedá a nullable typ jen nutil volajícího psát větev, která nemůže nastat.
    suspend fun getAllLibraryAuthors(): List<String>

    @Query("UPDATE manga SET autoDownload = :enabled WHERE id = :id")
    suspend fun setAutoDownload(id: String, enabled: Boolean)

    @Query("UPDATE manga SET userRating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int?)

    @Query("UPDATE manga SET excludeFromUpdates = :exclude WHERE id = :id")
    suspend fun setExcludeFromUpdates(id: String, exclude: Boolean)

    @Query("UPDATE manga SET contentType = :contentType WHERE id = :id")
    suspend fun setContentType(id: String, contentType: String)

    @Query("SELECT * FROM manga WHERE url = :url LIMIT 1")
    suspend fun getMangaByUrl(url: String): MangaEntity?

    @Query("UPDATE manga SET malId = :malId WHERE id = :id")
    suspend fun setMalId(id: String, malId: Int?)

    @Query("UPDATE manga SET malScore = :score WHERE id = :id")
    suspend fun setMalScore(id: String, score: Float?)

    @Query("UPDATE manga SET malStatus = :status WHERE id = :id")
    suspend fun setMalStatus(id: String, status: String?)

    @Query("UPDATE manga SET kitsuId = :kitsuId WHERE id = :id")
    suspend fun setKitsuId(id: String, kitsuId: String?)

    @Query("UPDATE manga SET kitsuScore = :score WHERE id = :id")
    suspend fun setKitsuScore(id: String, score: Float?)

    @Query("UPDATE manga SET mangaUpdatesId = :seriesId WHERE id = :id")
    suspend fun setMangaUpdatesId(id: String, seriesId: Long?)

    @Query("UPDATE manga SET readingTimeMs = readingTimeMs + :deltaMs WHERE id = :id")
    suspend fun addReadingTime(id: String, deltaMs: Long)

    @Query("UPDATE manga SET lastReadChapterId = :chapterId, lastReadAt = :time WHERE id = :mangaId")
    suspend fun updateLastReadChapterAndTime(mangaId: String, chapterId: String, time: Long)

    // Doplnek ChapterDao.resetProgressForManga - manga radek se pri odebrani z knihovny
    // take nemaze, takze "Pokracovat X" a cas cteni by jinak po znovu-pridani ukazovaly
    // stary stav z doby pred odebranim.
    @Query("UPDATE manga SET lastReadChapterId = NULL, lastReadAt = 0, readingTimeMs = 0 WHERE id = :id")
    suspend fun resetReadProgress(id: String)

    @Query("UPDATE manga SET readingStatus = :status WHERE id = :id")
    suspend fun setReadingStatus(id: String, status: String?)

    @Query("SELECT * FROM manga WHERE inLibrary = 1 AND readingStatus = :status ORDER BY title ASC")
    fun observeByReadingStatus(status: String): Flow<List<MangaEntity>>

    @Query("UPDATE manga SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("SELECT COUNT(*) FROM manga WHERE inLibrary = 1 AND isFavorite = 1")
    fun observeFavoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM manga WHERE inLibrary = 1")
    fun observeLibraryCount(): Flow<Int>

    // ── Úklid jen prohlížené mangy - viz [deleteBrowsedManga] ──────────────────────────
    /**
     * ID mangy, kterou lze bezpečně smazat: není v knihovně, není oblíbená, nikdy se nečetla,
     * není v žádné kategorii a nemá staženou kapitolu.
     *
     * Každá podmínka brání jiné ztrátě; nejádnou z nich nevyhazuj bez náhrady. Stažená kapitola
     * je z nich nejzákeřnější - smazáním záznamu by soubory zůstaly ležet na disku a už by na
     * ně nic neukazovalo.
     */
    @Query(
        """
        SELECT id FROM manga
        WHERE inLibrary = 0
          AND isFavorite = 0
          AND lastReadAt = 0
          AND id NOT IN (SELECT DISTINCT mangaId FROM read_history)
          AND id NOT IN (SELECT DISTINCT mangaId FROM manga_category)
          AND id NOT IN (SELECT DISTINCT mangaId FROM chapter WHERE localPath IS NOT NULL)
        """
    )
    suspend fun browsedMangaIds(): List<String>

    @Transaction
    suspend fun deleteChildrenOfManga(ids: List<String>) {
        deleteChaptersOfManga(ids)
        deleteNotesOfManga(ids)
        deleteTagsOfManga(ids)
    }

    @Query("DELETE FROM chapter WHERE mangaId IN (:ids)")
    suspend fun deleteChaptersOfManga(ids: List<String>)

    @Query("DELETE FROM manga_note WHERE mangaId IN (:ids)")
    suspend fun deleteNotesOfManga(ids: List<String>)

    @Query("DELETE FROM manga_tag WHERE mangaId IN (:ids)")
    suspend fun deleteTagsOfManga(ids: List<String>)

    @Query("DELETE FROM manga WHERE id IN (:ids)")
    suspend fun deleteMangaByIds(ids: List<String>)
}
