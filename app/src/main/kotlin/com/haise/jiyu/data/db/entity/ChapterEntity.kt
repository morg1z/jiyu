package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }

@Entity(
    tableName = "chapter",
    indices = [
        Index("mangaId"),
        Index(value = ["mangaId", "read"]),
        Index(value = ["mangaId", "chapterNumber"]),
        Index("chapterNumber"),
        Index("read"),
        Index("downloadStatus"),
        Index("dateUpload"),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val sourceId: String,
    val url: String,
    val name: String,
    val chapterNumber: Float,
    val dateUpload: Long,
    val read: Boolean = false,
    val lastPageRead: Int = 0,
    /** Kdy byla naposledy zapsána pozice čtení (lastPageRead/lastScrollOffset) - viz [com.haise.jiyu.ui.reader.ReaderViewModel] pro 10denní expiraci přesné pozice. */
    val lastReadAt: Long = 0L,
    /** Přesná pozice scrollu ve webtoon (svislém) čtecím režimu, v pixelech od horního okraje stránky [lastPageRead]. */
    val lastScrollOffset: Int = 0,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    /** Lokální složka se staženými stránkami (pokud downloadStatus == DOWNLOADED). */
    val localPath: String? = null,
    val pageCount: Int = 0,
    val scanlationGroup: String? = null,
    val volume: String? = null,
    /** JSON pole [{"name":...,"slug":...}] - viz SGroup. Zatim se nikde necte zpet do UI (pripraveno pro budouci klikaci stranku skupiny). */
    val groupsJson: String? = null,
    /** Kdy tenhle radek poprve vlozila appka (ne kdy zdroj kapitolu skutecne vydal - to je
     * [dateUpload]). Rozliseni je dulezite pro "Novinky" - viz ChapterDao.observeUpdates -
     * pri prvnim pridani mangy s uz existujicim archivem (napr. 8 kapitol) se cely archiv
     * vlozi naraz a MEL by se brat jako "uz mam precteno/videno", ne jako "8 novych upozorneni".
     * MangaRepository.refreshChapters nastavi na System.currentTimeMillis() pri vytvoreni. */
    val discoveredAt: Long = 0L,
    /** Overeny pocet stranek pri ONLINE cteni (ne stazeni - to je pageCount) - jakmile appka
     * jednou zkontroluje kompletnost kapitoly (viz SourceResolverViewModel fallback), zapise
     * sem vysledek, aby se pri pristim otevreni uz nic znovu nekontrolovalo. null = jeste
     * neoverovano. */
    val verifiedPageCount: Int? = null,
    /** true, pokud appka tuhle kapitolu dotahla z JINEHO zdroje, nez byl puvodne vybrany
     * "nejvhodnejsi" kandidat, protoze puvodni verze byla podezrele kratka - viz
     * SourceResolverViewModel. Ridi jednorazovou hlasku v ctecce (ReaderViewModel.loadChapter). */
    val isFallbackSource: Boolean = false,
    /** Kdyz appka pri kontrole tehle (puvodni, kratke) kapitoly najde lepsi alternativu, ulozi
     * sem ID kapitoly, na kterou se ma miste ni presmerovat - viz SourceResolverViewModel. Bez
     * tohohle by se PUVODNI (kratky) radek nikdy neoznacil jako "jiz overeno" a appka by
     * kontrolu opakovala pri kazdem otevireni znovu, protoze selectCandidate vzdy nejdriv najde
     * puvodniho "nejvhodnejsiho" kandidata, ne rovnou tu nahradni kapitolu. null = beze zmeny. */
    val fallbackChapterId: String? = null,
)
