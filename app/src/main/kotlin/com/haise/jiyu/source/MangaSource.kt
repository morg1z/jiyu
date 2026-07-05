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
)

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

    /** Fulltextové hledání podle názvu. */
    suspend fun search(query: String, page: Int = 1): List<SManga>

    /** Populární / doporučené tituly pro daný zdroj (výchozí zobrazení v Browse). */
    suspend fun getPopular(page: Int = 1): List<SManga>

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
}
