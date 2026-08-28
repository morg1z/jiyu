package com.haise.jiyu.source

/**
 * Manga tak, jak ji vrací konkrétní zdroj (ještě neuložená v Room databázi).
 */
data class SManga(
    val sourceId: String,
    val url: String,
    val title: String,
    val coverUrl: String?,
    val description: String? = null,
    val status: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val contentType: String = "MANGA",
    val demographic: String? = null,
    val translationCompleted: Boolean? = null,
    val hasAnime: Boolean? = null,
    val finalChapter: String? = null,
    val rating: Double? = null,
    val followCount: Int? = null,
    val rank: Int? = null,
    val alternateTitles: List<String> = emptyList(),
    /** Nejvyšší číslo kapitoly, co zdroj u téhle položky přímo hlásí v seznamovém API (bez
     * dalšího requestu na kompletní seznam kapitol) - odhad "kolik kapitol to má", ne přesný
     * počet (může mít mezery). Zatím jen ComicK (`last_chapter`, viz ComicKSource.comicFromJson). */
    val lastChapter: Float? = null,
)

data class MangaFilter(
    val status: String? = null,
    val year: Int? = null,
    val sortBy: String = "popular",
)

/** Překladatelská/scan skupina u konkrétní kapitoly - `slug` je nepovinný (ne každý zdroj ho má). */
data class SGroup(val name: String, val slug: String? = null)

/**
 * Kapitola tak, jak ji vrací konkrétní zdroj.
 */
data class SChapter(
    val sourceId: String,
    val mangaUrl: String,
    val url: String,
    val name: String,
    val chapterNumber: Float,
    val dateUpload: Long,
    val scanlationGroup: String? = null,
    val volume: String? = null,
    val groups: List<SGroup> = emptyList(),
)

/**
 * Jedna stránka kapitoly - buď přímá URL na obrázek, nebo URL,
 * kterou je potřeba ještě dorozlouskat (viz getImageUrl).
 */
data class Page(
    val index: Int,
    val url: String,
    var imageUrl: String? = null,
)

/**
 * Společné rozhraní pro všechny zdroje manga.
 *
 * Každý nový zdroj = nová třída implementující tohle rozhraní.
 * Appka pak vůbec neřeší, odkud data jsou - jen volá tyhle metody.
 * Díky tomu se dá přidat další zdroj, aniž bys sahal do zbytku appky.
 */
interface MangaSource {
    /** Unikátní ID zdroje, používá se jako prefix v databázi. */
    val id: String

    /** Jméno zobrazené v UI (výběr zdroje). */
    val name: String

    /** Typ obsahu: MANGA | MANHWA | MANHUA | NOVEL | COMIC. Výchozí = MANGA. */
    val contentType: String get() = "MANGA"

    /** Kód jazyka dle BCP-47 (en, cs, fr, es, pt, ja, ko, zh, …). Výchozí = en. */
    val language: String get() = "en"

    /** Doménová URL webu zdroje (bez cesty) - použije se pro načtení favicony v UI. Výchozí = null (spadne na barevný monogram). */
    val homepageUrl: String? get() = null

    /** Zdroj s explicitním 18+ obsahem - viz SettingsRepository.showAdultSources a SourceManager (filtruje z Browse/hledání, ne z už přidané knihovny). Výchozí false. */
    val isAdult: Boolean get() = false

    /** Fulltextové hledání podle názvu. */
    suspend fun search(query: String, page: Int = 1, filter: MangaFilter = MangaFilter()): List<SManga>

    /** Populární / doporučené tituly pro daný zdroj (výchozí zobrazení v Browse). */
    suspend fun getPopular(page: Int = 1, filter: MangaFilter = MangaFilter()): List<SManga>

    /** Detail mangy - doplní popis, stav vydávání apod. */
    suspend fun getMangaDetails(manga: SManga): SManga

    /** Seznam kapitol pro danou mangu, seřazený od nejnovější. */
    suspend fun getChapterList(manga: SManga): List<SChapter>

    /** Seznam stránek pro danou kapitolu. */
    suspend fun getPageList(chapter: SChapter): List<Page>

    /**
     * Pro zdroje, kde URL stránky není přímo obrázek (např. je potřeba
     * ještě zavolat další endpoint nebo rozparsovat token). Výchozí
     * implementace prostě vrátí url beze změny.
     */
    suspend fun getImageUrl(page: Page): String = page.url

    /** Zdroj poskytuje komentare k JEDNOTLIVYM kapitolam (ne jen k titulu) - viz [getChapterComments].
     * Vychozi = zadny zdroj neposkytuje, appka tak nemusi zkouset stahovat komentare u zdroje,
     * ktery zadne nema. */
    val supportsChapterComments: Boolean get() = false

    /** Komentare ke KONKRETNI kapitole. Vola se az line, kdyz uzivatel otevre panel komentaru v
     * ctecce (viz ReaderViewModel) - NE automaticky pri otevreni kapitoly, aby appka nedelala
     * network navic u vetsiny cteni, kdy uzivatel komentare vubec neotevre. */
    suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> = emptyList()
}
