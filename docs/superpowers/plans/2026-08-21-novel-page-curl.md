# 3D efekt otáčení stránek (page curl) — novel i manga/manhwa reader — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Přidat appce Jiyu volitelný 3D page-curl přechod mezi stránkami (věrnost jako
Google Play Books) ovládaný JEDNÍM togglem v Nastavení čtečky, který platí jak pro novel
reader (`NovelContent.kt`, dnes nekonečný scroll → stránkovaný režim), tak pro manga/manhwa
reader v `ReadingMode.MANGA` (`MangaReader` v `ReaderPager.kt`, dnes plynulý
`HorizontalPager` swipe → curl přechod). `ReadingMode.WEBTOON` (vertikální scroll) se
togglem neřídí.

**Architecture:** Sdílené vrstvy použité ze DVOU míst: (1) čistá geometrie ohybu + gesto/stav
rozhodovací logika (`PageCurlState`, `PageCurlGeometry`), obojí JVM-testovatelné bez Compose
typů, (2) Canvas/Matrix vykreslení (`PageCurlEffect`), (3) novel-specifický čistý paginátor
(`NovelPaginator`, mangě nepotřeba - už má diskrétní stránky), (4) dvě tenké Compose
composable vrstvy propojující výše uvedené s konkrétním obsahem (`PageCurlNovelReader` pro
text, `MangaPageCurlReader` pro obrázky+bubliny, sdílející se `MangaReader` extrahovanou
`computePageGroups`/`MangaGroupContent`). Toggle VYPNUTÝ = beze změny (dnešní `LazyColumn`
scroll / dnešní `HorizontalPager` swipe) v OBOU čtečkách.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.12.01 - `GraphicsLayer.toImageBitmap()`
stabilní), `android.graphics.Canvas`/`Matrix`/`Path` pro vykreslení ohybu, JUnit4 pro
unit testy.

**Spec:** `docs/superpowers/specs/2026-08-21-novel-page-curl-design.md`

## Global Constraints

- Práce se dělá přímo na `master`, žádná feature branch (zavedená konvence projektu).
- `JAVA_HOME` musí být nastaven na `C:\Program Files\Android\Android Studio\jbr` před
  každým `./gradlew` voláním (nepřežívá mezi Bash voláními).
- Po každém tasku: `./gradlew compileDebugKotlin testDebugUnitTest`, teprve pak commit.
- Žádný task se nedotýká `NovelContent.kt`'s existujícího `LazyColumn` chování ani
  `MangaReader`'s existujícího `HorizontalPager` chování, dokud toggle není zapnutý -
  podmíněné větve se jen PŘIDÁVAJÍ, nic se needstraňuje z dnešní cesty.
- Nové stringy (cs/en/es/fr) - cs je zdrojový jazyk appky, ostatní 3 překlady stejným
  stylem jako existující `settings_reader_preload_novel_*` klíče.
- Čistá logika (Task 2, 3, 4, 7) NESMÍ importovat žádný `androidx.compose.*` typ - projekt
  nemá v `app/src/test` ani jeden existující import z Compose (ověřeno), pravděpodobně
  záměrně kvůli JVM-testovatelnosti bez Robolectricu (viz [[project_jiyu_audit_2026_08]]
  "past s Robolectric" v paměti). Vlastní `Point` data class místo `Offset` v testovatelných
  souborech, převod na `Offset` až na hranici s Compose vrstvou.
- Jeden sdílený toggle (`pageCurlEnabled`) pro OBĚ čtečky - žádný task nevytváří druhý,
  manga-specifický přepínač. `PageCurlState` (Task 3) je generická třída bez vztahu k
  textu - používají ji Task 6 (novel) i Task 9 (manga) nad různým `pageCount`.
- `drawPageCurl` (Task 5) se VŽDY volá s reálnou `revealedPageBitmap` (rasterizovaná
  sousední stránka/skupina), nikdy s `null`, s výjimkou okamžiku, kdy sousední stránka
  ještě neexistuje (hranice kapitoly) - viz oprava v Task 6 a implementace v Task 9.

---

### Task 1: Nastavení — sdílený toggle "Použití 3D efektu při otáčení stránek"

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/settings/SettingsRepository.kt` (přidat klíč
  do `SettingsKeys` objektu ~řádek 50, a `Flow`/setter ~řádek 486, za `cropBorders`)
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/settings/SettingsViewModel.kt` (passthrough
  ~řádek 498, za `preloadNextNovelChapter`)
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/settings/ReaderSettingsScreen.kt` (nová
  `SettingsSection` + `SettingsToggleRow`, PŘED existující `SettingsSection(preload...)`
  sekcí na řádku ~235)
- Modify: `app/src/main/res/values/strings.xml`, `values-en/strings.xml`,
  `values-es/strings.xml`, `values-fr/strings.xml`

**Interfaces:**
- Produces: `SettingsRepository.pageCurlEnabled: Flow<Boolean>` (default `false`),
  `SettingsRepository.setPageCurlEnabled(enabled: Boolean): suspend Unit` — Task 6
  (novel, `ReaderScreen.kt`/`NovelContent.kt`) i Task 9 (manga, `ReaderContent.kt`) na
  tohle navazují stejným `ReaderViewModel.pageCurlEnabled: StateFlow<Boolean>`.
  JEDEN toggle řídí OBĚ čtečky - žádný task dál v plánu nevytváří druhý přepínač.

- [ ] **Step 1: Přidat klíč do `SettingsKeys`**

V `app/src/main/kotlin/com/haise/jiyu/settings/SettingsRepository.kt`, do objektu
`SettingsKeys` (za řádek `val PRELOAD_NEXT_CHAPTER_WIFI_ONLY = ...`, řádek 50):

```kotlin
    val PAGE_CURL_ENABLED      = booleanPreferencesKey("page_curl_enabled")
```

- [ ] **Step 2: Přidat Flow + setter do `SettingsRepository`**

Za blok `cropBorders` (řádek 481-486):

```kotlin
    /** Výchozí false - stránkovaný 3D page-curl přechod (novel reader) / curl přechod
     * mezi stránkami (manga/manhwa reader v ReadingMode.MANGA) místo dnešního scrollu/swipu.
     * Netýká se ReadingMode.WEBTOON (vertikální scroll nemá diskrétní stránky). */
    val pageCurlEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.PAGE_CURL_ENABLED] ?: false }

    suspend fun setPageCurlEnabled(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.PAGE_CURL_ENABLED] = enabled }
```

- [ ] **Step 3: Přidat passthrough do `SettingsViewModel`**

Za blok `preloadNextNovelChapter` (řádek 494-498) v
`app/src/main/kotlin/com/haise/jiyu/ui/settings/SettingsViewModel.kt`:

```kotlin
    // ── 3D efekt otáčení stránek (novel i manga/manhwa reader) ────────────────
    val pageCurlEnabled: StateFlow<Boolean> = settings.pageCurlEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPageCurlEnabled(enabled: Boolean) = viewModelScope.launch { settings.setPageCurlEnabled(enabled) }
```

- [ ] **Step 4: Přidat stringy (4 jazyky)**

`app/src/main/res/values/strings.xml`, za `settings_reader_preload_novel_desc`:

```xml
    <string name="settings_reader_page_curl_section_title">Otáčení stránek</string>
    <string name="settings_reader_page_curl_title">Použití 3D efektu při otáčení stránek</string>
    <string name="settings_reader_page_curl_desc">Stránka se otočí ohybovou animací jako u knihy místo plynulého scrollu/swipu. Platí pro light novel čtečku i pro manga/manhwa čtení po stránkách (ne pro webtoon plynulý scroll)</string>
```

`app/src/main/res/values-en/strings.xml`, na stejné místo:

```xml
    <string name="settings_reader_page_curl_section_title">Page turning</string>
    <string name="settings_reader_page_curl_title">3D page-turn effect</string>
    <string name="settings_reader_page_curl_desc">Pages turn with a book-like curling animation instead of a plain scroll/swipe. Applies to the light novel reader and to paged manga/manhwa reading (not to webtoon continuous scroll)</string>
```

`app/src/main/res/values-es/strings.xml`:

```xml
    <string name="settings_reader_page_curl_section_title">Cambio de página</string>
    <string name="settings_reader_page_curl_title">Efecto 3D al pasar página</string>
    <string name="settings_reader_page_curl_desc">La página se pasa con una animación de curvatura como un libro, en vez de un desplazamiento/deslizamiento plano. Se aplica al lector de novelas ligeras y a la lectura paginada de manga/manhwa (no al desplazamiento continuo de webtoon)</string>
```

`app/src/main/res/values-fr/strings.xml`:

```xml
    <string name="settings_reader_page_curl_section_title">Changement de page</string>
    <string name="settings_reader_page_curl_title">Effet 3D lors du changement de page</string>
    <string name="settings_reader_page_curl_desc">La page se tourne avec une animation de courbure comme un livre, au lieu d'un défilement/balayage plat. S'applique au lecteur de light novels et à la lecture paginée manga/manhwa (pas au défilement continu webtoon)</string>
```

- [ ] **Step 5: Přidat toggle do `ReaderSettingsScreen`**

V `app/src/main/kotlin/com/haise/jiyu/ui/settings/ReaderSettingsScreen.kt`, přidat state
čtení za řádek 70 (`val preloadNextNovelChapter by ...`):

```kotlin
    val pageCurlEnabled    by viewModel.pageCurlEnabled.collectAsState()
```

A novou sekci PŘED `SettingsSection(title = stringResource(R.string.settings_reader_preload_section_title))`
(řádek 235):

```kotlin
                // ── Otáčení stránek (novel + manga/manhwa) ────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_page_curl_section_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_page_curl_title),
                        description = stringResource(R.string.settings_reader_page_curl_desc),
                        checked = pageCurlEnabled,
                        onCheckedChange = { viewModel.setPageCurlEnabled(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))

```

- [ ] **Step 6: Ověřit sestavení**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/settings/SettingsRepository.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/settings/SettingsViewModel.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/settings/ReaderSettingsScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml
git commit -m "feat: pridat sdileny toggle 3D efektu otaceni stranek do Nastaveni ctecky"
```

---

### Task 2: NovelPaginator — dělení textu kapitoly na stránky

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelPaginator.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/ui/reader/NovelPaginatorTest.kt`

**Interfaces:**
- Produces: `data class NovelPage(val startIndex: Int, val endIndex: Int)`,
  `fun interface TextLayoutProvider { fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo> }`,
  `data class LineInfo(val endIndex: Int, val heightPx: Float)`,
  `fun paginateNovelText(text: String, textLayoutProvider: TextLayoutProvider, availableWidthPx: Float, availableHeightPx: Float, fontSizeSp: Float): List<NovelPage>`,
  `fun findPageIndexForOffset(pages: List<NovelPage>, offset: Int): Int`,
  `class ComposeTextLayoutProvider(textMeasurer: TextMeasurer, baseStyle: TextStyle) : TextLayoutProvider` —
  Task 7 tohle použije k reálnému měření textu na obrazovce.

- [ ] **Step 1: Napsat failing testy**

Vytvořit `app/src/test/kotlin/com/haise/jiyu/ui/reader/NovelPaginatorTest.kt`:

```kotlin
package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelPaginatorTest {

    /** Deterministická fake - každý řádek má pevnou výšku a zalamuje po pevném počtu znaků,
     * bez závislosti na reálném font-shapingu Androidu. */
    private class FixedWidthFakeLayoutProvider(
        private val charsPerLine: Int,
        private val lineHeightPx: Float,
    ) : TextLayoutProvider {
        override fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo> {
            if (text.isEmpty()) return emptyList()
            return text.indices.step(charsPerLine).map { start ->
                val end = (start + charsPerLine).coerceAtMost(text.length)
                LineInfo(endIndex = end, heightPx = lineHeightPx)
            }
        }
    }

    @Test
    fun `empty text produces a single empty page`() {
        val pages = paginateNovelText(
            text = "",
            textLayoutProvider = FixedWidthFakeLayoutProvider(10, 20f),
            availableWidthPx = 500f, availableHeightPx = 1000f, fontSizeSp = 16f,
        )
        assertEquals(listOf(NovelPage(0, 0)), pages)
    }

    @Test
    fun `text shorter than one page stays on a single page`() {
        val text = "a".repeat(25)
        val pages = paginateNovelText(
            text = text,
            textLayoutProvider = FixedWidthFakeLayoutProvider(charsPerLine = 10, lineHeightPx = 20f),
            availableWidthPx = 500f, availableHeightPx = 1000f, fontSizeSp = 16f,
        )
        assertEquals(listOf(NovelPage(0, 25)), pages)
    }

    @Test
    fun `text is split across multiple pages when it exceeds the height budget`() {
        val text = "a".repeat(250)
        val pages = paginateNovelText(
            text = text,
            textLayoutProvider = FixedWidthFakeLayoutProvider(charsPerLine = 10, lineHeightPx = 20f),
            availableWidthPx = 500f, availableHeightPx = 200f, fontSizeSp = 16f,
        )
        assertEquals(3, pages.size)
        assertEquals(NovelPage(0, 100), pages[0])
        assertEquals(NovelPage(100, 200), pages[1])
        assertEquals(NovelPage(200, 250), pages[2])
        assertEquals(text.length, pages.last().endIndex)
    }

    @Test
    fun `a single line taller than the page still gets its own page instead of looping forever`() {
        val hugeLineProvider = TextLayoutProvider { text, _, _ ->
            listOf(LineInfo(endIndex = text.length, heightPx = 5000f))
        }
        val pages = paginateNovelText(
            text = "krátký text s obřím fontem",
            textLayoutProvider = hugeLineProvider,
            availableWidthPx = 500f, availableHeightPx = 1000f, fontSizeSp = 200f,
        )
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startIndex)
        assertEquals("krátký text s obřím fontem".length, pages[0].endIndex)
    }

    @Test
    fun `findPageIndexForOffset locates the page containing a character offset`() {
        val pages = listOf(NovelPage(0, 100), NovelPage(100, 200), NovelPage(200, 250))
        assertEquals(0, findPageIndexForOffset(pages, 0))
        assertEquals(0, findPageIndexForOffset(pages, 99))
        assertEquals(1, findPageIndexForOffset(pages, 100))
        assertEquals(2, findPageIndexForOffset(pages, 249))
        assertEquals(2, findPageIndexForOffset(pages, 250))
    }

    @Test
    fun `findPageIndexForOffset on empty pages list returns 0`() {
        assertEquals(0, findPageIndexForOffset(emptyList(), 0))
    }
}
```

- [ ] **Step 2: Ověřit, že testy selžou (soubor `NovelPaginator.kt` ještě neexistuje)**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — `unresolved reference: NovelPage` (a další)

- [ ] **Step 3: Implementovat `NovelPaginator.kt`**

```kotlin
package com.haise.jiyu.ui.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/** Jedna stránka kapitoly - rozsah znaků do PŮVODNÍHO textu kapitoly, ne kopie textu. */
data class NovelPage(val startIndex: Int, val endIndex: Int)

/** Jeden zalomený řádek textu - kde končí (exclusive index) a jak je vysoký v px. */
data class LineInfo(val endIndex: Int, val heightPx: Float)

/**
 * Abstrakce nad zalomením textu na řádky. Produkční implementace ([ComposeTextLayoutProvider])
 * obalí Compose [TextMeasurer] (potřebuje reálný font-resolving engine, nejde spustit v čistém
 * JVM testu bez Robolectricu). V testech nahrazena deterministickou fake implementací.
 */
fun interface TextLayoutProvider {
    fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo>
}

/**
 * Rozseká předem zalomené řádky do stránek podle výškového rozpočtu [availableHeightPx].
 * Řádek, který sám o sobě přesahuje [availableHeightPx] (extrémně velký font), dostane
 * vlastní stránku místo nekonečného čekání na místo, které nikdy nepřijde.
 */
fun packLinesIntoPages(lines: List<LineInfo>, availableHeightPx: Float): List<NovelPage> {
    if (lines.isEmpty()) return listOf(NovelPage(0, 0))

    val pages = mutableListOf<NovelPage>()
    var pageStart = 0
    var heightUsed = 0f

    lines.forEachIndexed { i, line ->
        val lineStart = if (i == 0) 0 else lines[i - 1].endIndex
        val wouldExceed = heightUsed + line.heightPx > availableHeightPx
        if (wouldExceed && heightUsed > 0f) {
            pages += NovelPage(pageStart, lineStart)
            pageStart = lineStart
            heightUsed = 0f
        }
        heightUsed += line.heightPx
    }
    pages += NovelPage(pageStart, lines.last().endIndex)
    return pages
}

/**
 * Rozseká [text] na stránky, které se vejdou do [availableWidthPx] x [availableHeightPx]
 * při dané velikosti fontu. Jediné volání [TextLayoutProvider.layoutLines] - řádkování se
 * počítá jednou, ne opakovaně po stránkách.
 */
fun paginateNovelText(
    text: String,
    textLayoutProvider: TextLayoutProvider,
    availableWidthPx: Float,
    availableHeightPx: Float,
    fontSizeSp: Float,
): List<NovelPage> {
    if (text.isEmpty()) return listOf(NovelPage(0, 0))
    val lines = textLayoutProvider.layoutLines(text, availableWidthPx, fontSizeSp)
    return packLinesIntoPages(lines, availableHeightPx)
}

/**
 * Najde index stránky obsahující znak [offset] - použito pro zachování pozice čtenáře
 * při repaginaci (změna fontu, otočení obrazovky, ...).
 */
fun findPageIndexForOffset(pages: List<NovelPage>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    val idx = pages.indexOfFirst { offset >= it.startIndex && offset < it.endIndex }
    if (idx >= 0) return idx
    return pages.lastIndex
}

/** Reálná implementace [TextLayoutProvider] přes Compose [TextMeasurer] - běží jen na
 * zařízení/emulátoru (potřebuje reálný font-resolving engine Androidu). */
class ComposeTextLayoutProvider(
    private val textMeasurer: TextMeasurer,
    private val baseStyle: TextStyle,
) : TextLayoutProvider {
    override fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo> {
        if (text.isEmpty()) return emptyList()
        val style = baseStyle.copy(fontSize = fontSizeSp.sp)
        val result: TextLayoutResult = textMeasurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = availableWidthPx.toInt().coerceAtLeast(1)),
        )
        return (0 until result.lineCount).map { i ->
            LineInfo(
                endIndex = result.getLineEnd(i),
                heightPx = result.getLineBottom(i) - result.getLineTop(i),
            )
        }
    }
}
```

- [ ] **Step 4: Spustit testy a ověřit, že projdou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.haise.jiyu.ui.reader.NovelPaginatorTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 6 testů zelených

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelPaginator.kt \
        app/src/test/kotlin/com/haise/jiyu/ui/reader/NovelPaginatorTest.kt
git commit -m "feat: pridat NovelPaginator - deleni textu kapitoly na stranky"
```

---

### Task 3: PageCurlState — sdílená rozhodovací logika gesta a otáčení

**Poznámka k přejmenování:** Původní návrh nazýval tuhle třídu `NovelPageCurlState`.
Nic z ní ještě není napsáno v kódu, a logika je čistě generická (stránka/skupina + počet
+ míra ohybu, bez vztahu k textu) - přejmenováno na `PageCurlState`, protože ji teď
používá i manga reader (Task 9) nad `groups.size` místo počtu textových stránek.

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlState.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/ui/reader/PageCurlStateTest.kt`

**Interfaces:**
- Consumes: nic z předchozích tasků (nezávislé).
- Produces: `enum class TurnDirection { NEXT, PREV }`, `data class PageCurlState(val currentPageIndex: Int, val pageCount: Int, val dragProgress: Float = 0f)`,
  `sealed class PageTurnResult` (`WithinChapter(newState)`, `ChapterBoundary(direction)`, `Cancelled(newState)`),
  `fun PageCurlState.withDrag(deltaProgress: Float): PageCurlState`,
  `fun PageCurlState.onDragEnd(completionThreshold: Float = 0.4f): PageTurnResult`,
  `fun PageCurlState.onEdgeTap(direction: TurnDirection): PageTurnResult` —
  Task 6 (`PageCurlNovelReader`) a Task 9 (`MangaPageCurlReader`) na tyhle funkce naváží
  přímo z `pointerInput` handlerů, oba nezávisle nad vlastním `pageCount`.

- [ ] **Step 1: Napsat failing testy**

Vytvořit `app/src/test/kotlin/com/haise/jiyu/ui/reader/PageCurlStateTest.kt`:

```kotlin
package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlStateTest {

    @Test
    fun `dragging past the threshold and releasing advances to the next page`() {
        val state = PageCurlState(currentPageIndex = 2, pageCount = 5)
        val dragged = state.withDrag(0.6f)
        val result = dragged.onDragEnd(completionThreshold = 0.4f)
        assertTrue(result is PageTurnResult.WithinChapter)
        assertEquals(3, (result as PageTurnResult.WithinChapter).newState.currentPageIndex)
        assertEquals(0f, result.newState.dragProgress)
    }

    @Test
    fun `dragging below the threshold and releasing cancels back to flat`() {
        val state = PageCurlState(currentPageIndex = 2, pageCount = 5)
        val dragged = state.withDrag(0.2f)
        val result = dragged.onDragEnd(completionThreshold = 0.4f)
        assertTrue(result is PageTurnResult.Cancelled)
        assertEquals(0f, (result as PageTurnResult.Cancelled).newState.dragProgress)
        assertEquals(2, result.newState.currentPageIndex)
    }

    @Test
    fun `completing a turn on the last page of the chapter reports a chapter boundary, not a page change`() {
        val state = PageCurlState(currentPageIndex = 4, pageCount = 5)
        val result = state.withDrag(0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), result)
    }

    @Test
    fun `completing a turn on the first page toward prev reports a chapter boundary`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 5)
        val result = state.withDrag(-0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), result)
    }

    @Test
    fun `dragging past the chapter boundary produces no curl progress`() {
        val lastPage = PageCurlState(currentPageIndex = 4, pageCount = 5)
        assertEquals(0f, lastPage.withDrag(0.7f).dragProgress)

        val firstPage = PageCurlState(currentPageIndex = 0, pageCount = 5)
        assertEquals(0f, firstPage.withDrag(-0.7f).dragProgress)
    }

    @Test
    fun `a single-page chapter reports a chapter boundary immediately on edge tap without any drag`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 1)
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), state.onEdgeTap(TurnDirection.NEXT))
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), state.onEdgeTap(TurnDirection.PREV))
    }

    @Test
    fun `edge tap works the same as a completed drag without needing prior drag state`() {
        val state = PageCurlState(currentPageIndex = 1, pageCount = 5)
        val result = state.onEdgeTap(TurnDirection.NEXT)
        assertEquals(PageTurnResult.WithinChapter(PageCurlState(2, 5, 0f)), result)
    }
}
```

- [ ] **Step 2: Ověřit, že testy selžou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — `unresolved reference: PageCurlState`

- [ ] **Step 3: Implementovat `PageCurlState.kt`**

```kotlin
package com.haise.jiyu.ui.reader

enum class TurnDirection { NEXT, PREV }

/**
 * Stav ohybu stránky - na které stránce/skupině jsme, kolik jich celkem je, a jak moc je
 * aktuálně "ohnutá" (0f = plochá, ±1f = plně otočená). Čistá immutable data třída bez
 * závislosti na Compose/gestech - testovatelná přímo. Sdílená mezi novel readerem
 * (pageCount = počet textových stránek z paginátoru) a manga readerem (pageCount =
 * groups.size, viz Task 7) - ničím jinak specifická.
 */
data class PageCurlState(
    val currentPageIndex: Int,
    val pageCount: Int,
    val dragProgress: Float = 0f, // -1f..1f: zaporne = ohyb k PREV, kladne = k NEXT
)

/** Výsledek gesta - buď změna stránky uvnitř kapitoly, přechod na jinou KAPITOLU (hranice),
 * nebo zrušení (vráceno zpět naplocho). */
sealed class PageTurnResult {
    data class WithinChapter(val newState: PageCurlState) : PageTurnResult()
    data class ChapterBoundary(val direction: TurnDirection) : PageTurnResult()
    data class Cancelled(val newState: PageCurlState) : PageTurnResult()
}

/** Průběžný tah prstem - aktualizuje míru ohybu, NEMĚNÍ currentPageIndex (to se děje až
 * po puštění). Na hranici kapitoly (první/poslední stránka) se ohyb tím směrem nepovolí -
 * hranici řeší až [onDragEnd]/[onEdgeTap], aby prázdná animace nikdy neproběhla. */
fun PageCurlState.withDrag(deltaProgress: Float): PageCurlState {
    val atFirstPage = currentPageIndex == 0
    val atLastPage = currentPageIndex == pageCount - 1
    val clamped = when {
        deltaProgress < 0f && atFirstPage -> 0f
        deltaProgress > 0f && atLastPage -> 0f
        else -> deltaProgress.coerceIn(-1f, 1f)
    }
    return copy(dragProgress = clamped)
}

/**
 * Rozhodne, co se stane po puštění prstu. Přesažení [completionThreshold] dokončí obrat,
 * jinak se stránka vrátí naplocho. Na hranici kapitoly dokončený obrat vrátí
 * [PageTurnResult.ChapterBoundary] místo změny currentPageIndex - volající pak zavolá
 * existující onNext()/onPrev() (novel) nebo onNavigateNextChapter()/onNavigatePrevChapter()
 * (manga).
 */
fun PageCurlState.onDragEnd(completionThreshold: Float = 0.4f): PageTurnResult {
    val magnitude = kotlin.math.abs(dragProgress)
    if (magnitude < completionThreshold) {
        return PageTurnResult.Cancelled(copy(dragProgress = 0f))
    }
    val direction = if (dragProgress > 0f) TurnDirection.NEXT else TurnDirection.PREV
    return completeTurn(direction)
}

/** Ťuknutí na okraj obrazovky = stejný výsledek jako dokončený tah, bez postupného ohybu -
 * proto funguje i pro jednostránkovou kapitolu, kde [withDrag] nikdy žádný ohyb nepovolí. */
fun PageCurlState.onEdgeTap(direction: TurnDirection): PageTurnResult = completeTurn(direction)

private fun PageCurlState.completeTurn(direction: TurnDirection): PageTurnResult {
    val atFirstPage = currentPageIndex == 0
    val atLastPage = currentPageIndex == pageCount - 1
    return when {
        direction == TurnDirection.PREV && atFirstPage -> PageTurnResult.ChapterBoundary(TurnDirection.PREV)
        direction == TurnDirection.NEXT && atLastPage -> PageTurnResult.ChapterBoundary(TurnDirection.NEXT)
        direction == TurnDirection.NEXT -> PageTurnResult.WithinChapter(
            copy(currentPageIndex = currentPageIndex + 1, dragProgress = 0f),
        )
        else -> PageTurnResult.WithinChapter(
            copy(currentPageIndex = currentPageIndex - 1, dragProgress = 0f),
        )
    }
}
```

- [ ] **Step 4: Spustit testy a ověřit, že projdou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.haise.jiyu.ui.reader.PageCurlStateTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 7 testů zelených

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlState.kt \
        app/src/test/kotlin/com/haise/jiyu/ui/reader/PageCurlStateTest.kt
git commit -m "feat: pridat PageCurlState - sdilena rozhodovaci logika gesta otaceni stranek"
```

---

### Task 4: PageCurlGeometry — geometrie ohybu (polygon clip + zrcadlová matice)

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlGeometry.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/ui/reader/PageCurlGeometryTest.kt`

**Interfaces:**
- Consumes: nic z předchozích tasků (nezávislé).
- Produces: `data class Point(val x: Float, val y: Float)`,
  `data class PageCurlGeometry(val corner: Point, val dragPoint: Point, val foldEdgeA: Point, val foldEdgeB: Point, val flatRegion: List<Point>, val curledRegion: List<Point>, val progress: Float)`,
  `fun computePageCurlGeometry(corner: Point, dragPoint: Point, pageWidth: Float, pageHeight: Float): PageCurlGeometry`,
  `data class ReflectionMatrixCoefficients(val scaleX: Float, val skewX: Float, val transX: Float, val skewY: Float, val scaleY: Float, val transY: Float)`,
  `fun computeReflectionAcross(lineStart: Point, lineEnd: Point): ReflectionMatrixCoefficients`,
  `fun ReflectionMatrixCoefficients.apply(p: Point): Point` —
  Task 5 (`PageCurlEffect.kt`) tohle spotřebuje a převede `Point`→`Offset`/`android.graphics.Matrix`
  na hranici s Canvas vykreslením.

- [ ] **Step 1: Napsat failing testy**

Vytvořit `app/src/test/kotlin/com/haise/jiyu/ui/reader/PageCurlGeometryTest.kt`:

```kotlin
package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageCurlGeometryTest {

    @Test
    fun `dragging the corner straight across produces a fold line near the page center`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f),
            dragPoint = Point(150f, 400f),
            pageWidth = 300f, pageHeight = 400f,
        )
        val foldMidX = (geometry.foldEdgeA.x + geometry.foldEdgeB.x) / 2f
        assertTrue("fold by mel byt kolem stredu mezi rohem a prstem", abs(foldMidX - 225f) < 5f)
    }

    @Test
    fun `dragging exactly to the opposite corner reaches nearly full progress`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f),
            dragPoint = Point(0f, 0f),
            pageWidth = 300f, pageHeight = 400f,
        )
        assertEquals(1f, geometry.progress, 0.15f)
    }

    @Test
    fun `dragging past the opposite corner is clamped, not extrapolated further`() {
        val withinBounds = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(0f, 0f),
            pageWidth = 300f, pageHeight = 400f,
        )
        val overshooting = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(-500f, -500f),
            pageWidth = 300f, pageHeight = 400f,
        )
        assertEquals(withinBounds.progress, overshooting.progress, 0.2f)
    }

    @Test
    fun `flat and curled regions together cover all four rectangle corners`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(200f, 350f),
            pageWidth = 300f, pageHeight = 400f,
        )
        val rectCorners = setOf(Point(0f, 0f), Point(300f, 0f), Point(300f, 400f), Point(0f, 400f))
        val covered = (geometry.flatRegion + geometry.curledRegion).toSet()
        rectCorners.forEach { corner ->
            assertTrue("roh $corner musi byt bud v ploche, nebo v ohybane casti", corner in covered)
        }
    }

    @Test
    fun `no drag (finger at the corner) yields zero progress`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(300f, 400f),
            pageWidth = 300f, pageHeight = 400f,
        )
        assertEquals(0f, geometry.progress, 0.01f)
    }

    @Test
    fun `reflecting a point across a horizontal line flips only the Y coordinate`() {
        val coeffs = computeReflectionAcross(Point(0f, 100f), Point(500f, 100f))
        val reflected = coeffs.apply(Point(50f, 150f))
        assertEquals(50f, reflected.x, 0.01f)
        assertEquals(50f, reflected.y, 0.01f)
    }

    @Test
    fun `reflecting a point across a vertical line flips only the X coordinate`() {
        val coeffs = computeReflectionAcross(Point(200f, 0f), Point(200f, 500f))
        val reflected = coeffs.apply(Point(250f, 80f))
        assertEquals(150f, reflected.x, 0.01f)
        assertEquals(80f, reflected.y, 0.01f)
    }

    @Test
    fun `reflecting twice returns the original point`() {
        val coeffs = computeReflectionAcross(Point(10f, 20f), Point(300f, 250f))
        val once = coeffs.apply(Point(70f, 45f))
        val twice = coeffs.apply(once)
        assertEquals(70f, twice.x, 0.05f)
        assertEquals(45f, twice.y, 0.05f)
    }

    @Test
    fun `a point exactly on the reflection line stays put`() {
        val coeffs = computeReflectionAcross(Point(0f, 0f), Point(100f, 100f))
        val reflected = coeffs.apply(Point(50f, 50f))
        assertEquals(50f, reflected.x, 0.01f)
        assertEquals(50f, reflected.y, 0.01f)
    }
}
```

- [ ] **Step 2: Ověřit, že testy selžou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — `unresolved reference: Point`

- [ ] **Step 3: Implementovat `PageCurlGeometry.kt`**

```kotlin
package com.haise.jiyu.ui.reader

import kotlin.math.hypot

/** Prostý 2D bod - záměrně NE Compose `Offset`, aby tahle část šla testovat v čistém
 * JVM testu bez závislosti na Android/Compose runtime. */
data class Point(val x: Float, val y: Float) {
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun times(scalar: Float) = Point(x * scalar, y * scalar)
    fun distanceTo(other: Point): Float = hypot((x - other.x).toDouble(), (y - other.y).toDouble()).toFloat()
}

/**
 * Geometrie ohybu stránky pro dané tažení. Fold linie prochází středem mezi rohem stránky
 * ([corner]) a pozicí prstu ([dragPoint]), kolmo na spojnici rohu a prstu - klasický
 * "single-corner curl" (stejný princip jako běžné page-curl knihovny na Androidu/iOS).
 */
data class PageCurlGeometry(
    val corner: Point,
    val dragPoint: Point,
    /** Body na okraji stránky, kde fold linie protíná hranici. */
    val foldEdgeA: Point,
    val foldEdgeB: Point,
    /** Vrcholy plochě zůstávající části stránky (opačná strana od [corner]). */
    val flatRegion: List<Point>,
    /** Vrcholy ohýbané části stránky (strana s [corner]). */
    val curledRegion: List<Point>,
    /** 0f (prst u rohu, žádný ohyb) .. 1f (prst u protějšího rohu, plně otočeno). */
    val progress: Float,
)

/**
 * Spočítá geometrii ohybu pro stránku o rozměrech [pageWidth] x [pageHeight], kdy uživatel
 * táhne roh [corner] směrem k [dragPoint]. [dragPoint] se nejdřív ořízne, aby nešel dál
 * než na opačnou stranu stránky (brání degenerované geometrii při přetažení mimo).
 */
fun computePageCurlGeometry(
    corner: Point,
    dragPoint: Point,
    pageWidth: Float,
    pageHeight: Float,
): PageCurlGeometry {
    val maxDistance = hypot(pageWidth.toDouble(), pageHeight.toDouble()).toFloat() * 1.05f
    val toDrag = dragPoint - corner
    val rawDistance = corner.distanceTo(dragPoint)
    val clampedDrag = when {
        rawDistance == 0f -> corner
        rawDistance > maxDistance -> corner + toDrag * (maxDistance / rawDistance)
        else -> dragPoint
    }

    val mid = Point((corner.x + clampedDrag.x) / 2f, (corner.y + clampedDrag.y) / 2f)
    val axis = clampedDrag - corner
    val foldDir = Point(-axis.y, axis.x)

    val rectCorners = listOf(
        Point(0f, 0f), Point(pageWidth, 0f), Point(pageWidth, pageHeight), Point(0f, pageHeight),
    )
    fun side(p: Point): Float = foldDir.x * (p.y - mid.y) - foldDir.y * (p.x - mid.x)
    val cornerSign = side(corner).sign()

    val curled = mutableListOf<Point>()
    val flat = mutableListOf<Point>()
    val edgeIntersections = mutableListOf<Point>()

    for (i in rectCorners.indices) {
        val a = rectCorners[i]
        val b = rectCorners[(i + 1) % rectCorners.size]
        val sideA = side(a)
        val onCornerSide = sideA.sign() == cornerSign || sideA == 0f
        (if (onCornerSide) curled else flat).add(a)

        val sideB = side(b)
        val crosses = (sideA > 0f && sideB < 0f) || (sideA < 0f && sideB > 0f)
        if (crosses) {
            val t = sideA / (sideA - sideB)
            val intersection = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            curled.add(intersection)
            flat.add(intersection)
            edgeIntersections.add(intersection)
        }
    }

    val (foldEdgeA, foldEdgeB) = if (edgeIntersections.size >= 2) {
        edgeIntersections[0] to edgeIntersections[1]
    } else {
        corner to corner
    }

    val progress = (rawDistance.coerceAtMost(maxDistance) / maxDistance).coerceIn(0f, 1f)

    return PageCurlGeometry(corner, clampedDrag, foldEdgeA, foldEdgeB, flat, curled, progress)
}

private fun Float.sign(): Float = when {
    this > 0f -> 1f
    this < 0f -> -1f
    else -> 0f
}

/** Šest koeficientů affinní matice zrcadlení (bez závislosti na `android.graphics.Matrix`,
 * aby vzorec šel testovat v čistém JVM testu). Pořadí odpovídá `Matrix.setValues()`. */
data class ReflectionMatrixCoefficients(
    val scaleX: Float, val skewX: Float, val transX: Float,
    val skewY: Float, val scaleY: Float, val transY: Float,
)

/** Odvodí matici zrcadlení bodů přes přímku danou body [lineStart]/[lineEnd] - použito
 * na vykreslení "rubu" ohýbané stránky (viz [PageCurlGeometry.curledRegion]). */
fun computeReflectionAcross(lineStart: Point, lineEnd: Point): ReflectionMatrixCoefficients {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val lenSq = dx * dx + dy * dy
    if (lenSq < 0.0001f) {
        return ReflectionMatrixCoefficients(1f, 0f, 0f, 0f, 1f, 0f)
    }
    val a = (dx * dx - dy * dy) / lenSq
    val b = 2 * dx * dy / lenSq
    val d = -a
    return ReflectionMatrixCoefficients(
        scaleX = a, skewX = b, transX = lineStart.x - a * lineStart.x - b * lineStart.y,
        skewY = b, scaleY = d, transY = lineStart.y - b * lineStart.x - d * lineStart.y,
    )
}

fun ReflectionMatrixCoefficients.apply(p: Point): Point =
    Point(scaleX * p.x + skewX * p.y + transX, skewY * p.x + scaleY * p.y + transY)
```

- [ ] **Step 4: Spustit testy a ověřit, že projdou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.haise.jiyu.ui.reader.PageCurlGeometryTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 9 testů zelených

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlGeometry.kt \
        app/src/test/kotlin/com/haise/jiyu/ui/reader/PageCurlGeometryTest.kt
git commit -m "feat: pridat PageCurlGeometry - geometrie ohybu a zrcadlova matice"
```

---

### Task 5: PageCurlEffect — Canvas vykreslení ohybu

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlEffect.kt`

**Interfaces:**
- Consumes: `PageCurlGeometry`, `Point`, `computeReflectionAcross`/`.apply()` (Task 4).
- Produces: `fun DrawScope.drawPageCurl(geometry: PageCurlGeometry, currentPageBitmap: ImageBitmap, revealedPageBitmap: ImageBitmap?)` —
  Task 7 (composable) tohle zavolá uvnitř `Canvas { ... }` bloku.

**Poznámka k testování:** Tahle vrstva kreslí do reálného `android.graphics.Canvas`
(`DrawScope.drawContext.canvas.nativeCanvas`) - Compose samo nemá API na `Matrix`
transformace a `Path` clip na téhle úrovni. Matematika, která by se dala zkazit
(zrcadlová matice), je už otestovaná v Task 4 - tenhle soubor je čistě "zavolej Android
Canvas API se správnými čísly", bez vlastní logiky k unit testování. Vizuální správnost
se ověří v Task 8 ručně na emulátoru.

- [ ] **Step 1: Implementovat `PageCurlEffect.kt`**

```kotlin
package com.haise.jiyu.ui.reader

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.hypot

/**
 * Vykreslí aktuální stránku s ohybem podle [geometry]. [revealedPageBitmap] je stránka,
 * která se odkrývá pod ohybem (null na hranici kapitoly, kdy sousední stránka ještě
 * neexistuje jako bitmapa).
 */
fun DrawScope.drawPageCurl(
    geometry: PageCurlGeometry,
    currentPageBitmap: ImageBitmap,
    revealedPageBitmap: ImageBitmap?,
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val toOffset = { p: Point -> Offset(p.x, p.y) }

    revealedPageBitmap?.let {
        nativeCanvas.drawBitmap(it.asAndroidBitmap(), 0f, 0f, null)
    }

    val flatPath = polygonPath(geometry.flatRegion.map(toOffset))
    nativeCanvas.save()
    nativeCanvas.clipPath(flatPath.asAndroidPath())
    nativeCanvas.drawBitmap(currentPageBitmap.asAndroidBitmap(), 0f, 0f, null)
    nativeCanvas.restore()

    drawFoldShadow(nativeCanvas, geometry)

    val curledPath = polygonPath(geometry.curledRegion.map(toOffset))
    nativeCanvas.save()
    nativeCanvas.clipPath(curledPath.asAndroidPath())
    val reflection = computeReflectionAcross(geometry.foldEdgeA, geometry.foldEdgeB)
    val matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                reflection.scaleX, reflection.skewX, reflection.transX,
                reflection.skewY, reflection.scaleY, reflection.transY,
                0f, 0f, 1f,
            ),
        )
    }
    val dimPaint = Paint().apply { alpha = 217 } // ~0.85 - simuluje o neco tmavsi rub papiru
    nativeCanvas.drawBitmap(currentPageBitmap.asAndroidBitmap(), matrix, dimPaint)
    nativeCanvas.restore()

    drawFoldHighlight(nativeCanvas, geometry)
}

private fun polygonPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    close()
}

/** Stín na plochém okraji podél linie ohybu - simuluje zvednutý papír vrhající stín na
 * stránku pod sebou. */
private fun drawFoldShadow(canvas: android.graphics.Canvas, geometry: PageCurlGeometry) {
    val a = geometry.foldEdgeA
    val b = geometry.foldEdgeB
    val shadowWidth = 40f * geometry.progress.coerceIn(0.1f, 1f)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().takeIf { it > 0f } ?: 1f
    val normalX = -dy / len
    val normalY = dx / len

    val paint = Paint().apply {
        shader = LinearGradient(
            a.x, a.y,
            a.x - normalX * shadowWidth, a.y - normalY * shadowWidth,
            intArrayOf(0x66000000.toInt(), 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
    }
    val path = android.graphics.Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(b.x - normalX * shadowWidth, b.y - normalY * shadowWidth)
        lineTo(a.x - normalX * shadowWidth, a.y - normalY * shadowWidth)
        close()
    }
    canvas.drawPath(path, paint)
}

/** Zvýraznění na špičce ohybu (světlo odrážející se od zakřiveného papíru u prstu). */
private fun drawFoldHighlight(canvas: android.graphics.Canvas, geometry: PageCurlGeometry) {
    val tip = geometry.dragPoint
    val radius = 60f * geometry.progress.coerceAtLeast(0.05f)
    val paint = Paint().apply {
        shader = RadialGradient(
            tip.x, tip.y, radius,
            intArrayOf(0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(tip.x, tip.y, radius, paint)
}
```

- [ ] **Step 2: Ověřit kompilaci**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL` (žádné automatizované testy pro tenhle soubor - viz poznámka výše;
vizuální ověření je Task 8)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlEffect.kt
git commit -m "feat: pridat PageCurlEffect - Canvas vykresleni ohybu stranky"
```

---

### Task 6: PageCurlNovelReader — composable propojující gesta, rasterizaci a vykreslení

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlNovelReader.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelContent.kt` (podmíněná větev)
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt` (nový `StateFlow`)
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt` (předání parametru)

**Interfaces:**
- Consumes: `NovelPage`/`paginateNovelText`/`findPageIndexForOffset`/`ComposeTextLayoutProvider`
  (Task 2), `PageCurlState`/`TurnDirection`/`PageTurnResult`/`.withDrag()`/`.onDragEnd()`/`.onEdgeTap()`
  (Task 3), `Point`/`computePageCurlGeometry` (Task 4), `drawPageCurl` (Task 5).
- Produces: `@Composable fun PageCurlNovelReader(text: String, fontSize: Float, lineSpacing: Float, textColor: Color, bgColor: Color, onChapterBoundary: (TurnDirection) -> Unit)`

**Poznámka:** Tenhle task propojuje VŠECHNY předchozí vrstvy poprvé dohromady - přesná
souhra `pointerInput` gest, časování rasterizace stránky do bitmapy přes `GraphicsLayer`
(musí doběhnout dřív, než uživatel začne tahat) a finální vizuální ladění (šířka stínu,
práh dokončení tahu) se doladí až při ručním testu na zařízení v Task 10. Kód níže je
funkční výchozí bod, ne finální pixel-perfect podoba.

**Oprava oproti původnímu návrhu (viz spec, sekce "Architektura" bod 2):** kód níže
rasterizuje i SOUSEDNÍ stránku (tu, co se odkrývá pod ohybem) a předává ji jako
`revealedPageBitmap`, místo aby vždycky posílal `null` - jinak by se pod ohýbanou částí
neukázal žádný text, jen pozadí, což neodpovídá referenčním screenshotům.

- [ ] **Step 1: Implementovat `PageCurlNovelReader.kt`**

```kotlin
package com.haise.jiyu.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PageCurlNovelReader(
    text: String,
    fontSize: Float,
    lineSpacing: Float,
    textColor: Color,
    bgColor: Color,
    onChapterBoundary: (TurnDirection) -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(bgColor)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val baseStyle = TextStyle(color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
        val layoutProvider = remember(textMeasurer) { ComposeTextLayoutProvider(textMeasurer, baseStyle) }

        var pages by remember { mutableStateOf(listOf(NovelPage(0, 0))) }
        var curlState by remember { mutableStateOf(PageCurlState(0, 1)) }

        LaunchedEffect(text, fontSize, lineSpacing, widthPx, heightPx) {
            val previousOffset = pages.getOrNull(curlState.currentPageIndex)?.startIndex ?: 0
            val newPages = paginateNovelText(text, layoutProvider, widthPx, heightPx, fontSize)
            val newIndex = findPageIndexForOffset(newPages, previousOffset)
            pages = newPages
            curlState = PageCurlState(currentPageIndex = newIndex, pageCount = newPages.size)
        }

        val currentPage = pages[curlState.currentPageIndex.coerceIn(pages.indices)]
        val currentPageText = text.substring(currentPage.startIndex, currentPage.endIndex)

        val currentLayer = rememberGraphicsLayer()
        var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    currentLayer.record { this@drawWithContent.drawContent() }
                    drawContent()
                }
                .padding(20.dp),
        ) {
            Text(text = currentPageText, color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
        }

        LaunchedEffect(currentPage, fontSize, lineSpacing, textColor, widthPx, heightPx) {
            currentBitmap = currentLayer.toImageBitmap()
        }

        // Sousední stránka, kterou tah odkrývá pod ohybem - NEXT při kladném dragProgress,
        // PREV při záporném. Rasterizuje se do vlastní vrstvy, ale NEKRESLÍ se přímo na
        // obrazovku (chybí koncové `drawContent()`) - jinak by prosvítala i v klidu (dragProgress
        // == 0f), překrytá přes aktuální stránku.
        val revealedPageIndex = when {
            curlState.dragProgress > 0f -> curlState.currentPageIndex + 1
            curlState.dragProgress < 0f -> curlState.currentPageIndex - 1
            else -> null
        }
        val revealedPage = revealedPageIndex?.let { pages.getOrNull(it) }
        val revealedLayer = rememberGraphicsLayer()
        var revealedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    revealedLayer.record { this@drawWithContent.drawContent() }
                    // Zámerně BEZ drawContent() - tahle vrstva se jen rasterizuje pro
                    // revealedBitmap, na obrazovku samu o sobě nekreslí nic.
                }
                .padding(20.dp),
        ) {
            if (revealedPage != null) {
                val revealedText = text.substring(revealedPage.startIndex, revealedPage.endIndex)
                Text(text = revealedText, color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
            }
        }

        LaunchedEffect(revealedPage, fontSize, lineSpacing, textColor, widthPx, heightPx) {
            revealedBitmap = if (revealedPage != null) revealedLayer.toImageBitmap() else null
        }

        fun applyTurnResult(result: PageTurnResult) {
            when (result) {
                is PageTurnResult.WithinChapter -> curlState = result.newState
                is PageTurnResult.Cancelled -> curlState = result.newState
                is PageTurnResult.ChapterBoundary -> {
                    curlState = curlState.copy(dragProgress = 0f)
                    onChapterBoundary(result.direction)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(curlState.pageCount) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val deltaProgress = dragAmount.x / widthPx
                            curlState = curlState.withDrag(curlState.dragProgress + deltaProgress)
                        },
                        onDragEnd = { applyTurnResult(curlState.onDragEnd()) },
                    )
                }
                .pointerInput(curlState.pageCount) {
                    detectTapGestures(
                        onTap = { offset ->
                            val direction = when {
                                offset.x < widthPx * 0.15f -> TurnDirection.PREV
                                offset.x > widthPx * 0.85f -> TurnDirection.NEXT
                                else -> null
                            }
                            direction?.let { applyTurnResult(curlState.onEdgeTap(it)) }
                        },
                    )
                },
        ) {
            val bitmap = currentBitmap
            if (bitmap != null && curlState.dragProgress != 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val corner = if (curlState.dragProgress > 0f) Offset(widthPx, heightPx) else Offset(0f, heightPx)
                    val fingerOffset = Offset(
                        x = corner.x - curlState.dragProgress * widthPx,
                        y = corner.y,
                    )
                    val geometry = computePageCurlGeometry(
                        corner = Point(corner.x, corner.y),
                        dragPoint = Point(fingerOffset.x, fingerOffset.y),
                        pageWidth = widthPx, pageHeight = heightPx,
                    )
                    drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
            val percent = (curlState.currentPageIndex + 1) * 100 / pages.size.coerceAtLeast(1)
            Text(
                text = "Stránka ${curlState.currentPageIndex + 1} z ${pages.size} · $percent%",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}
```

- [ ] **Step 2: Přidat `StateFlow` do `ReaderViewModel`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt`, za blok `oledMode`
(řádek 179-180), stejný vzor. Sdílené jak novel readerem (Task 6), tak manga readerem
(Task 9) - jeden `StateFlow` pro obě čtečky:

```kotlin
    val pageCurlEnabled: StateFlow<Boolean> = settings.pageCurlEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

- [ ] **Step 3: Napojit do `ReaderScreen.kt`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt`, přidat čtení state (za
řádek 95, `val oledMode by viewModel.oledMode.collectAsState()`):

```kotlin
    val pageCurlEnabled by viewModel.pageCurlEnabled.collectAsState()
```

A předat do volání `NovelContent` (za `onRemoveGlossaryEntry`, řádek 235):

```kotlin
                pageCurlEnabled = pageCurlEnabled,
```

(Task 9 přidá stejný `pageCurlEnabled` parametr i do volání `ReaderContent` o pár řádků
výš ve stejném souboru - `NovelContent`/`ReaderContent` jsou volané ze stejné obrazovky,
`pageCurlEnabled` z `viewModel` se čte JEDNOU a předává na obě místa.)

- [ ] **Step 4: Přepnout `NovelContent.kt` na podmíněnou větev**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelContent.kt` přidat nový parametr
`pageCurlEnabled: Boolean = false` do signatury `NovelContent` (za `onRemoveGlossaryEntry`,
řádek 74), a obalit existující `LazyColumn` blok (řádky 228-254) podmínkou:

```kotlin
        if (pageCurlEnabled) {
            PageCurlNovelReader(
                text = displayText,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                textColor = textColor,
                bgColor = bgColor,
                onChapterBoundary = { direction ->
                    if (direction == TurnDirection.NEXT) onNext() else onPrev()
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(paragraphs) { paragraph: String ->
                    Text(
                        text = paragraph,
                        color = textColor,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp,
                        modifier = Modifier.padding(bottom = (fontSize * 0.75f).dp),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        Arrangement.SpaceBetween,
                    ) {
                        if (hasPrev) {
                            TextButton(onClick = onPrev) { Text(stringResource(R.string.reader_prev_novel), color = Color(0xFF34D1BF)) }
                        } else { Spacer(Modifier) }
                        if (hasNext) {
                            TextButton(onClick = onNext) { Text(stringResource(R.string.reader_next_novel), color = Color(0xFF34D1BF)) }
                        }
                    }
                }
            }
        }
```

(Vypnutí toggle uprostřed čtení tím pádem funguje samo - příští recompozice prostě
vykreslí `LazyColumn` větev místo `PageCurlNovelReader`, žádný extra kód není potřeba.)

- [ ] **Step 5: Ověřit sestavení**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/PageCurlNovelReader.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelContent.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt
git commit -m "feat: propojit PageCurlNovelReader do NovelContent za novy toggle"
```

---

### Task 7: Extrakce sdílených kusů z `MangaReaderu` (`computePageGroups`, `MangaGroupContent`, `SharePageBottomSheet`)

**Cíl tasku:** ČISTÝ REFAKTORING beze změny chování — připravit `ReaderPager.kt` tak, aby
Task 8 (`MangaPageCurlReader`) mohl znovupoužít stránkovací/vykreslovací/sdílecí logiku,
místo aby ji psal podruhé. Po tomhle tasku musí `MangaReader` fungovat úplně stejně jako
dnes (žádný pozorovatelný rozdíl v chování).

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderPager.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/ui/reader/MangaPageGroupsTest.kt` (nový)

**Interfaces:**
- Produces: `fun computePageGroups(pageCount: Int, useSpread: Boolean, spreadPageIndices: Set<Int>): List<List<Int>>`,
  `@Composable fun MangaGroupContent(indices: List<Int>, pages: List<String>, translateMode: Boolean, translatedPages: Map<Int, List<TranslatedBlock>>, reverseLayout: Boolean, resolvedContentScale: ContentScale, cropBorders: Boolean, textScale: Float, flippedBubbles: Set<String>, onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit, onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit)`,
  `@Composable fun SharePageBottomSheet(pageUrl: String, onDismiss: () -> Unit)`,
  `internal suspend fun saveBitmapToGallery(context: android.content.Context, url: String)` (dřív `private`) —
  Task 8 (`MangaPageCurlReader.kt`) tohle všechno přímo použije.

- [ ] **Step 1: Napsat failing testy pro `computePageGroups`**

Vytvořit `app/src/test/kotlin/com/haise/jiyu/ui/reader/MangaPageGroupsTest.kt`:

```kotlin
package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaPageGroupsTest {

    @Test
    fun `without spread every page is its own group`() {
        val groups = computePageGroups(pageCount = 4, useSpread = false, spreadPageIndices = emptySet())
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), groups)
    }

    @Test
    fun `with spread pages pair up two at a time`() {
        val groups = computePageGroups(pageCount = 4, useSpread = true, spreadPageIndices = emptySet())
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), groups)
    }

    @Test
    fun `an odd page count leaves the last group as a single page`() {
        val groups = computePageGroups(pageCount = 5, useSpread = true, spreadPageIndices = emptySet())
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), groups)
    }

    @Test
    fun `a page forced solo by spreadPageIndices breaks the pairing around it`() {
        // Stránka 1 je sirsi-nez-vyssi (napr. rozlozeny obrazek) - nesmi se parovat.
        val groups = computePageGroups(pageCount = 5, useSpread = true, spreadPageIndices = setOf(1))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), groups)
    }

    @Test
    fun `empty page list produces no groups`() {
        assertEquals(emptyList<List<Int>>(), computePageGroups(pageCount = 0, useSpread = true, spreadPageIndices = emptySet()))
        assertEquals(emptyList<List<Int>>(), computePageGroups(pageCount = 0, useSpread = false, spreadPageIndices = emptySet()))
    }
}
```

- [ ] **Step 2: Ověřit, že testy selžou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — `unresolved reference: computePageGroups`

- [ ] **Step 3: Extrahovat `computePageGroups` a napojit ho do `MangaReaderu`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderPager.kt` přidat jako top-level
funkci (mimo `MangaReader`, např. hned pod `OffsetSaver`):

```kotlin
/**
 * Rozdělí [pageCount] stránek do skupin - bez spreadu je každá stránka vlastní skupina,
 * se spreadem se párují po dvou, KROMĚ stránek v [spreadPageIndices] (širší-než-vyšší
 * obrázky, #29 fix), které zůstávají samy. Čistá funkce vytažená z `MangaReader` pro
 * JVM testovatelnost a znovupoužití v [MangaPageCurlReader].
 */
fun computePageGroups(pageCount: Int, useSpread: Boolean, spreadPageIndices: Set<Int>): List<List<Int>> {
    if (!useSpread) {
        return (0 until pageCount).map { listOf(it) }
    }
    val result = mutableListOf<List<Int>>()
    var i = 0
    while (i < pageCount) {
        if (i in spreadPageIndices) {
            result.add(listOf(i)); i++
        } else if (i + 1 < pageCount && (i + 1) !in spreadPageIndices) {
            result.add(listOf(i, i + 1)); i += 2
        } else {
            result.add(listOf(i)); i++
        }
    }
    return result
}
```

V `MangaReader` nahradit existující blok (`ReaderPager.kt:127-144`, `val groups: List<List<Int>> = remember(...) { ... if (!useSpread) { ... } else { ... } }`)
za:

```kotlin
    val groups: List<List<Int>> = remember(pages.size, useSpread, spreadPageIndices) {
        computePageGroups(pages.size, useSpread, spreadPageIndices)
    }
```

- [ ] **Step 4: Spustit testy a ověřit, že projdou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.haise.jiyu.ui.reader.MangaPageGroupsTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 testů zelených

- [ ] **Step 5: Extrahovat `MangaGroupContent`**

V `ReaderPager.kt`, extrahovat obsah `HorizontalPager` content lambdy (`ReaderPager.kt:336-431`
- celý `if (indices.size == 1) { ... } else { ... }` blok, BEZ vnějšího
`pointerInput`/`BoxWithConstraints` z `MangaReaderu` - ty zůstávají v `MangaReaderu`) do
nové top-level `@Composable`:

```kotlin
@Composable
fun MangaGroupContent(
    indices: List<Int>,
    pages: List<String>,
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    reverseLayout: Boolean,
    resolvedContentScale: ContentScale,
    cropBorders: Boolean,
    textScale: Float,
    flippedBubbles: Set<String>,
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit,
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit,
) {
    if (indices.size == 1) {
        var intrinsicSize by remember(pages[indices[0]]) { mutableStateOf<Size?>(null) }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight
            Box(modifier = Modifier.fillMaxSize()) {
                RetryableAsyncImage(
                    url = pages[indices[0]],
                    contentDescription = stringResource(R.string.reader_page_content_desc, indices[0] + 1),
                    contentScale = resolvedContentScale,
                    cropBorders = cropBorders,
                    modifier = Modifier.fillMaxSize(),
                    onImageSize = { intrinsicSize = it },
                )
                if (translateMode) {
                    val blocks = translatedPages[indices[0]]
                    if (!blocks.isNullOrEmpty()) {
                        val imageRect = remember(intrinsicSize, containerWidth, containerHeight, resolvedContentScale) {
                            intrinsicSize?.let {
                                imageDisplayRect(it, Size(containerWidth.value, containerHeight.value), resolvedContentScale)
                            } ?: Rect(0f, 0f, containerWidth.value, containerHeight.value)
                        }
                        BubbleOverlayLayer(
                            blocks = blocks,
                            imageRect = imageRect,
                            textScale = textScale,
                            pageIndex = indices[0],
                            pageUrl = pages[indices[0]],
                            flippedBubbles = flippedBubbles,
                            onToggleFlip = onToggleBubbleFlip,
                            onEditBubble = onEditBubble,
                        )
                    }
                }
            }
        }
    } else {
        val ordered = if (reverseLayout) indices.reversed() else indices
        Row(modifier = Modifier.fillMaxSize()) {
            ordered.forEach { idx ->
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) {
                    var pageIntrinsicSize by remember(pages[idx]) { mutableStateOf<Size?>(null) }
                    RetryableAsyncImage(
                        url = pages[idx],
                        contentDescription = stringResource(R.string.reader_page_content_desc, idx + 1),
                        contentScale = resolvedContentScale,
                        modifier = Modifier.fillMaxSize(),
                        onImageSize = { pageIntrinsicSize = it },
                    )
                    if (translateMode) {
                        val blocks = translatedPages[idx]
                        if (!blocks.isNullOrEmpty()) {
                            val imageRect = remember(pageIntrinsicSize, maxWidth, maxHeight, resolvedContentScale) {
                                pageIntrinsicSize?.let {
                                    imageDisplayRect(it, Size(maxWidth.value, maxHeight.value), resolvedContentScale)
                                } ?: Rect(0f, 0f, maxWidth.value, maxHeight.value)
                            }
                            BubbleOverlayLayer(
                                blocks = blocks,
                                imageRect = imageRect,
                                textScale = textScale,
                                pageIndex = idx,
                                pageUrl = pages[idx],
                                flippedBubbles = flippedBubbles,
                                onToggleFlip = onToggleBubbleFlip,
                                onEditBubble = onEditBubble,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

**Poznámka o zachování dnešní (ne)symetrie:** `cropBorders` se v původním kódu předává
`RetryableAsyncImage` JEN ve větvi s jednou stránkou, ne ve spread/`Row` větvi - to je
zachováno beze změny (není to tenhle task, kdo by měl tuhle nesrovnalost tiše opravovat).

`MangaReader`'s `HorizontalPager` content lambda (uvnitř `pointerInput`/`graphicsLayer`
obálky, kterou si `MangaReader` ponechává) se zjednoduší na volání:

```kotlin
                MangaGroupContent(
                    indices = indices,
                    pages = pages,
                    translateMode = translateMode,
                    translatedPages = translatedPages,
                    reverseLayout = reverseLayout,
                    resolvedContentScale = resolvedContentScale,
                    cropBorders = cropBorders,
                    textScale = textScale,
                    flippedBubbles = flippedBubbles,
                    onToggleBubbleFlip = onToggleBubbleFlip,
                    onEditBubble = onEditBubble,
                )
```

- [ ] **Step 6: Extrahovat `SharePageBottomSheet` a zpřístupnit `saveBitmapToGallery`**

V `ReaderPager.kt` extrahovat `ModalBottomSheet` blok (`ReaderPager.kt:149-184`) do nové
top-level `@Composable`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePageBottomSheet(pageUrl: String, onDismiss: () -> Unit) {
    val saveContext = androidx.compose.ui.platform.LocalContext.current
    val saveScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.reader_share_page_chooser), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedButton(
                onClick = { onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.6f)),
            ) {
                Icon(TablerIcons.Share, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reader_share_link), color = Color(0xFF4FC3F7))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    saveScope.launch { saveBitmapToGallery(saveContext, pageUrl) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.6f)),
            ) {
                Icon(TablerIcons.DeviceFloppy, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reader_save_to_gallery), color = Color(0xFF8B5CF6))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
```

**Poznámka:** Původní kód volal `onSharePage(sharePageUrl)` na "Sdílet odkaz" tlačítku
(`ReaderPager.kt:159`) - to je ale hodnota parametru `onSharePage`, kterou `MangaReader`
nikdy nevyplňuje (defaultní `{}` - viz `ReaderContent.kt`, které `MangaReaderu` nepředává
`onSharePage`). Zjednodušeno na přímé `onDismiss()`, beze změny pozorovatelného chování
(tlačítko dřív taky nic neudělalo, jen zavřelo sheet).

Změnit `private suspend fun saveBitmapToGallery` na `internal suspend fun saveBitmapToGallery`
(řádek 437) - beze změny těla funkce.

`MangaReader` nahradí svůj inline `if (showShareSheet) { ... }` blok (řádky 149-184) za:

```kotlin
    if (showShareSheet) {
        SharePageBottomSheet(pageUrl = sharePageUrl, onDismiss = { showShareSheet = false })
    }
```

(Proměnné `saveContext`/`saveScope`/`sheetState`, dřív používané jen tímhle blokem, se z
`MangaReaderu` odstraní - žijou teď uvnitř `SharePageBottomSheet`.)

- [ ] **Step 7: Ověřit sestavení**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL` — `MangaReader` se chová beze změny (žádný nový toggle na něj
zatím nesahá), jen je teď poskládaný ze sdílených kusů.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderPager.kt \
        app/src/test/kotlin/com/haise/jiyu/ui/reader/MangaPageGroupsTest.kt
git commit -m "refactor: extrahovat computePageGroups/MangaGroupContent/SharePageBottomSheet z MangaReaderu"
```

---

### Task 8: `MangaPageCurlReader` — curl přechod pro manga/manhwa reader + zapojení do `ReaderContent`

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/MangaPageCurlReader.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderContent.kt` (podmíněná větev)

**Interfaces:**
- Consumes: `computePageGroups`/`MangaGroupContent`/`SharePageBottomSheet`/`saveBitmapToGallery`
  (Task 7), `PageCurlState`/`TurnDirection`/`PageTurnResult`/`.withDrag()`/`.onDragEnd()`/`.onEdgeTap()`
  (Task 3), `Point`/`computePageCurlGeometry` (Task 4), `drawPageCurl` (Task 5),
  `OffsetSaver` (privátní `val` v `ReaderPager.kt`, stejný soubor/balíček - zpřístupnit
  jako `internal` místo `private`, pokud implementátor zjistí, že `MangaPageCurlReader`
  žije v JINÉM souboru než `ReaderPager.kt` a potřebuje k němu přístup).
- Produces: `@Composable fun MangaPageCurlReader(...)` se STEJNOU signaturou parametrů
  jako dnešní `MangaReader` (viz `ReaderPager.kt:74-100`) - žádný nový/chybějící
  parametr, aby `ReaderContent.kt` mohl volat oba zaměnitelně.

**Poznámka:** Stejně jako Task 6 - tenhle task propojuje curl vrstvu (Task 3/4/5) s
manga obsahem (Task 7) poprvé dohromady. Finální vizuální/gesto ladění (práh dokončení
tahu, souhra zoom↔curl) se doladí při ručním testu na zařízení v Task 9. Kód níže je
funkční výchozí bod.

**Řešení souběhů (viz spec, sekce "Manga reader — reálné souběhy"):**
- **Zoom vs. curl:** gesto otáčení (drag/tap) se vloží do modifier řetězce jen
  `if (scale <= 1f)` - stejná sémantika jako dnešní `userScrollEnabled = scale <= 1f`.
  `detectTransformGestures` (pinch) běží vždy, nezávisle.
- **Spread:** `groups` (z `computePageGroups`) obsahuje páry jako dnes - curl vrstva o
  tom neví, rasterizuje celou skupinu (1 nebo 2 stránky) jako jednu bitmapu.
- **Reset zoomu při otočení stránky:** `scale`/`panOffset` se vrátí na `1f`/`Offset.Zero`
  vždy, když se `curlState.currentPageIndex` změní (stejně jako dnešní `handlePageChanged`).

- [ ] **Step 1: Implementovat `MangaPageCurlReader.kt`**

```kotlin
package com.haise.jiyu.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.haise.jiyu.translate.TranslatedBlock
import kotlinx.coroutines.delay

@Composable
fun MangaPageCurlReader(
    pages: List<String>,
    initialPage: Int,
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    reverseLayout: Boolean,
    doublePageSpread: Boolean,
    spreadPageIndices: Set<Int> = emptySet(),
    textScale: Float,
    tapZonesEnabled: Boolean,
    tapZoneGrid: TapZoneGrid = TapZoneGrid(),
    onPageChanged: (Int) -> Unit,
    onShowPanel: () -> Unit,
    onNavigatePrevChapter: () -> Unit = {},
    onNavigateNextChapter: () -> Unit = {},
    onSharePage: (String) -> Unit = {},
    pageScale: String = "fit_width",
    jumpToPage: Int? = null,
    onJumpConsumed: () -> Unit = {},
    autoNextChapter: Boolean = false,
    onAutoNextChapter: () -> Unit = {},
    cropBorders: Boolean = false,
    volumeKeysNav: Boolean = true,
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
) {
    var scale by rememberSaveable { mutableStateOf(1f) }
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    val resolvedContentScale = when (pageScale) {
        "fit_height" -> ContentScale.FillHeight
        "fit_screen" -> ContentScale.Fit
        "stretch"    -> ContentScale.FillBounds
        else         -> ContentScale.FillWidth
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useSpread = doublePageSpread && isLandscape

    var showShareSheet by remember { mutableStateOf(false) }
    var sharePageUrl by remember { mutableStateOf("") }
    if (showShareSheet) {
        SharePageBottomSheet(pageUrl = sharePageUrl, onDismiss = { showShareSheet = false })
    }

    var currentSingleIndex by rememberSaveable { mutableStateOf(initialPage) }

    key(useSpread) {
        val groups = remember(pages.size, useSpread, spreadPageIndices) {
            computePageGroups(pages.size, useSpread, spreadPageIndices)
        }
        val initialGroupIndex = remember(groups) {
            groups.indexOfFirst { currentSingleIndex in it }.coerceAtLeast(0)
        }
        var curlState by remember {
            mutableStateOf(
                PageCurlState(
                    currentPageIndex = initialGroupIndex.coerceIn(0, groups.lastIndex.coerceAtLeast(0)),
                    pageCount = groups.size,
                ),
            )
        }

        var reachedEndManually by remember { mutableStateOf(false) }
        LaunchedEffect(curlState.currentPageIndex, groups.size) {
            val newSingleIndex = groups.getOrNull(curlState.currentPageIndex)?.firstOrNull()
            if (newSingleIndex != null && newSingleIndex != currentSingleIndex) {
                scale = 1f
                panOffset = Offset.Zero
            }
            if (newSingleIndex != null) currentSingleIndex = newSingleIndex
            onPageChanged(currentSingleIndex)
            if (groups.size > 1 && curlState.currentPageIndex < groups.size - 1) reachedEndManually = true
            if (reachedEndManually && groups.isNotEmpty() && curlState.currentPageIndex == groups.size - 1 && autoNextChapter) {
                delay(2500)
                if (curlState.currentPageIndex == groups.size - 1) onAutoNextChapter()
            }
        }

        LaunchedEffect(jumpToPage) {
            val target = jumpToPage ?: return@LaunchedEffect
            val groupIdx = groups.indexOfFirst { target in it }.coerceAtLeast(0)
                .coerceIn(0, groups.lastIndex.coerceAtLeast(0))
            curlState = curlState.copy(currentPageIndex = groupIdx, dragProgress = 0f)
            onJumpConsumed()
        }

        fun applyTurnResult(result: PageTurnResult) {
            when (result) {
                is PageTurnResult.WithinChapter -> curlState = result.newState
                is PageTurnResult.Cancelled -> curlState = result.newState
                is PageTurnResult.ChapterBoundary -> {
                    curlState = curlState.copy(dragProgress = 0f)
                    if (result.direction == TurnDirection.NEXT) onNavigateNextChapter() else onNavigatePrevChapter()
                }
            }
        }

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) { }
        }

        fun tryTurn(direction: TurnDirection) {
            if (scale <= 1f) applyTurnResult(curlState.onEdgeTap(direction))
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.A -> { tryTurn(if (reverseLayout) TurnDirection.NEXT else TurnDirection.PREV); true }
                        Key.DirectionRight, Key.D -> { tryTurn(if (reverseLayout) TurnDirection.PREV else TurnDirection.NEXT); true }
                        Key.VolumeDown -> if (volumeKeysNav) { tryTurn(if (reverseLayout) TurnDirection.PREV else TurnDirection.NEXT); true } else false
                        Key.VolumeUp -> if (volumeKeysNav) { tryTurn(if (reverseLayout) TurnDirection.NEXT else TurnDirection.PREV); true } else false
                        else -> false
                    }
                },
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val currentIndices = groups.getOrElse(curlState.currentPageIndex.coerceIn(groups.indices.ifEmpty { 0..0 })) { listOf(0) }

            val currentLayer = rememberGraphicsLayer()
            var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        currentLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = panOffset.x, translationY = panOffset.y,
                    ),
            ) {
                MangaGroupContent(
                    indices = currentIndices, pages = pages, translateMode = translateMode,
                    translatedPages = translatedPages, reverseLayout = reverseLayout,
                    resolvedContentScale = resolvedContentScale, cropBorders = cropBorders,
                    textScale = textScale, flippedBubbles = flippedBubbles,
                    onToggleBubbleFlip = onToggleBubbleFlip, onEditBubble = onEditBubble,
                )
            }
            LaunchedEffect(currentIndices, pages, translateMode, translatedPages, widthPx, heightPx) {
                currentBitmap = currentLayer.toImageBitmap()
            }

            val revealedGroupIndex = when {
                curlState.dragProgress > 0f -> curlState.currentPageIndex + 1
                curlState.dragProgress < 0f -> curlState.currentPageIndex - 1
                else -> null
            }
            val revealedIndices = revealedGroupIndex?.let { groups.getOrNull(it) }
            val revealedLayer = rememberGraphicsLayer()
            var revealedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        revealedLayer.record { this@drawWithContent.drawContent() }
                        // bez drawContent() - jen rasterizace pro revealedBitmap
                    },
            ) {
                if (revealedIndices != null) {
                    MangaGroupContent(
                        indices = revealedIndices, pages = pages, translateMode = translateMode,
                        translatedPages = translatedPages, reverseLayout = reverseLayout,
                        resolvedContentScale = resolvedContentScale, cropBorders = cropBorders,
                        textScale = textScale, flippedBubbles = flippedBubbles,
                        onToggleBubbleFlip = onToggleBubbleFlip, onEditBubble = onEditBubble,
                    )
                }
            }
            LaunchedEffect(revealedIndices, pages, translateMode, translatedPages, widthPx, heightPx) {
                revealedBitmap = if (revealedIndices != null) revealedLayer.toImageBitmap() else null
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (scale <= 1f) {
                            Modifier
                                .pointerInput(curlState.pageCount, reverseLayout) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val delta = (if (reverseLayout) -dragAmount.x else dragAmount.x) / widthPx
                                            curlState = curlState.withDrag(curlState.dragProgress - delta)
                                        },
                                        onDragEnd = { applyTurnResult(curlState.onDragEnd()) },
                                    )
                                }
                                .pointerInput(tapZonesEnabled, tapZoneGrid, reverseLayout, curlState.pageCount) {
                                    detectTapGestures(
                                        onLongPress = {
                                            sharePageUrl = pages.getOrElse(currentIndices[0]) { "" }
                                            if (sharePageUrl.isNotEmpty()) showShareSheet = true
                                        },
                                        onTap = { offset ->
                                            val action = if (!tapZonesEnabled) {
                                                TapZoneAction.SHOW_PANEL
                                            } else {
                                                val col = (offset.x / size.width * 3).toInt().coerceIn(0, 2)
                                                val row = (offset.y / size.height * 3).toInt().coerceIn(0, 2)
                                                tapZoneGrid[row, col]
                                            }
                                            when (action) {
                                                TapZoneAction.SHOW_PANEL -> onShowPanel()
                                                TapZoneAction.PREV_PAGE -> tryTurn(if (reverseLayout) TurnDirection.NEXT else TurnDirection.PREV)
                                                TapZoneAction.NEXT_PAGE -> tryTurn(if (reverseLayout) TurnDirection.PREV else TurnDirection.NEXT)
                                                TapZoneAction.PREV_CHAPTER -> onNavigatePrevChapter()
                                                TapZoneAction.NEXT_CHAPTER -> onNavigateNextChapter()
                                                TapZoneAction.NONE -> {}
                                            }
                                        },
                                    )
                                }
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) panOffset += pan else panOffset = Offset.Zero
                        }
                    },
            ) {
                val bitmap = currentBitmap
                if (bitmap != null && curlState.dragProgress != 0f) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val corner = if (curlState.dragProgress > 0f) Offset(widthPx, heightPx) else Offset(0f, heightPx)
                        val fingerOffset = Offset(x = corner.x - curlState.dragProgress * widthPx, y = corner.y)
                        val geometry = computePageCurlGeometry(
                            corner = Point(corner.x, corner.y),
                            dragPoint = Point(fingerOffset.x, fingerOffset.y),
                            pageWidth = widthPx, pageHeight = heightPx,
                        )
                        drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Napojit `pageCurlEnabled` do `ReaderContent.kt`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderContent.kt` přidat nový parametr
`pageCurlEnabled: Boolean = false` do signatury `ReaderContent` (za `onDeviceWarningText`,
řádek 115), a obalit `if (readingMode == ReadingMode.WEBTOON) { ... } else { MangaReader(...) }`
(řádky 156-205) tak, aby `else` větev volila mezi `MangaReader`/`MangaPageCurlReader`:

```kotlin
        if (readingMode == ReadingMode.WEBTOON) {
            WebtoonReader(
                // beze změny, viz řádky 157-177
            )
        } else if (pageCurlEnabled) {
            MangaPageCurlReader(
                pages = pages,
                initialPage = initialPage,
                translateMode = effectiveTranslateMode,
                translatedPages = translatedPages,
                reverseLayout = reverseLayout,
                doublePageSpread = doublePageSpread,
                spreadPageIndices = spreadPageIndices,
                textScale = textScale,
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
                onPageChanged = onPageChanged,
                onShowPanel = onToggleControlsVisible,
                onNavigatePrevChapter = onNavigatePrev,
                onNavigateNextChapter = onNavigateNext,
                onSharePage = onSharePage,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpConsumed = onJumpConsumed,
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = onAutoNextChapter,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
            )
        } else {
            MangaReader(
                // beze změny, viz řádky 179-204
            )
        }
```

- [ ] **Step 3: Předat `pageCurlEnabled` z `ReaderScreen.kt` do `ReaderContent`**

Ve stejném volání `ReaderContent(...)` v `ReaderScreen.kt`, kde Task 6 přidal
`pageCurlEnabled` pro `NovelContent`, přidat stejný parametr i sem:

```kotlin
            pageCurlEnabled = pageCurlEnabled,
```

- [ ] **Step 4: Ověřit sestavení**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/MangaPageCurlReader.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderContent.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt
git commit -m "feat: pridat MangaPageCurlReader a zapojit sdileny toggle do manga readeru"
```

---

### Task 9: Manuální ověření na emulátoru/zařízení (novel i manga/manhwa)

**Files:** žádné (jen ověřovací krok - vizuální kvalitu ohybu a souhru gest nejde
automatizovaně otestovat).

- [ ] **Step 1: Sestavit a nainstalovat debug APK**

```bash
cd "C:\Users\ilekr\Desktop\jiyu"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug --console=plain
"C:\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Zapnout toggle**

Nastavení → Nastavení čtečky → sekce "Otáčení stránek" → zapnout "Použití 3D efektu při
otáčení stránek".

- [ ] **Step 3: Novel reader — ověřit**

- Kapitola se zobrazuje po stránkách, ne jako scroll; dole "Stránka X z Y · Z %".
- Tah prstem ohýbá stránku a POD OHYBEM JE VIDĚT ZAČÁTEK DALŠÍ/PŘEDCHOZÍ STRÁNKY (ne
  jen prázdné pozadí - to je oprava oproti původnímu Task 6 návrhu, ověřit, že funguje).
- Puštění před prahem (~40 %) se vrátí naplocho, za prahem dokončí obrat.
- Ťuknutí na levý/pravý okraj obrátí stránku bez tahu.
- Poslední/první stránka kapitoly → tah/ťuk přejde na DALŠÍ/PŘEDCHOZÍ kapitolu.
- Změna fontu uprostřed čtení nezpůsobí skok (repaginace zachová pozici).
- Vypnutí toggle uprostřed čtení vrátí dnešní scroll bez pádu appky.

- [ ] **Step 4: Manga/manhwa reader (`ReadingMode.MANGA`) — ověřit**

- Otevřít mangu/manhwu s `ReadingMode.MANGA` (ne webtoon) - stránky se otáčí curl
  přechodem místo plynulého swipu; POD OHYBEM je vidět další/předchozí stránka.
- Pinch-zoom funguje na klidové stránce stejně jako dřív; při `scale > 1f` se curl-drag
  nespustí (žádné náhodné otočení stránky při panování v zoomu) - přesně jako dřív swipe.
- Zapnout dvoustránkový spread (`Nastavení čtečky`) + otočit zařízení na šířku - pár
  stránek se otáčí jako JEDNA curl jednotka.
- Otočit zařízení tam a zpět uprostřed čtení (spread on/off) - žádný zaseknutý/rozpůlený
  stav.
- Tap zóny (pokud zapnuté v Nastavení) fungují stejně jako dřív - okraje otáčí stránku
  (teď curlem), střed/rohy dělají to, co dělaly dřív (panel, kapitoly).
- Volume klávesy otáčí stránku (curlem), pokud je `volumeKeysNav` zapnuté.
- Dlouhý stisk na stránce nabídne sdílení/uložení do galerie stejně jako dřív.
- Zapnutý bublinový překlad - bubliny se otáčí SPOLU se stránkou (jsou součástí
  rasterizované bitmapy), ne odděleně/posunuté.
- Kapitola s 1 stránkou → tah/ťuk rovnou přejde na sousední kapitolu, žádná prázdná
  animace.
- Vypnutí toggle uprostřed čtení vrátí dnešní `HorizontalPager` swipe bez pádu appky.

- [ ] **Step 5: `ReadingMode.WEBTOON` — ověřit, že se NIC nezměnilo**

Otevřít manhwu/webtoon v `ReadingMode.WEBTOON` (se zapnutým togglem) - vertikální scroll
musí vypadat a chovat se úplně stejně jako před touhle prací (toggle se ho netýká).

- [ ] **Step 6: Zaznamenat zjištění**

Pokud vizuální kvalita ohybu (stín, zrcadlený rub, ostrost fold linie) neodpovídá
očekávání v kterékoliv čtečce, upravit konstanty v `PageCurlEffect.kt` (`shadowWidth`,
`radius`, barvy/alpha gradientů) a zopakovat Step 1-5 - to je očekávaná iterace, ne
chyba plánu (viz spec, sekce "Vykreslení ohybu").

---

## Self-Review (proveden autorem plánu)

**Pokrytí specu:** Paginátor (novel) → Task 2. Sdílená geometrie/gesto/vykreslení →
Task 3 + Task 4 + Task 5. Manga stránkování/vykreslení/sdílení (extrahováno beze změny
chování) → Task 7. Sjednocený toggle → Task 1, čte ho Task 6 (novel) i Task 8 (manga).
Oprava chybějící `revealedPageBitmap` (spec, "Architektura" bod 2) → Task 6 i Task 8.
Manga reálné souběhy (zoom, spread, tap zóny, volume klávesy, sdílení, auto-next-kapitola,
bublinový překlad) → Task 8, viz sekce "Řešení souběhů" uvnitř tasku. Ukazatel postupu
(novel) → Task 6; manga si drží dnešní `ReaderTopBar`, beze změny. Edge cases (prázdný
text, extrémní font - novel; 1-stránková kapitola, zoom×curl, spread on/off - manga) →
testy v Task 2/3 (sdíleno) + manuální ověření v Task 9. `ReadingMode.WEBTOON` netknuto →
explicitní krok v Task 9 Step 5. Mimo rozsah (TTS toggle, persistence fontu/tématu,
plochý bezcurlový mezistav, zoom+curl současně jedním gestem) → nedotčeno, žádný task na
ně nesahá.

**Placeholder scan:** žádné TBD/TODO, všechny kroky mají konkrétní kód.

**Typová konzistence:** `NovelPage`/`TextLayoutProvider`/`paginateNovelText`/
`findPageIndexForOffset` (Task 2) používány stejně v Task 6. `PageCurlState`/
`TurnDirection`/`PageTurnResult`/`.withDrag()`/`.onDragEnd()`/`.onEdgeTap()` (Task 3)
používány STEJNĚ v Task 6 (nad textovými stránkami) i Task 8 (nad `groups`). `Point`/
`PageCurlGeometry`/`computePageCurlGeometry` (Task 4) používány stejně v Task 5, Task 6
i Task 8. `drawPageCurl` (Task 5) volán stejně v Task 6 i Task 8, vždy s reálnou
`revealedPageBitmap`. `computePageGroups`/`MangaGroupContent`/`SharePageBottomSheet`
(Task 7) používány stejně v Task 8. `pageCurlEnabled` (Task 1) čten stejným
`ReaderViewModel.pageCurlEnabled: StateFlow<Boolean>` v Task 6 i Task 8.
