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
    /** Země původu tak, jak ji vrací API zdroje (ComicK `country`: jp/kr/cn/...,
     * MangaDex `originalLanguage`: ja/ko/zh/...). Na rozdíl od [contentType] nese
     * RAW hodnotu - "unknown"/"others" tady zůstane viditelné, zatímco contentType
     * z něj musí udělat default. Používá se pro katalogovou verifikaci typu titulu
     * (viz MangaRepository.verifyContentType). */
    val countryOfOrigin: String? = null,
    /** Nejvyšší číslo kapitoly, co zdroj u téhle položky přímo hlásí v seznamovém API (bez
     * dalšího requestu na kompletní seznam kapitol) - odhad "kolik kapitol to má", ne přesný
     * počet (může mít mezery). Zatím jen ComicK (`last_chapter`, viz ComicKSource.comicFromJson). */
    val lastChapter: Float? = null,
)

data class MangaFilter(
    val status: String? = null,
    val year: Int? = null,
    val sortBy: String = "popular",
    /** Vybrané žánry (viz [MangaSource.getAvailableTags], kind `genre`) - obsahuje `id`
     * z [FilterTag], ne zobrazovaný `label`. Prázdné = žádný žánrový filtr. */
    val genres: List<String> = emptyList(),
    /** Žánry k VYLOUČENÍ - jen zdroje s [MangaSource.supportsExcludeTags]. */
    val excludeGenres: List<String> = emptyList(),
    /** Volné tagy (kind `tag` z [getAvailableTags]) - zdroje, co mají vedle žánrů
     * i fulltextovou tag taxonomii (ComicKArt ~9k tagů). */
    val tags: List<String> = emptyList(),
    /** Tagy k VYLOUČENÍ - opět jen s [MangaSource.supportsExcludeTags]. */
    val excludeTags: List<String> = emptyList(),
    /** Demografické kategorie (id z [MangaSource.availableDemographics]). */
    val demographic: List<String> = emptyList(),
    /** Typ komiksu země původu (id z [MangaSource.availableComicTypes] - jp/kr/cn/...). */
    val comicTypes: List<String> = emptyList(),
    /** Minimální počet kapitol - jen zdroje s [MangaSource.supportsMinChaptersFilter]. */
    val minChapters: Int? = null,
    /** "Přidáno před X dny" (id z [MangaSource.availableCreatedRanges]). */
    val createdRangeDays: Int? = null,
    /** Směr řazení - jen zdroje s [MangaSource.supportsSortDirection]. Výchozí desc. */
    val sortAscending: Boolean = false,
)

/** Jeden tag/žánr tak, jak ho nabízí konkrétní zdroj - `id` je hodnota, kterou
 * zdroj sám používá v URL/query (slug, UUID...), `label` je text pro UI.
 * `kind` rozlišuje sekce v pickeru (`genre`/`tag`) u zdrojů, co mají obě
 * taxonomie (ComicKArt: 84 žánrů + 9k volných tagů); null = jedna plochá sekce. */
data class FilterTag(val id: String, val label: String, val kind: String? = null)

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
    /** ISO kód jazyka kapitoly ("en", "pt-br", ...) - hlásí jen vícejazyčné
     * agregátory (ComicK, comickart), které vrací jednu logickou kapitolu tolikrát,
     * v kolika jazycích existuje. Běžné zdroje nechávají null. UI/čtečka jazykem
     * deduplikuje - viz [com.haise.jiyu.data.repository.preferEnglishChapters]. */
    val language: String? = null,
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
     * Výchozí true - `getAvailableTags()` vrací výchozím prázdný seznam, což picker
     * zobrazí poctivě jako "zdroj žádné tagy nenabízí" (drive byl default false, takze
     * ~100 zdroju s implementovanymi tagy picker skryvalo uplne - flag se musel znovu
     * zapnout rucne a u kazdeho noveho zdroje se na to zapomnelo). Zdroj muze explicitne
     * opt-outnout `= false`, kdyz sekci tagu nema smysl vubec ukazovat. */
    val supportsTagFilter: Boolean get() = true

    /**
     * Zdroj skutečně aplikuje [MangaFilter.status] (server-side, ne klientovsky) -
     * bez toho by filtr "Stav vydávání" jen tvrdil filtr, který nic nedělá
     * (hlášený bug: filtry "prostě nic"). Výchozí false; opt-in u zdrojů, kde je
     * parametr ověřený živě (MangaDex `status[]`, ...).
     */
    val supportsStatusFilter: Boolean get() = false

    /** Zdroj skutečně aplikuje [MangaFilter.year] - stejné pravidlo jako [supportsStatusFilter]. */
    val supportsYearFilter: Boolean get() = false

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
     * Hodnoty [MangaFilter.status], které zdroj umí server-side aplikovat -
     * UI schová chipy pro stavy, jež zdroj neumí vyjádřit (např. web má jen
     * "ongoing/finished" bez hiatus). Prázdný výběr ("Vše") je vždy dostupný.
     */
    val availableStatuses: List<String> get() = listOf("ongoing", "completed", "hiatus")

    /**
     * Zdroj umí tagy/žánry nejen ZAHRNOUT, ale i VYLOUČIT (např. ComicKArt `excludes[]`
     * /`excluded_tags[]`) - picker pak nabídne 3-stavový výběr (vypnuto→zahrnout→vyloučit)
     * a filtr naplní [MangaFilter.excludeGenres]/[MangaFilter.excludeTags]. Výchozí false.
     */
    val supportsExcludeTags: Boolean get() = false

    /**
     * Demografické kategorie pro filtrování (id = hodnota pro query param, label pro UI) -
     * např. ComicKArt: Shounen/Josei/Seinen/Shoujo/None → `demographic[]`. Prázdné =
     * zdroj nic takového nemá a UI sekci schová.
     */
    val availableDemographics: List<FilterTag> get() = emptyList()

    /**
     * Typy komiksu dle země původu (id = query hodnota, label pro UI) -
     * např. ComicKArt: Manga(jp)/Manhwa(kr)/Manhua(cn)/Others → `country[]`.
     */
    val availableComicTypes: List<FilterTag> get() = emptyList()

    /**
     * Předdefinované "přidáno před X dny" volby (id = počet dnů jako string) -
     * např. ComicKArt `time` param (3/7/30/90/180/365/730).
     */
    val availableCreatedRanges: List<FilterTag> get() = emptyList()

    /** Zdroj aplikuje [MangaFilter.minChapters] server-side. Výchozí false. */
    val supportsMinChaptersFilter: Boolean get() = false

    /** Zdroj umí měnit směr řazení ([MangaFilter.sortAscending]). Výchozí false (vždy desc). */
    val supportsSortDirection: Boolean get() = false

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
