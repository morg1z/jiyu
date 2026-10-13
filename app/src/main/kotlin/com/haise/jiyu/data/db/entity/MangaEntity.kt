package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `id` je vždy "{sourceId}::{url}" - díky tomu je unikátní napříč zdroji
 * a nepotřebujeme žádný centrální generátor ID.
 */
@Entity(
    tableName = "manga",
    indices = [
        Index("inLibrary"),
        Index("sourceId"),
        Index(value = ["inLibrary", "sourceId"]),
        Index("lastReadAt"),
        Index("title"),
        Index("addedAt"),
        Index("lastUpdated"),
        Index("isFavorite"),
        Index("url"),
        Index("comickHid"),
        Index("resolvedMangaId"),
    ],
)
data class MangaEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val url: String,
    val title: String,
    val coverUrl: String?,
    val description: String?,
    val status: String?,
    val inLibrary: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val lastReadChapterId: String? = null,
    val lastReadAt: Long = 0L,
    val readerDirectionOverride: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: String = "",
    val year: Int? = null,
    val autoDownload: Boolean = false,
    val userRating: Int? = null,
    val contentType: String = "MANGA",
    /** Typ už ověřil autoritativní katalog (ComicK `country` / MangaUpdates `type`,
     * viz MangaRepository.verifyContentType) - verifikovaná hodnota má přednost před
     * jakýmkoli source-defaultem, který by ji jinak při refreshi vrátil zpátky. */
    val contentTypeVerified: Boolean = false,
    val excludeFromUpdates: Boolean = false,
    val malId: Int? = null,
    val malScore: Float? = null,
    val malStatus: String? = null,
    val addedAt: Long = 0,
    val readingStatus: String? = null,
    val kitsuId: String? = null,
    val kitsuScore: Float? = null,
    val mangaUpdatesId: Long? = null,
    /** Opaque Comick title HID z oficialni Library API (`^[A-Za-z0-9_-]{1,64}$`) - trvale
     *  vazani lokalni manga -> Comick entry. NENI slug (mutable) a NENI chapter hid
     *  (ten je v ChapterEntity.url). Viz [com.haise.jiyu.data.tracking.ComickRepository]. */
    val comickHid: String? = null,
    /** Id manga řádku (`sourceId::url`) reálného zdroje, na který se ComicK katalog
     *  naposledy vyresolvoval (viz SourceResolverViewModel.selectCandidate). Díky tomu
     *  další "Pokračovat"/otevření kapitoly přeskočí drahý sweep všech zdrojů - stačí
     *  refreshnout kapitoly jen tohoto zdroje a ověřit, že pokrývá cíl i nejnovější
     *  ComicK kapitolu. Indexovaný, protože propagateReadProgressToLinkedChapters na
     *  něj filtruje při každém otočení stránky. */
    val resolvedMangaId: String? = null,
    val readingTimeMs: Long = 0L,
    /** Posledni uspesne dotazeni detailu ze zdroje (0 = nikdy) - backfill comick
     *  zanru se na razitkovem zaklade konverguje a nesnazi se donekonecna o tituly,
     *  jejichz detail na serveru proste neexistuje. */
    val detailFetchedAt: Long = 0L,
    /** Posledni USPESNY refresh seznamu kapitol (0 = nikdy) - odlisuje "nikdy jsme
     *  se nesnazili" (backfill kandidat) od "zkusili jsme a zdroj vratil prazdno". */
    val lastChapterRefreshAt: Long = 0L,
    /** Cas posledniho zapisu `readingStatus` (ms). U remote aplikace se zapisuje
     *  `updated_at` z Comick entry - pull sync pak prepisuje status jen kdyz je remote
     *  opravdu novejsi nez posledni lokalni zmena (jinak by pull po offline selhani
     *  twoway pushe uzivatelovu zmenu tise vratil zpet). */
    val statusUpdatedAt: Long = 0L,
    val isFavorite: Boolean = false,
    val demographic: String? = null,
    val translationCompleted: Boolean? = null,
    val hasAnime: Boolean? = null,
    val finalChapter: String? = null,
    val rating: Double? = null,
    val followCount: Int? = null,
    val rank: Int? = null,
    /** JSON pole řetězců - viz [com.haise.jiyu.data.repository.serializeAltTitles]. */
    val alternateTitles: String = "",
    /**
     * Volitelný volný text posílaný AI překladači jako doplňkový kontext díla (viz
     * [com.haise.jiyu.translate.GeminiUltraPrompt.buildMangaContext]) - třeba "hlavní hrdina
     * je ve skutečnosti žena v přestrojení" nebo "děj je celý retrospektiva". Na rozdíl od
     * [MangaNoteEntity] (čistě soukromá poznámka čtenáře, NIKDY se neposílá žádnému API) je
     * tohle POLE VÝSLOVNĚ určené k odeslání ven - proto samostatné pole, ne recyklace
     * poznámky, aby si uživatel omylem neposlal soukromý text tam, kam nechtěl.
     */
    val translationContextNote: String? = null,
)
