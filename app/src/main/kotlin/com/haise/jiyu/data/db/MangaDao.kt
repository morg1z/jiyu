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

    /** `url` samo o sobě není napříč zdroji unikátní (relativní cesty typu "/manga/1" se
     * opakují), proto se vždy hledá spolu se `sourceId` (viz TachiyomiBackupImporter). */
    @Query("SELECT * FROM manga WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun getMangaBySourceAndUrl(sourceId: String, url: String): MangaEntity?

    @Query("UPDATE manga SET malId = :malId WHERE id = :id")
    suspend fun setMalId(id: String, malId: Int?)

    @Query("UPDATE manga SET malScore = :score WHERE id = :id")
    suspend fun setMalScore(id: String, score: Float?)

    @Query("UPDATE manga SET malStatus = :status WHERE id = :id")
    suspend fun setMalStatus(id: String, status: String?)

    @Query("UPDATE manga SET kitsuId = :kitsuId WHERE id = :id")
    suspend fun setKitsuId(id: String, kitsuId: String?)

    /** Volitelný kontext pro AI překladač (viz [com.haise.jiyu.data.db.entity.MangaEntity.translationContextNote]) - NULL/prázdné = žádný. */
    @Query("UPDATE manga SET translationContextNote = :note WHERE id = :id")
    suspend fun setTranslationContextNote(id: String, note: String?)

    @Query("UPDATE manga SET kitsuScore = :score WHERE id = :id")
    suspend fun setKitsuScore(id: String, score: Float?)

    @Query("UPDATE manga SET mangaUpdatesId = :seriesId WHERE id = :id")
    suspend fun setMangaUpdatesId(id: String, seriesId: Long?)

    @Query("UPDATE manga SET readingTimeMs = readingTimeMs + :deltaMs WHERE id = :id")
    suspend fun addReadingTime(id: String, deltaMs: Long)

    @Query("UPDATE manga SET lastReadChapterId = :chapterId, lastReadAt = :time WHERE id = :mangaId")
    suspend fun updateLastReadChapterAndTime(mangaId: String, chapterId: String, time: Long)

    /** Protějšek [ManualTranslationDao.relinkChapter] pro `manga.lastReadChapterId` - bez tohohle by
     * po [ChapterDao.relink] ukazoval na neexistující (staré) id kapitoly a "Pokračovat čtení" by
     * na widgetu/domovské obrazovce přestalo fungovat pro relinkovanou kapitolu. */
    @Query("UPDATE manga SET lastReadChapterId = :newChapterId WHERE lastReadChapterId = :oldChapterId")
    suspend fun relinkLastReadChapter(oldChapterId: String, newChapterId: String)

    /** Cílený zápis po opravě odkazu (viz MangaRepository.recoverMangaLink) - mění jen url/title/cover, nic dalšího. */
    @Query("UPDATE manga SET url = :url, title = :title, coverUrl = COALESCE(:coverUrl, coverUrl) WHERE id = :id")
    suspend fun relinkManga(id: String, url: String, title: String, coverUrl: String?)

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
     * není v žádné kategorii, nemá staženou ani právě stahovanou (QUEUED/DOWNLOADING) kapitolu
     * a nevlastní kapitolu, na kterou aktuálně
     * ukazuje nějaký fallbackChapterId (viz SourceResolverViewModel.resolveCompleteChapter -
     * preview-manga vytvořená přes openPreview při kontrole alternativních zdrojů má přesně
     * profil "jen prohlížené", ale je to naučená, trvale zapsaná náhrada za kapitolu s málo
     * stránkami - smazáním by se nenávratně ztratila, i když dangling redirect zpět na
     * originál appku nerozbije, jen naučenou kontrolu tiše zahodí).
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
          AND id NOT IN (
              SELECT DISTINCT mangaId FROM chapter
              WHERE localPath IS NOT NULL OR downloadStatus IN ('QUEUED', 'DOWNLOADING')
          )
          AND id NOT IN (
              SELECT DISTINCT mangaId FROM chapter
              WHERE id IN (SELECT fallbackChapterId FROM chapter WHERE fallbackChapterId IS NOT NULL)
          )
        """
    )
    suspend fun browsedMangaIds(): List<String>

    @Transaction
    suspend fun deleteChildrenOfManga(ids: List<String>) {
        // manual_translation/translated_page/translated_novel nemaji vlastni mangaId sloupec,
        // jen chapterId (translated_* dokonce jen jako predponu composite id) - VSECHNY MUSI
        // bezet PRED deleteChaptersOfManga, jinak uz poddotaz/JOIN proti tabulce chapter nic
        // nenajde (viz audit nalez "read_history/translated_* nikdy nemazane, sirotci po
        // smazani mangy" - puvodne to platilo uz jen pro manual_translation/glossary_entry).
        deleteManualTranslationsOfManga(ids)
        deleteTranslatedPagesOfManga(ids)
        deleteTranslatedNovelsOfManga(ids)
        deleteGlossaryOfManga(ids)
        deleteReadHistoryOfManga(ids)
        deleteChaptersOfManga(ids)
        deleteNotesOfManga(ids)
        deleteTagsOfManga(ids)
    }

    @Query("""
        DELETE FROM manual_translation
        WHERE chapterId IN (SELECT id FROM chapter WHERE mangaId IN (:ids))
    """)
    suspend fun deleteManualTranslationsOfManga(ids: List<String>)

    // translated_page/translated_novel nemaji chapterId sloupec, jen "$chapterId::..." jako
    // predponu primarniho klice id (viz TranslatedPageEntity/TranslatedNovelEntity) - proto
    // join pres chapter.id misto primeho IN poddotazu. Porovnani pres substr(...) = ..., ne
    // LIKE - chapterId je "$sourceId::$url" a URL bezne obsahuje '%'/'_', coz by LIKE vzalo
    // jako wildcard misto doslovneho znaku (viz TranslatedPageDao.relinkChapter).
    @Query("""
        DELETE FROM translated_page
        WHERE id IN (
            SELECT tp.id FROM translated_page tp
            INNER JOIN chapter c ON substr(tp.id, 1, length(c.id) + 2) = c.id || '::'
            WHERE c.mangaId IN (:ids)
        )
    """)
    suspend fun deleteTranslatedPagesOfManga(ids: List<String>)

    @Query("""
        DELETE FROM translated_novel
        WHERE id IN (
            SELECT tn.id FROM translated_novel tn
            INNER JOIN chapter c ON substr(tn.id, 1, length(c.id) + 2) = c.id || '::'
            WHERE c.mangaId IN (:ids)
        )
    """)
    suspend fun deleteTranslatedNovelsOfManga(ids: List<String>)

    @Query("DELETE FROM read_history WHERE mangaId IN (:ids)")
    suspend fun deleteReadHistoryOfManga(ids: List<String>)

    @Query("DELETE FROM glossary_entry WHERE mangaId IN (:ids)")
    suspend fun deleteGlossaryOfManga(ids: List<String>)

    @Query("DELETE FROM chapter WHERE mangaId IN (:ids)")
    suspend fun deleteChaptersOfManga(ids: List<String>)

    @Query("DELETE FROM manga_note WHERE mangaId IN (:ids)")
    suspend fun deleteNotesOfManga(ids: List<String>)

    @Query("DELETE FROM manga_tag WHERE mangaId IN (:ids)")
    suspend fun deleteTagsOfManga(ids: List<String>)

    @Query("DELETE FROM manga WHERE id IN (:ids)")
    suspend fun deleteMangaByIds(ids: List<String>)
}
