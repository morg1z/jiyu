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
    /** Vybrané tagy/žánry (viz [MangaSource.getAvailableTags]) - obsahuje `id`
     * z [FilterTag], ne zobrazovaný `label`. Prázdné = žádný tagový filtr. */
    val genres: List<String> = emptyList(),
)

/** Jeden tag/žánr tak, jak ho nabízí konkrétní zdroj - `id` je hodnota, kterou
 * zdroj sám používá v URL/query (slug, UUID...), `label` je text pro UI. */
data class FilterTag(val id: String, val label: String)

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

    /** Zdroj nabízí vlastní seznam tagů/žánrů pro filtrování (viz [getAvailableTags]).
     * Výchozí false = appka u tohohle zdroje sekci tagů ve Filtrech vůbec nezobrazí. */
    val supportsTagFilter: Boolean get() = false

    /**
     * Umí zdroj vrátit "Populární" a "Nejnovější" v RŮZNÉM pořadí (viz [MangaFilter.sortBy])? `false` = obě
     * záložky by ukazovaly totéž, appka proto přepínač u zdroje vůbec nezobrazí. Zdroj, který řazení
     * doplní, tuhle vlastnost odstraní (výchozí je `true`).
     */
    val supportsSortOrder: Boolean get() = true

    /**
     * Zdroj je zjevně rozbitý (web změnil strukturu/zanikl a parser nefunguje) - `SourceManager` ho nenabízí
     * v Procházet a hledání, ale zůstává dostupný přes `getById`, aby už přidané tituly v knihovně dál šly
     * otevřít. Živý test (`LiveSourceSmokeTest`) ukazuje, který zdroj to má být; důvod je v [brokenReason].
     */
    val isBroken: Boolean get() = false

    /**
     * Zahrnout zdroj do globálního hledání a do hledání zdroje pro ComicK? Rozšířený katalog (stovky webů na
     * sdílených šablonách) to vypíná - každý dotaz by jinak zatížil všechny weby najednou. Takové zdroje jsou
     * dál v Procházet a hledají se v nich jednotlivě.
     */
    val includeInGlobalSearch: Boolean get() = true

    /** Krátké vysvětlení pro vývojáře, proč je [isBroken] `true` (co se na webu změnilo). */
    val brokenReason: String? get() = null

    /**
     * Klíče řazení ([MangaFilter.sortBy]: `popular`, `latest`, `title`, `rating`), které zdroj skutečně rozlišuje -
     * UI nabídne jen tyhle volby a přepínač Populární/Nejnovější skryje, pokud na výběr není. Výchozí se odvozuje
     * z [supportsSortOrder]; zdroj s bohatším řazením (např. abecedně) ji přepíše.
     */
    val availableSorts: Set<String> get() = if (supportsSortOrder) setOf("popular", "latest") else setOf("popular")

    /** Seznam tagů/žánrů, které zdroj nabízí pro filtrování - buď natvrdo (ověřeno
     * živě proti webu), nebo dotažený přímo z webu (např. z jeho vyhledávacího
     * formuláře či vlastního API). Volá se až při otevření sekce tagů ve Filtrech,
     * ne automaticky při načtení zdroje. Výchozí = prázdný seznam. */
    suspend fun getAvailableTags(): List<FilterTag> = emptyList()

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
