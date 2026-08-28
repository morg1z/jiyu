# Komentáře ke kapitole v čtečce — Design

## Kontext a problém

Uživatelský požadavek: "kdyz cteme... oni maji i vzdy komenty na konci kapitoly... aby jsme z kazde te stranky braly taky ty komentare... kdyz budu cist tak u tech kapitol uvidim komentare, kdyz tam budou". Appka dnes MÁ podobnou featuru pro ComicK - `ComicKSource.getComments()` stahuje komentáře k CELÉMU TITULU z vlastního ComicK API a zobrazuje je v `MangaDetailScreen.kt` (`CommentCard`/`CommentRow`, řádky ~1517-1585). Tenhle spec řeší jiný, nezávislý případ: komentáře ke KONKRÉTNÍ KAPITOLE, zobrazené PŘÍMO V ČTEČCE, ze skutečných čtecích zdrojů (ne z ComicK).

## Rozsahový průzkum (klíčové zjištění)

Appka má aktivně registrováno ~145 zdrojů (`SourceManager.kt`). Živý průzkum (curl, po dávkách) zjistil, že komentáře ke kapitole - server-rendered nebo v JSONu, bez nutnosti JS - reálně a použitelně mají **12 z nich**:

| Zdroj | Formát | Sdílená implementace přes |
|---|---|---|
| MangaK, Comizy | JSON (`initialChapter.latest_comments`) | stejná Next.js "manga reader" platforma - identická struktura |
| mangaread.org, wuxiaworldsite, mangazin, mangagg, manhuanext (vše `MadaraSource`) | wpDiscuz plugin (HTML) | stejný WordPress plugin |
| scythescans, lagoonscans, thunderscans (vlastní třídy, ne `MadaraSource` - "MangaThemesia" šablona) | wpDiscuz plugin (HTML) | STEJNÝ plugin jako výše, i když jiná šablona webu |
| manhuahot, mangadistrict (vše `MadaraSource`) | nativní WordPress komentáře (HTML) | jiný (starší, vestavěný) WP komentářový systém než wpDiscuz |

Zbytek (~133 zdrojů): buď potvrzeně Disqus/JS-only (nepoužitelné bez prohlížeče), nebo bez signálu, nebo komentáře jen na úrovni CELÉHO TITULU ne kapitoly (todaymanga, mangadenizi - mimo scope), nebo nešlo curlem ověřit (Cloudflare - appka má vlastní `CloudflareInterceptor`, takže by u těch mohla uspět i tam, kde curl ne, ale to není součástí tohoto specu).

**Architektonický důsledek:** wpDiscuz se používá napříč RŮZNÝMI třídami/šablonami (Madara i vlastní), takže parser pro něj musí být sdílená, na hierarchii nezávislá funkce - ne metoda jen na `MadaraSource`. Stejně tak JSON formát MangaK/Comizy je bit-přesně identický mezi dvěma jinak nezávislými třídami.

**LikeManga byl zamítnut** - živě ověřeno, že skutečný kontejner komentářů ke kapitole (`#load_show_list_comment`) je PRÁZDNÝ (dotahuje se přes AJAX, ne staticky) - to, co počáteční průzkum našel (`.comment-content`), byl jen postranní widget "poslední komentáře napříč celým webem", ne komentáře ke KONKRÉTNÍ kapitole.

## Datový model a rozhraní

Nový soubor `app/src/main/kotlin/com/haise/jiyu/source/comments/ChapterComment.kt`:
```kotlin
package com.haise.jiyu.source.comments

/** Jeden komentar ke KONKRETNI kapitole (ne k celemu titulu - to resi ComicKSource.getComments).
 * Zadne vnorene odpovedi v prvni verzi (YAGNI) - MangaK/Comizy JSON stejne nedava obsah odpovedi,
 * jen pocet (viz replies_count), a zbyle 2 formaty (wpDiscuz, nativni WP) sice vnorene odpovedi
 * v HTML maji, ale plosseni by pridalo slozitost bez jasne uzivatelske potreby zatim. */
data class ChapterComment(
    val id: String,
    val author: String,
    val content: String,
    /** Epoch millis, 0 = nezname/nepodarilo se naparsovat. */
    val createdAt: Long,
    val avatarUrl: String? = null,
)
```

Nová volitelná metoda a pole na `MangaSource` (`app/src/main/kotlin/com/haise/jiyu/source/MangaSource.kt`), za `getImageUrl`:
```kotlin
    /** Zdroj poskytuje komentare k JEDNOTLIVYM kapitolam (ne jen k titulu) - viz [supportsChapterComments].
     * Vychozi = zadny zdroj neposkytuje, appka tak nemusi zkouset stahovat komentare u zdroje,
     * ktery zadne nema. */
    val supportsChapterComments: Boolean get() = false

    /** Komentare ke KONKRETNI kapitole. Vola se az LINE, kdyz uzivatel otevre panel komentaru v
     * ctecce (viz ReaderViewModel) - NE automaticky pri otevreni kapitoly, aby appka nedelala
     * network navic u vetsiny cteni, kdy uzivatel komentare vubec neotevre. */
    suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> = emptyList()
```

## Sdílené parsery

### 1. wpDiscuz (8 zdrojů)

Ověřená struktura (živě, mangaread.org): `.wpd-comment-wrap` obal, `.wpd-comment-right` (má `id="comment-{id}"`), `.wpd-comment-author` (jméno), `.wpd-comment-date` (atribut `title` = přesný čas ve formátu `dd.MM.yyyy HH:mm`, např. `"11.08.2026 00:22"`), `.wpd-comment-text p` (text), `.wpd-avatar img[src]` (avatar).

Nový soubor `app/src/main/kotlin/com/haise/jiyu/source/comments/WpDiscuzComments.kt`:
```kotlin
package com.haise.jiyu.source.comments

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * wpDiscuz je WordPress komentarovy plugin pouzivany napric RUZNYMI sablonami webu (Madara i
 * MangaThemesia - overeno zive na 8 ruznych zdrojich, viz spec) - proto samostatna funkce,
 * ne metoda vazana na jednu tridu. `.wpd-comment-wrap` zahrnuje i vnorene odpovedi (jsou
 * DOM-potomky sveho rodicovskeho komentare) - `doc.select(...)` vrati VSECHNY urovne naraz,
 * coz je pro plochy seznam (viz ChapterComment - zadne vnorene odpovedi v v1) zamerne v poradku.
 */
fun parseWpDiscuzComments(doc: Document): List<ChapterComment> {
    val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ENGLISH)
    return doc.select("div.wpd-comment-wrap").mapNotNull { wrap ->
        val right = wrap.selectFirst(".wpd-comment-right") ?: wrap
        val author = right.selectFirst(".wpd-comment-author")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val content = right.select(".wpd-comment-text p").joinToString("\n") { it.text().trim() }.ifBlank { return@mapNotNull null }
        val id = right.attr("id").removePrefix("comment-").ifBlank { "$author:$content".hashCode().toString() }
        val createdAt = right.selectFirst(".wpd-comment-date")?.attr("title")
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
        val avatarUrl = wrap.selectFirst(".wpd-avatar img")?.attr("src")?.trim()?.ifBlank { null }
        ChapterComment(id = id, author = author, content = content, createdAt = createdAt, avatarUrl = avatarUrl)
    }
}
```

### 2. Nativní WordPress komentáře (2 zdroje)

Ověřená struktura (živě, manhuahot.com): `li.comment` (`id="comment-{id}"`), `article.comment-body`, `.comment-author` (jméno, často vnořené v `h6.heading.fn`), `.comment-content p` (text), `.comment-metadata a` (text = datum ve formátu `"MMMM d, yyyy 'at' h:mm a"`, např. `"June 24, 2026 at 6:12 am"`), `.comment-avatar img[src]` (avatar).

Nový soubor `app/src/main/kotlin/com/haise/jiyu/source/comments/NativeWpComments.kt`:
```kotlin
package com.haise.jiyu.source.comments

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/** Vestaveny (nejstarsi, pred-wpDiscuz) WordPress komentarovy system - jina struktura nez
 * wpDiscuz (viz [parseWpDiscuzComments]), proto samostatny parser. Podobne jako u wpDiscuz
 * `li.comment` zahrnuje i vnorene odpovedi (`ul.children` uvnitr) - plochy seznam je zamerny. */
fun parseNativeWpComments(doc: Document): List<ChapterComment> {
    val dateFormat = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH)
    return doc.select("li.comment").mapNotNull { li ->
        val body = li.selectFirst("article.comment-body") ?: li
        val author = body.selectFirst(".comment-author")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val content = body.select(".comment-content p").joinToString("\n") { it.text().trim() }.ifBlank { return@mapNotNull null }
        val id = li.attr("id").removePrefix("comment-").ifBlank { "$author:$content".hashCode().toString() }
        val createdAt = body.selectFirst(".comment-metadata a")?.text()?.trim()
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
        val avatarUrl = body.selectFirst(".comment-avatar img")?.attr("src")?.trim()?.ifBlank { null }
        ChapterComment(id = id, author = author, content = content, createdAt = createdAt, avatarUrl = avatarUrl)
    }
}
```

### 3. MangaReader JSON formát (2 zdroje: MangaK, Comizy)

Ověřená struktura (živě, oba zdroje bit-přesně shodné): `initialChapter.latest_comments` - pole objektů s `id` (string), `content` (string, syrový text), `user.name` (string), `created_at` (ISO 8601, např. `"2025-08-28T21:51:22.000Z"`).

Nový soubor `app/src/main/kotlin/com/haise/jiyu/source/comments/MangaReaderJsonComments.kt`:
```kotlin
package com.haise.jiyu.source.comments

import org.json.JSONObject
import java.time.Instant

/**
 * Sdilena Next.js "manga reader" platforma - overeno zive, MangaK a Comizy maji BIT-PRESNE
 * stejnou strukturu `initialChapter.latest_comments` (`id`/`content`/`user.name`/`created_at`),
 * i kdyz jde o nezavisle tridy (ruzne domeny). Zadne vnorene odpovedi - JSON dava jen
 * `replies_count`, ne obsah odpovedi.
 */
fun parseMangaReaderJsonComments(initialChapter: JSONObject): List<ChapterComment> {
    val arr = initialChapter.optJSONArray("latest_comments") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val c = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = c.optString("id").ifBlank { return@mapNotNull null }
        val content = c.optString("content").trim().ifBlank { return@mapNotNull null }
        val author = c.optJSONObject("user")?.optString("name")?.ifBlank { null } ?: "?"
        val createdAt = runCatching { Instant.parse(c.optString("created_at")).toEpochMilli() }.getOrDefault(0L)
        ChapterComment(id = id, author = author, content = content, createdAt = createdAt)
    }
}
```

## Zapojení po zdrojích

### MangaK a Comizy (JSON)

Obě třídy mají stejný JSON formát komentářů, ale liší se v tom, jak dojdou k `initialChapter` JSONu (`MangaKSource.getPageList` vs. `ComizySource.getPageList` - viz existující kód) a jestli je `chapter.url` relativní (MangaK) nebo už absolutní (Comizy, `getChapterList` tam ukládá `base + c.optString("url")`).

`MangaKSource.kt`:
```kotlin
    override val supportsChapterComments: Boolean get() = true

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                val ic = pageProps(get("$base${chapter.url}")).optJSONObject("initialChapter") ?: return@withContext emptyList()
                com.haise.jiyu.source.comments.parseMangaReaderJsonComments(ic)
            } catch (_: Exception) { emptyList() }
        }
```

`ComizySource.kt` (`chapter.url` už absolutní, a používá `nextData`+`pageProps` místo přímo `pageProps`, viz existující `getPageList` tamtéž):
```kotlin
    override val supportsChapterComments: Boolean get() = true

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                val ic = pageProps(nextData(get(chapter.url)) ?: return@withContext emptyList())
                    .getJSONObject("initialChapter")
                com.haise.jiyu.source.comments.parseMangaReaderJsonComments(ic)
            } catch (_: Exception) { emptyList() }
        }
```

### wpDiscuz zdroje - vlastní třídy (thunderscans, scythescans, lagoonscans)

Všechny tři mají vlastní `get(url): String` metodu vracející syrové HTML (viz `ThunderscansSource.kt`). Přidat do každé z nich:
```kotlin
    override val supportsChapterComments: Boolean get() = true

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                com.haise.jiyu.source.comments.parseWpDiscuzComments(Jsoup.parse(get(chapter.url)))
            } catch (_: Exception) { emptyList() }
        }
```
(Ověřeno přímo v souborech: `ScytheScansSource.kt` i `LagoonScansSource.kt` mají identickou `private fun get(url: String): String` metodu jako `ThunderscansSource.kt` - stejný vzor platí beze změny.)

### wpDiscuz a nativní WP zdroje - MadaraSource (7 zdrojů)

`MadaraSource` je JEDNA sdílená třída pro ~35+ webů, z nichž jen některé mají komentáře, a dokonce různý PLUGIN (wpDiscuz vs nativní WP) - potřeba per-instance přepínač, podobně jako dnešní `contentTypeOverride`.

V `app/src/main/kotlin/com/haise/jiyu/source/madara/MadaraSource.kt` přidat enum a pole do `MadaraSelectors`:
```kotlin
enum class MadaraCommentStyle { WPDISCUZ, NATIVE_WP }

data class MadaraSelectors(
    // ...existujici pole beze zmeny...
    /** null = zdroj (tenhle konkretni web) komentare k pripadne kapitole neposkytuje, nebo
     * pouziva Disqus (nescrapovatelny bez JS) - vetsina Madara webu. Nastavuje se explicitne
     * jen pro zive overene weby (viz SourceManager.kt). */
    val commentStyle: MadaraCommentStyle? = null,
)
```

V `MadaraSource` třídě přidat:
```kotlin
    override val supportsChapterComments: Boolean get() = selectors.commentStyle != null

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            val style = selectors.commentStyle ?: return@withContext emptyList()
            try {
                val doc = fetchDocument(chapter.url)
                when (style) {
                    MadaraCommentStyle.WPDISCUZ -> com.haise.jiyu.source.comments.parseWpDiscuzComments(doc)
                    MadaraCommentStyle.NATIVE_WP -> com.haise.jiyu.source.comments.parseNativeWpComments(doc)
                }
            } catch (_: Exception) { emptyList() }
        }
```

V `app/src/main/kotlin/com/haise/jiyu/source/SourceManager.kt` přidat `selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ)` k těmto 5 existujícím `MadaraSource(...)` voláním: `mangaread`, `wuxiaworldsite`, `mangazin`, `mangagg`, `manhuanext`; a `selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.NATIVE_WP)` k: `manhuahot`, `mangadistrict`. (Přesné aktuální volání viz `SourceManager.kt` řádky 319-731 - většina má dnes jen `contentTypeOverride`, přidání `selectors = ...` je čistě aditivní parametr navíc.)

## Líné načítání v čtečce (kdy se komentáře stahují)

Komentáře se NESTAHUJÍ automaticky při `loadChapter()` - jen když uživatel otevře panel komentářů (stejný vzor jako `translateAllPages()` - taky se nespouští automaticky). Důvod: zabránit zdvojení network nákladu na KAŽDÉM otevření kapitoly (přesně tenhle problém byl nalezen a řešen jako Important nález ve finálním review předchozí featury - fallback pro neúplnou kapitolu).

`ReaderViewModel.kt` - nový stav a funkce (vedle `_novelText`/podobných):
```kotlin
    private val _chapterComments = MutableStateFlow<List<ChapterComment>>(emptyList())
    val chapterComments: StateFlow<List<ChapterComment>> = _chapterComments.asStateFlow()

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()

    /** true, pokud AKTUALNI zdroj kapitoly komentare vubec poskytuje (viz MangaSource.
     * supportsChapterComments) - ridi, jestli se tlacitko "Komentare" v ctecce vubec zobrazi. */
    private val _commentsSupported = MutableStateFlow(false)
    val commentsSupported: StateFlow<Boolean> = _commentsSupported.asStateFlow()

    private var commentsJob: Job? = null

    fun loadChapterComments() {
        if (_chapterComments.value.isNotEmpty() || commentsJob?.isActive == true) return
        val chapter = currentChapter ?: return
        commentsJob = viewModelScope.launch {
            _commentsLoading.value = true
            try {
                _chapterComments.value = repository.getChapterComments(chapter.sourceId, chapter.url)
            } catch (e: Exception) {
                e.report("reader:loadChapterComments")
            } finally {
                _commentsLoading.value = false
            }
        }
    }
```
V `loadChapter(id: String)` DVĚ samostatná místa (pozor, `chapter` proměnná v prvním resetovacím bloku ještě NEEXISTUJE - je dostupná až po `val chapter = repository.getChapter(id) ...`, viz existující kód):

1. Za existující reset `_translatedPages.value = emptyMap()` (ještě PŘED `val chapter = repository.getChapter(id)`):
```kotlin
        _chapterComments.value = emptyList()
        commentsJob?.cancel()
        commentsJob = null
```

2. Za `currentChapter = chapter` (stejné místo, kam předchozí feature - fallback pro neúplnou kapitolu - přidala `if (chapter.isFallbackSource) { ... }`):
```kotlin
        _commentsSupported.value = repository.sourceSupportsChapterComments(chapter.sourceId)
```

Nová metoda v `MangaRepository.kt` (vedle `getChapterPages`):
```kotlin
    suspend fun getChapterComments(sourceId: String, chapterUrl: String): List<com.haise.jiyu.source.comments.ChapterComment> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getChapterComments(SChapter(sourceId, "", chapterUrl, "", 0f, 0L))
    }

    suspend fun sourceSupportsChapterComments(sourceId: String): Boolean =
        sourceManager.getById(sourceId)?.supportsChapterComments ?: false
```

## UI v čtečce

Nový button v `ReaderControls.kt`, stejný vzor jako `reader_glossary_button` (řádek ~647-656), viditelný jen `if (commentsSupported)` - přidán do `ReaderAdvancedSheetContent` vedle glosáře, s novým parametrem `onShowComments: () -> Unit` a `commentsSupported: Boolean`, protažen přes `ReaderBottomPanel` stejně jako `onShowGlossary` (řádek 449).

Nový soubor `app/src/main/kotlin/com/haise/jiyu/ui/reader/ChapterCommentsBottomSheet.kt` - kopíruje strukturu `GlossaryBottomSheet.kt` (`ModalBottomSheet`, `containerColor = Color(0xFF111B35)`), obsah listu inspirovaný `CommentRow` v `MangaDetailScreen.kt` (avatar/jméno/relativní čas/text, ale BEZ vnořených odpovědí a BEZ up/down hlasů - `ChapterComment` je nemá). Tři stavy: `commentsLoading && comments.isEmpty()` → `JiyuLoadingIndicator`; `!commentsLoading && comments.isEmpty()` → text "Tahle kapitola zatím nemá komentáře" (nový string `reader_comments_empty`); jinak seznam.

`ReaderContent.kt` (už dnes 60+ parametrů, `glossary`/`onAddGlossaryEntry`/`onRemoveGlossaryEntry` jsou přesně tenhle vzor - přidání dalších je konzistentní s existující strukturou, ne nová komplexita): nové parametry `chapterComments: List<ChapterComment> = emptyList()`, `commentsLoading: Boolean = false`, `commentsSupported: Boolean = false`, `onShowComments: () -> Unit = {}`; nový lokální stav `var showCommentsSheet by remember { mutableStateOf(false) }`; `ChapterCommentsBottomSheet` renderovaný podmíněně na konci funkce stejně jako `GlossaryBottomSheet` (řádek 342-350).

Nové stringy (4 soubory, vedle `reader_glossary_button`):

| Klíč | cs (`values`) | en | es | fr |
|---|---|---|---|---|
| `reader_comments_button` | Komentáře | Comments | Comentarios | Commentaires |
| `reader_comments_empty` | Tahle kapitola zatím nemá komentáře | This chapter has no comments yet | Este capítulo aún no tiene comentarios | Ce chapitre n'a pas encore de commentaires |

## Chybové stavy

- `getChapterComments()` selže (síť/parsing) → `try/catch` v KAŽDÉ implementaci (viz výše) vrátí `emptyList()`, appka zobrazí prázdný stav ("zatím žádné komentáře"), ne chybovou hlášku - appka nerozlišuje "web nemá komentáře" od "stažení selhalo", protože rozdíl by uživateli stejně nepomohl (nemá jak to opravit).
- `ReaderViewModel.loadChapterComments()` - chyba se nahlásí přes `e.report(...)`, ale UI dostane stejný "prázdný" stav jako výše (`_chapterComments.value` zůstane `emptyList()`).
- Zdroj, který `supportsChapterComments = false` (drtivá většina) → appka tlačítko komentářů vůbec nezobrazí, žádný network navíc.

## Testování

- **Nové JVM testy pro 3 sdílené parsery** (`app/src/test/kotlin/com/haise/jiyu/source/comments/`): `WpDiscuzCommentsTest`, `NativeWpCommentsTest`, `MangaReaderJsonCommentsTest` - každý parsuje MALOU ručně napsanou ukázku HTML/JSON (ne celou staženou stránku - jen pár komentářů s reálnou strukturou zjištěnou v tomhle specu) a ověří správné pole (author/content/createdAt/avatarUrl), prázdný vstup (žádné komentáře), a chybějící/rozbité pole (chybějící author → přeskočit ten komentář, ne spadnout).
- **Orchestrace v jednotlivých `getChapterComments()` implementacích** (síť) - stejně jako u předchozích speců není ruční/automatizované ověření prakticky proveditelné bez živého zdroje; spoléhá se na pokrytí parserů + manuální ověření na zařízení po implementaci (aspoň u MangaK, kde je appka už dřív živě testovala).

## Rozsah / co NENÍ součástí

- LikeManga - zamítnuto, komentáře jsou AJAX-only (viz "Rozsahový průzkum" výše).
- Žádné vnořené odpovědi (replies) - YAGNI, žádný ze 3 formátů nedává dost dat na to, aby to stálo za komplexitu v první verzi.
- Žádné psaní/lajkování komentářů - appka nemá napojené účty k žádnému z těchto zdrojů (stejné omezení jako u existující ComicK featury).
- Žádná proaktivní kontrola/stahování při otevření kapitoly - jen líně, na vyžádání (viz "Líné načítání").
- Zdroje s NEJASNÝM výsledkem (VortexScans, hadesscans, simplyhentai, MangaRaw4u, Silentquill/KDT Scans, Mangago) - potřebovaly by hlubší zkoumání (reverzování JS API apod.), mimo scope tohohle specu, můžou být přidány později stejným vzorem (nová sdílená funkce nebo přímo per-zdroj).
- Zdroje blokované Cloudflare při curl ověření (linkmanga, dragontea, readhunters, MangaFire, BatCave, Hachiraw, astratoons) - appka MÁ `CloudflareInterceptor`, takže by v appce mohly fungovat i tam, kde curl selhal, ale tohle nebylo ověřeno přímo v appce - mimo scope, můžou se doplnit později beze změny architektury (stejné parsery, jen přidat `commentStyle`/`supportsChapterComments = true`).
- Titul-úrovňové komentáře u zdrojů, které je mají jen tak (todaymanga, mangadenizi) - jiná kategorie featury než tenhle spec řeší.
