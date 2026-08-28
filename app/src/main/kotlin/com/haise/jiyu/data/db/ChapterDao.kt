package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow

data class MangaUnreadCount(val mangaId: String, val count: Int)
data class MangaTotalCount(val mangaId: String, val count: Int)
data class MangaDownloadedCount(val mangaId: String, val count: Int)

data class UpdateItem(
    val chapterId: String,
    val chapterName: String,
    val chapterNumber: Float,
    val dateUpload: Long,
    val mangaId: String,
    val mangaTitle: String,
    val coverUrl: String?,
    val sourceId: String,
    val read: Boolean,
)

@Dao
interface ChapterDao {

    /** Full upsert — používej POUZE pro import zálohy kde chceme obnovit i read/download stav. */
    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    /** Vloží jen nové kapitoly; existující nechá beze změny (zachová read/download stav). Vrací row IDs (-1L = conflict/ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewOnly(chapters: List<ChapterEntity>): List<Long>

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    fun observeForManga(mangaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun getById(id: String): ChapterEntity?

    @Query("UPDATE chapter SET downloadStatus = :status WHERE id = :id")
    suspend fun setDownloadStatus(id: String, status: DownloadStatus)

    @Query("UPDATE chapter SET downloadStatus = :status, localPath = :localPath, pageCount = :pageCount WHERE id = :id")
    suspend fun markDownloaded(id: String, status: DownloadStatus, localPath: String, pageCount: Int)

    @Query("UPDATE chapter SET read = :read, lastPageRead = :lastPageRead, lastReadAt = :lastReadAt WHERE id = :id")
    suspend fun updateProgress(id: String, read: Boolean, lastPageRead: Int, lastReadAt: Long)

    @Query("UPDATE chapter SET lastScrollOffset = :offset, lastReadAt = :lastReadAt WHERE id = :id")
    suspend fun updateScrollOffset(id: String, offset: Int, lastReadAt: Long)

    /** Přemapuje kapitolu na novou URL/id při opravě odkazu (viz MangaRepository.recoverMangaLink) -
     * záměrně NEMĚNÍ read/lastPageRead/lastReadAt/lastScrollOffset/downloadStatus/localPath/
     * pageCount/discoveredAt, aby uživatel o postup čtení/stažené soubory nepřišel. */
    @Query("""
        UPDATE chapter SET id = :newId, url = :newUrl, name = :newName, dateUpload = :dateUpload,
               scanlationGroup = :scanlationGroup, volume = :volume, groupsJson = :groupsJson
        WHERE id = :oldId
    """)
    suspend fun relink(
        oldId: String,
        newId: String,
        newUrl: String,
        newName: String,
        dateUpload: Long,
        scanlationGroup: String?,
        volume: String?,
        groupsJson: String?,
    )

    @Query("""
        UPDATE chapter SET verifiedPageCount = :count, isFallbackSource = :isFallback,
               fallbackChapterId = :fallbackChapterId WHERE id = :id
    """)
    suspend fun setVerifiedPageCount(id: String, count: Int, isFallback: Boolean, fallbackChapterId: String? = null)

    // Manga/kapitola id se generuje deterministicky ze zdroje+URL (viz MangaRepository.mangaId/
    // chapterId) a odebrání z knihovny mangu ani kapitoly nemaže (jen inLibrary = false, viz
    // MangaDao.setInLibrary) - bez tohohle resetu by opetovne pridani te same mangy tise
    // "zdedilo" stary stav cteni (read/lastPageRead) z doby pred odebranim, takže by cerstve
    // pridany titul vypadal jako uz kompletne precteny.
    @Query("UPDATE chapter SET read = 0, lastPageRead = 0, lastScrollOffset = 0, lastReadAt = 0 WHERE mangaId = :mangaId")
    suspend fun resetProgressForManga(mangaId: String)

    @Query("SELECT COUNT(*) FROM chapter WHERE mangaId = :mangaId")
    suspend fun countForManga(mangaId: String): Int

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    suspend fun getAllForManga(mangaId: String): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM chapter WHERE read = 1")
    suspend fun countRead(): Int

    @Query("SELECT COUNT(*) FROM chapter WHERE read = 1")
    fun observeReadCount(): Flow<Int>

    @Query("SELECT * FROM chapter WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)")
    suspend fun getAllForLibrary(): List<ChapterEntity>

    // ── Counts per manga ──────────────────────────────────────────────────────

    // Agregátorské zdroje (ComicK) ukládají zvlášť řádek za KAŽDOU skupinu, co danou
    // kapitolu přeložila - stejné chapterNumber tak může mít v tabulce víc řádků.
    // COUNT(*) by proto sčítal kapitoly přes všechny skupiny místo unikátních čísel
    // (např. "434" místo skutečných ~156) - group by mangaId+chapterNumber napřed
    // sjednotí duplicity, teprve pak se počítá. Nemá vliv na zdroje s 1:1 kapitolami
    // (tam je group by no-op).
    @Query(
        """
        SELECT mangaId, COUNT(*) as count FROM (
            SELECT mangaId, chapterNumber FROM chapter
            GROUP BY mangaId, chapterNumber
            HAVING SUM(CASE WHEN read = 1 THEN 1 ELSE 0 END) = 0
        )
        GROUP BY mangaId
        """
    )
    fun observeUnreadCounts(): Flow<List<MangaUnreadCount>>

    @Query(
        """
        SELECT mangaId, COUNT(*) as count FROM (
            SELECT DISTINCT mangaId, chapterNumber FROM chapter
        )
        GROUP BY mangaId
        """
    )
    fun observeTotalCounts(): Flow<List<MangaTotalCount>>

    @Query("SELECT mangaId, COUNT(*) as count FROM chapter WHERE downloadStatus = 'DOWNLOADED' GROUP BY mangaId")
    fun observeDownloadedCountPerManga(): Flow<List<MangaDownloadedCount>>

    // ── Download management ───────────────────────────────────────────────────

    @Query("SELECT * FROM chapter WHERE downloadStatus != 'NOT_DOWNLOADED' ORDER BY mangaId ASC, chapterNumber DESC")
    fun observeNonEmptyDownloads(): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapter WHERE downloadStatus = 'DOWNLOADED'")
    fun observeDownloadedCount(): Flow<Int>

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED', localPath = NULL, pageCount = 0 WHERE downloadStatus = 'DOWNLOADED'")
    suspend fun clearAllDownloaded()

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED', localPath = NULL, pageCount = 0 WHERE id = :id")
    suspend fun resetDownloadForChapter(id: String)

    // "Novinky" - dve zamerne odlisnosti od naivniho "vsechny kapitoly serazene podle data":
    // 1) `c.discoveredAt > m.addedAt` - `dateUpload` je datum VYDANI na zdroji (muze byt roky
    //    stare), `discoveredAt` je kdy appka radek poprve ulozila. Bez tehle podminky by prvni
    //    synchronizace ciziho titulu (napr. 8 jiz existujicich kapitol pri pridani do
    //    knihovny) zaplavila Novinky celym archivem, jako by slo o 8 novych vydani
    //    (nahlaseno uzivatelem). Porovnanim s `addedAt` (kdy uzivatel mangu pridal) zustanou
    //    ve feedu jen kapitoly objevene AZ POTOM - tedy skutecne nove.
    // 2) `c.id = (SELECT ... ORDER BY discoveredAt ASC LIMIT 1)` - u agregovanych zdroju
    //    (ComicK) muze stejne cislo kapitoly vydat vic prekladatelskych skupin zvlast, kazda
    //    jako samostatny radek - bez tehle podminky by kazda skupina znamenala vlastni
    //    polozku ve feedu (uzivatel hlasil 3 upozorneni na stejnou kapitolu). Vybere se jen
    //    NEJDRIV objevena skupina jako zastupce cisla kapitoly.
    @Query("""
        SELECT c.id as chapterId, c.name as chapterName, c.chapterNumber, c.dateUpload,
               c.mangaId, m.title as mangaTitle, m.coverUrl, c.sourceId, c.read
        FROM chapter c
        INNER JOIN manga m ON c.mangaId = m.id
        WHERE m.inLibrary = 1
          AND c.discoveredAt > m.addedAt
          AND c.id = (
              SELECT c2.id FROM chapter c2
              WHERE c2.mangaId = c.mangaId AND c2.chapterNumber = c.chapterNumber
              ORDER BY c2.discoveredAt ASC LIMIT 1
          )
          AND (SELECT COUNT(DISTINCT c3.chapterNumber) FROM chapter c3
               WHERE c3.mangaId = c.mangaId AND c3.discoveredAt > c.discoveredAt
                 AND c3.discoveredAt > m.addedAt) < 20
        ORDER BY c.discoveredAt DESC
        LIMIT 500
    """)
    fun observeUpdates(): Flow<List<UpdateItem>>

    @Query("UPDATE chapter SET read = 1 WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)")
    suspend fun markAllRead()

    @Query("UPDATE chapter SET read = 1, lastPageRead = 0 WHERE mangaId IN (:mangaIds)")
    suspend fun markAllReadForMangas(mangaIds: List<String>)

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED' WHERE downloadStatus IN ('QUEUED', 'DOWNLOADING')")
    suspend fun resetActiveDownloads()
}
