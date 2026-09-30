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

    /** Ruční označení přečtené/nepřečtené (detail mangy) předává `lastReadAt = 0` a `lastPageRead = 0` -
     * tehdy se maže i uložený webtoon offset, aby "nepřečteno" nenechalo starou pozici. Čtečka
     * vždy posílá skutečný čas, takže čtení první stránky offset nesmaže.
     * `pageCount > 0` (čtečka zná skutečný počet vykreslených stránek) se zapíše do
     * `verifiedPageCount` - reálná verze "online ověření" počtu stránek, potřebná pro
     * % průběhu kapitoly na kartách "Pokračovat ve čtení". */
    @Query(
        "UPDATE chapter SET read = :read, lastPageRead = :lastPageRead, lastReadAt = :lastReadAt, " +
            "verifiedPageCount = CASE WHEN :pageCount > 0 THEN :pageCount ELSE verifiedPageCount END, " +
            "lastScrollOffset = CASE WHEN :lastPageRead = 0 AND :lastReadAt = 0 THEN 0 ELSE lastScrollOffset END " +
            "WHERE id = :id"
    )
    suspend fun updateProgress(id: String, read: Boolean, lastPageRead: Int, lastReadAt: Long, pageCount: Int = 0)

    /** Propagace postupu čtení na kapitoly, které na [resolvedChapterId] přesměrovávají přes
     * `fallbackChapterId` - typicky metadatová ComicK kapitola, kterou appka při výběru zdroje
     * přilinkovala na reálnou kapitolu jiného zdroje (viz SourceResolverViewModel.selectCandidate).
     * `read` se nikdy nesníží (dočtená zůstane dočtená - stejné pravidlo jako u hlavního zápisu
     * v ReaderViewModel.processPageProgress); `lastPageRead`/`verifiedPageCount` se drží v synchu,
     * aby % na kartách fungovalo i pro ComicK tituly. */
    @Query(
        "UPDATE chapter SET read = MAX(read, :read), lastPageRead = :lastPageRead, lastReadAt = :lastReadAt, " +
            "verifiedPageCount = CASE WHEN :pageCount > 0 THEN :pageCount ELSE verifiedPageCount END " +
            "WHERE fallbackChapterId = :resolvedChapterId"
    )
    suspend fun propagateProgressToFallbackParents(
        resolvedChapterId: String,
        read: Boolean,
        lastPageRead: Int,
        lastReadAt: Long,
        pageCount: Int,
    )

    /** Přilinkuje kapitolu na "reálnou" kapitolu, kterou appka skutečně čte (ComicK metadatová
     * kapitola → resolved kapitola zdroje). Při pozdějším výběru jiného zdroje se prostě přepíše. */
    @Query("UPDATE chapter SET fallbackChapterId = :targetChapterId WHERE id = :id")
    suspend fun setFallbackTarget(id: String, targetChapterId: String?)

    @Query("UPDATE chapter SET lastScrollOffset = :offset, lastReadAt = :lastReadAt WHERE id = :id")
    suspend fun updateScrollOffset(id: String, offset: Int, lastReadAt: Long)

    /** Přemapuje kapitolu na novou URL/id (+ zdroj při cross-source relinku, viz
     * MangaRepository.recoverMangaLink/relinkMangaToSource) - záměrně NEMĚNÍ read/lastPageRead/
     * lastReadAt/lastScrollOffset/downloadStatus/localPath/pageCount/discoveredAt, aby uživatel
     * o postup čtení/stažené soubory nepřišel. */
    // OR REPLACE: kdyz kapitola s newId uz existuje (canonical kapitola se mezitim
    // objevila samostatne), plain UPDATE vyhodi SQLITE_CONSTRAINT_PRIMARYKEY a shodi
    // celou relink transakci v MangaRepository (audit DB-2). REPLACE kolizni radek
    // smaze a prejmenuje stary - postup/stazeni stareho radku se zachovava, coz je
    // presne semantika, kterou docstring slibuje.
    @Query("""
        UPDATE OR REPLACE chapter SET id = :newId, sourceId = :newSourceId, url = :newUrl, name = :newName,
               dateUpload = :dateUpload, scanlationGroup = :scanlationGroup, volume = :volume,
               groupsJson = :groupsJson
        WHERE id = :oldId
    """)
    suspend fun relink(
        oldId: String,
        newId: String,
        newSourceId: String,
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

    /** Protějšek [com.haise.jiyu.data.db.ManualTranslationDao.relinkChapter] pro jiné kapitoly,
     * které na relinkovanou kapitolu ukazují jako na svůj fallback (viz [setVerifiedPageCount]) -
     * bez tohohle by po relinku ukazovaly na neexistující staré id a fallback přestal fungovat. */
    @Query("UPDATE chapter SET fallbackChapterId = :newChapterId WHERE fallbackChapterId = :oldChapterId")
    suspend fun relinkFallbackChapterId(oldChapterId: String, newChapterId: String)

    // Manga/kapitola id se generuje deterministicky ze zdroje+URL (viz MangaRepository.mangaId/
    // chapterId) a odebrání z knihovny mangu ani kapitoly nemaže (jen inLibrary = false, viz
    // MangaDao.setInLibrary) - bez tohohle resetu by opetovne pridani te same mangy tise
    // "zdedilo" stary stav cteni (read/lastPageRead) z doby pred odebranim, takže by cerstve
    // pridany titul vypadal jako uz kompletne precteny.
    @Query("UPDATE chapter SET read = 0, lastPageRead = 0, lastScrollOffset = 0, lastReadAt = 0 WHERE mangaId = :mangaId")
    suspend fun resetProgressForManga(mangaId: String)

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED', localPath = NULL, pageCount = 0 WHERE mangaId = :mangaId AND downloadStatus = 'DOWNLOADED'")
    suspend fun resetDownloadsForManga(mangaId: String)

    @Query("SELECT COUNT(*) FROM chapter WHERE mangaId = :mangaId")
    suspend fun countForManga(mangaId: String): Int

    /** Nejvyšší zatím známé číslo kapitoly - baseline pro "nové kapitoly" (refreshChapters
     * hlásí jako nové jen inserty nad tímhle maximem; NULL = kapitoly se ještě nikdy
     * nenačetly, takže vkládaná dávka je zakladní naplnění, ne novinka). Fallback řádky
     * (kapitolu dotáhl resolver z JINÉHO zdroje - `isFallbackSource`) se vynechávají:
     * jejich číslování nemusí odpovídat hostitelskému zdroji a nafouknuté maximum by
     * umlčelo skutečně nové kapitoly titulu. */
    @Query("SELECT MAX(chapterNumber) FROM chapter WHERE mangaId = :mangaId AND isFallbackSource = 0")
    suspend fun getMaxChapterNumber(mangaId: String): Float?

    /** Batched varianta [countForManga] - pro seznam manga id vrátí počty jedním dotazem
     * místo N+1 (viz [com.haise.jiyu.data.repository.MangaRepository.findLibraryMatchesByTitle]). */
    @Query("SELECT mangaId, COUNT(*) as count FROM chapter WHERE mangaId IN (:mangaIds) GROUP BY mangaId")
    suspend fun countForMangas(mangaIds: List<String>): List<MangaTotalCount>

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    suspend fun getAllForManga(mangaId: String): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM chapter WHERE read = 1")
    fun observeReadCount(): Flow<Int>

    /** Jednorázové deduplikované počty pro obrazovku Statistik - stejná logika slučování
     * skupinových duplicit jako [observeTotalCounts]/[observeUnreadCounts] (agregátorské zdroje
     * typu ComicK mají řádek na skupinu, ne na kapitolu), jen sjednocené do jednoho čísla
     * napříč knihovnou. */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT DISTINCT mangaId, (CASE WHEN chapterNumber > 0 THEN CAST(chapterNumber AS TEXT) ELSE id END) AS chapterKey
            FROM chapter WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)
        )
        """
    )
    suspend fun countDistinctInLibrary(): Int

    /** Dedupovaný počet PŘEČTENÝCH kapitol - kapitola se počítá, když je přečtená aspoň v jedné
     * skupinové verzi (WHERE read = 1 před DISTINCT). Obyčejný COUNT(read=1) u ComicK titulů
     * nafukuje počet násobkem skupin. */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT DISTINCT mangaId, (CASE WHEN chapterNumber > 0 THEN CAST(chapterNumber AS TEXT) ELSE id END) AS chapterKey
            FROM chapter WHERE read = 1 AND mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)
        )
        """
    )
    suspend fun countReadDistinctInLibrary(): Int

    @Query("SELECT * FROM chapter WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)")
    suspend fun getAllForLibrary(): List<ChapterEntity>

    // ── Counts per manga ──────────────────────────────────────────────────────

    // Agregátorské zdroje (ComicK) ukládají zvlášť řádek za KAŽDOU skupinu, co danou
    // kapitolu přeložila - stejné chapterNumber tak může mít v tabulce víc řádků.
    // COUNT(*) by proto sčítal kapitoly přes všechny skupiny místo unikátních čísel
    // (např. "434" místo skutečných ~156) - group by mangaId+chapterNumber napřed
    // sjednotí duplicity, teprve pak se počítá. Nemá vliv na zdroje s 1:1 kapitolami
    // (tam je group by no-op). Kapitoly s číslem 0 (parser číslo nenašel) se NESLUČUJÍ -
    // každá je samostatná (klíč = id), jinak by se všechny "bezčíselné" kapitoly titulu
    // počítaly jako jedna.
    @Query(
        """
        SELECT mangaId, COUNT(*) as count FROM (
            SELECT mangaId, (CASE WHEN chapterNumber > 0 THEN CAST(chapterNumber AS TEXT) ELSE id END) AS chapterKey
            FROM chapter
            GROUP BY mangaId, chapterKey
            HAVING SUM(CASE WHEN read = 1 THEN 1 ELSE 0 END) = 0
        )
        GROUP BY mangaId
        """
    )
    fun observeUnreadCounts(): Flow<List<MangaUnreadCount>>

    @Query(
        """
        SELECT mangaId, COUNT(*) as count FROM (
            SELECT DISTINCT mangaId, (CASE WHEN chapterNumber > 0 THEN CAST(chapterNumber AS TEXT) ELSE id END) AS chapterKey
            FROM chapter
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
    // 3) `c.discoveredAt > MIN(discoveredAt)` - prvni objevena davka je vzdy zakladni
    //    naplneni titulu (kapitoly vlozene jednim refresem sdili stejny timestamp), tudiz
    //    nikdy "novinka". Bez tehle podminky by ve feedu visely stare kapitoly z manga,
    //    jejichz baseline se naplnil AZ PO pridani (selhany prvni fetch, obnovena zaloha,
    //    smazane a znovu nactene kapitoly) - tj. presne nahlasene "stare oznameni z 2024",
    //    protoze baseline radky meli discoveredAt par ms PO addedAt.
    @Query("""
        SELECT c.id as chapterId, c.name as chapterName, c.chapterNumber, c.dateUpload,
               c.mangaId, m.title as mangaTitle, m.coverUrl, c.sourceId, c.read
        FROM chapter c
        INNER JOIN manga m ON c.mangaId = m.id
        WHERE m.inLibrary = 1
          AND c.discoveredAt > m.addedAt
          AND c.discoveredAt > (
              SELECT MIN(c4.discoveredAt) FROM chapter c4 WHERE c4.mangaId = c.mangaId
          )
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

    // lastReadAt = :nowAt u vsech "oznacit prectene" UPDATE - SyncRepository.chaptersToPush
    // filtruje push podle lastReadAt >= lastPushAt, takze bez zapisu casu by se hromadne
    // oznaceni nikdy neodeslalo do cloudu a pull by je mohl prepsat zpet (audit).
    @Query("UPDATE chapter SET read = 1, lastReadAt = :nowAt WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)")
    suspend fun markAllRead(nowAt: Long = System.currentTimeMillis())

    @Query("UPDATE chapter SET read = 1, lastPageRead = 0, lastReadAt = :nowAt WHERE mangaId IN (:mangaIds)")
    suspend fun markAllReadForMangas(mangaIds: List<String>, nowAt: Long = System.currentTimeMillis())

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED' WHERE downloadStatus IN ('QUEUED', 'DOWNLOADING')")
    suspend fun resetActiveDownloads()

    /** Batched "označit přečtené" podle konkrétních id (na rozdíl od [markAllReadForMangas],
     * které bere celou mangu) - pro import historie ze zálohy (viz TachiyomiBackupImporter),
     * kde se dřív volalo [updateProgress] po jednom řádku pro každou nalezenou kapitolu. */
    @Query("SELECT * FROM chapter WHERE id IN (:chapterIds)")
    suspend fun getByIds(chapterIds: List<String>): List<ChapterEntity>

    @Query("UPDATE chapter SET read = 0, lastPageRead = 0, lastScrollOffset = 0, lastReadAt = 0 WHERE id IN (:chapterIds)")
    suspend fun markUnreadByIds(chapterIds: List<String>)

    @Query("UPDATE chapter SET read = 1, lastPageRead = 0, lastReadAt = :nowAt WHERE id IN (:chapterIds)")
    suspend fun markReadByIds(chapterIds: List<String>, nowAt: Long = System.currentTimeMillis())
}
