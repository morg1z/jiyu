# 3D efekt otáčení stránek (page curl) v novel readeru — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Přidat do novel readeru appky Jiyu volitelný stránkovaný režim s plnou 3D page-curl
animací (věrnost jako Google Play Books) ovládaný togglem v Nastavení čtečky.

**Architecture:** Tři nezávislé, samostatně testovatelné vrstvy: (1) čistý paginátor
(Kotlin, JVM-testovatelný), (2) čistá geometrie ohybu + gesto/stav rozhodovací logika
(taky JVM-testovatelné, bez Compose typů), (3) Canvas/Matrix vykreslení a Compose
composable, které vše propojí. Toggle VYPNUTÝ = beze změny (dnešní `LazyColumn` scroll).

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.12.01 - `GraphicsLayer.toImageBitmap()`
stabilní), `android.graphics.Canvas`/`Matrix`/`Path` pro vykreslení ohybu, JUnit4 pro
unit testy.

**Spec:** `docs/superpowers/specs/2026-08-21-novel-page-curl-design.md`

## Global Constraints

- Práce se dělá přímo na `master`, žádná feature branch (zavedená konvence projektu).
- `JAVA_HOME` musí být nastaven na `C:\Program Files\Android\Android Studio\jbr` před
  každým `./gradlew` voláním (nepřežívá mezi Bash voláními).
- Po každém tasku: `./gradlew compileDebugKotlin testDebugUnitTest`, teprve pak commit.
- Žádný task se nedotýká `NovelContent.kt`'s existujícího `LazyColumn` chování, dokud
  toggle není zapnutý - Task 7 přidává jen podmíněnou větev, nic neodstraňuje.
- Nové stringy (cs/en/es/fr) - cs je zdrojový jazyk appky, ostatní 3 překlady stejným
  stylem jako existující `settings_reader_preload_novel_*` klíče.
- Čistá logika (Task 2, 3, 4) NESMÍ importovat žádný `androidx.compose.*` typ - projekt
  nemá v `app/src/test` ani jeden existující import z Compose (ověřeno), pravděpodobně
  záměrně kvůli JVM-testovatelnosti bez Robolectricu (viz [[project_jiyu_audit_2026_08]]
  "past s Robolectric" v paměti). Vlastní `Point` data class místo `Offset` v testovatelných
  souborech, převod na `Offset` až na hranici s Compose vrstvou.

---

### Task 1: Nastavení — toggle "Použití 3D efektu při otáčení stránek"

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
- Produces: `SettingsRepository.novelPageCurl: Flow<Boolean>` (default `false`),
  `SettingsRepository.setNovelPageCurl(enabled: Boolean): suspend Unit` — Task 7 (a
  `ReaderViewModel`, mimo rozsah tohoto tasku) na tohle naváže při čtení kapitoly.

- [ ] **Step 1: Přidat klíč do `SettingsKeys`**

V `app/src/main/kotlin/com/haise/jiyu/settings/SettingsRepository.kt`, do objektu
`SettingsKeys` (za řádek `val PRELOAD_NEXT_CHAPTER_WIFI_ONLY = ...`, řádek 50):

```kotlin
    val NOVEL_PAGE_CURL        = booleanPreferencesKey("novel_page_curl")
```

- [ ] **Step 2: Přidat Flow + setter do `SettingsRepository`**

Za blok `cropBorders` (řádek 481-486):

```kotlin
    /** Výchozí false - stránkovaný 3D page-curl režim v novel readeru místo dnešního scrollu. */
    val novelPageCurl: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.NOVEL_PAGE_CURL] ?: false }

    suspend fun setNovelPageCurl(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.NOVEL_PAGE_CURL] = enabled }
```

- [ ] **Step 3: Přidat passthrough do `SettingsViewModel`**

Za blok `preloadNextNovelChapter` (řádek 494-498) v
`app/src/main/kotlin/com/haise/jiyu/ui/settings/SettingsViewModel.kt`:

```kotlin
    // ── 3D efekt otáčení stránek v novel readeru ──────────────────────────────
    val novelPageCurl: StateFlow<Boolean> = settings.novelPageCurl
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setNovelPageCurl(enabled: Boolean) = viewModelScope.launch { settings.setNovelPageCurl(enabled) }
```

- [ ] **Step 4: Přidat stringy (4 jazyky)**

`app/src/main/res/values/strings.xml`, za `settings_reader_preload_novel_desc`:

```xml
    <string name="settings_reader_novel_section_title">Light novel čtečka</string>
    <string name="settings_reader_novel_page_curl_title">Použití 3D efektu při otáčení stránek</string>
    <string name="settings_reader_novel_page_curl_desc">Kapitola se zobrazí po stránkách s ohybovou animací jako u knihy, místo plynulého scrollu</string>
```

`app/src/main/res/values-en/strings.xml`, na stejné místo:

```xml
    <string name="settings_reader_novel_section_title">Light novel reader</string>
    <string name="settings_reader_novel_page_curl_title">3D page-turn effect</string>
    <string name="settings_reader_novel_page_curl_desc">Show the chapter as book-like pages with a curling animation instead of continuous scrolling</string>
```

`app/src/main/res/values-es/strings.xml`:

```xml
    <string name="settings_reader_novel_section_title">Lector de novelas ligeras</string>
    <string name="settings_reader_novel_page_curl_title">Efecto 3D al pasar página</string>
    <string name="settings_reader_novel_page_curl_desc">Muestra el capítulo en páginas con una animación de curvatura como un libro, en vez de desplazamiento continuo</string>
```

`app/src/main/res/values-fr/strings.xml`:

```xml
    <string name="settings_reader_novel_section_title">Lecteur de light novels</string>
    <string name="settings_reader_novel_page_curl_title">Effet 3D lors du changement de page</string>
    <string name="settings_reader_novel_page_curl_desc">Affiche le chapitre en pages avec une animation de courbure comme un livre, au lieu d'un défilement continu</string>
```

- [ ] **Step 5: Přidat toggle do `ReaderSettingsScreen`**

V `app/src/main/kotlin/com/haise/jiyu/ui/settings/ReaderSettingsScreen.kt`, přidat state
čtení za řádek 70 (`val preloadNextNovelChapter by ...`):

```kotlin
    val novelPageCurl      by viewModel.novelPageCurl.collectAsState()
```

A novou sekci PŘED `SettingsSection(title = stringResource(R.string.settings_reader_preload_section_title))`
(řádek 235):

```kotlin
                // ── Light novel čtečka ─────────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_novel_section_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_novel_page_curl_title),
                        description = stringResource(R.string.settings_reader_novel_page_curl_desc),
                        checked = novelPageCurl,
                        onCheckedChange = { viewModel.setNovelPageCurl(it) },
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
git commit -m "feat: pridat toggle 3D efektu otaceni stranek do Nastaveni ctecky"
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

### Task 3: NovelPageCurlState — rozhodovací logika gesta a otáčení

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelPageCurlState.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/ui/reader/NovelPageCurlStateTest.kt`

**Interfaces:**
- Consumes: nic z předchozích tasků (nezávislé).
- Produces: `enum class TurnDirection { NEXT, PREV }`, `data class NovelPageCurlState(val currentPageIndex: Int, val pageCount: Int, val dragProgress: Float = 0f)`,
  `sealed class PageTurnResult` (`WithinChapter(newState)`, `ChapterBoundary(direction)`, `Cancelled(newState)`),
  `fun NovelPageCurlState.withDrag(deltaProgress: Float): NovelPageCurlState`,
  `fun NovelPageCurlState.onDragEnd(completionThreshold: Float = 0.4f): PageTurnResult`,
  `fun NovelPageCurlState.onEdgeTap(direction: TurnDirection): PageTurnResult` —
  Task 7 (composable) na tyhle funkce naváže přímo z `pointerInput` handlerů.

- [ ] **Step 1: Napsat failing testy**

Vytvořit `app/src/test/kotlin/com/haise/jiyu/ui/reader/NovelPageCurlStateTest.kt`:

```kotlin
package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPageCurlStateTest {

    @Test
    fun `dragging past the threshold and releasing advances to the next page`() {
        val state = NovelPageCurlState(currentPageIndex = 2, pageCount = 5)
        val dragged = state.withDrag(0.6f)
        val result = dragged.onDragEnd(completionThreshold = 0.4f)
        assertTrue(result is PageTurnResult.WithinChapter)
        assertEquals(3, (result as PageTurnResult.WithinChapter).newState.currentPageIndex)
        assertEquals(0f, result.newState.dragProgress)
    }

    @Test
    fun `dragging below the threshold and releasing cancels back to flat`() {
        val state = NovelPageCurlState(currentPageIndex = 2, pageCount = 5)
        val dragged = state.withDrag(0.2f)
        val result = dragged.onDragEnd(completionThreshold = 0.4f)
        assertTrue(result is PageTurnResult.Cancelled)
        assertEquals(0f, (result as PageTurnResult.Cancelled).newState.dragProgress)
        assertEquals(2, result.newState.currentPageIndex)
    }

    @Test
    fun `completing a turn on the last page of the chapter reports a chapter boundary, not a page change`() {
        val state = NovelPageCurlState(currentPageIndex = 4, pageCount = 5)
        val result = state.withDrag(0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), result)
    }

    @Test
    fun `completing a turn on the first page toward prev reports a chapter boundary`() {
        val state = NovelPageCurlState(currentPageIndex = 0, pageCount = 5)
        val result = state.withDrag(-0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), result)
    }

    @Test
    fun `dragging past the chapter boundary produces no curl progress`() {
        val lastPage = NovelPageCurlState(currentPageIndex = 4, pageCount = 5)
        assertEquals(0f, lastPage.withDrag(0.7f).dragProgress)

        val firstPage = NovelPageCurlState(currentPageIndex = 0, pageCount = 5)
        assertEquals(0f, firstPage.withDrag(-0.7f).dragProgress)
    }

    @Test
    fun `a single-page chapter reports a chapter boundary immediately on edge tap without any drag`() {
        val state = NovelPageCurlState(currentPageIndex = 0, pageCount = 1)
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), state.onEdgeTap(TurnDirection.NEXT))
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), state.onEdgeTap(TurnDirection.PREV))
    }

    @Test
    fun `edge tap works the same as a completed drag without needing prior drag state`() {
        val state = NovelPageCurlState(currentPageIndex = 1, pageCount = 5)
        val result = state.onEdgeTap(TurnDirection.NEXT)
        assertEquals(PageTurnResult.WithinChapter(NovelPageCurlState(2, 5, 0f)), result)
    }
}
```

- [ ] **Step 2: Ověřit, že testy selžou**

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — `unresolved reference: NovelPageCurlState`

- [ ] **Step 3: Implementovat `NovelPageCurlState.kt`**

```kotlin
package com.haise.jiyu.ui.reader

enum class TurnDirection { NEXT, PREV }

/**
 * Stav ohybu stránky - na které stránce jsme, kolik jich kapitola má, a jak moc je
 * aktuálně "ohnutá" (0f = plochá, ±1f = plně otočená). Čistá immutable data třída bez
 * závislosti na Compose/gestech - testovatelná přímo.
 */
data class NovelPageCurlState(
    val currentPageIndex: Int,
    val pageCount: Int,
    val dragProgress: Float = 0f, // -1f..1f: zaporne = ohyb k PREV, kladne = k NEXT
)

/** Výsledek gesta - buď změna stránky uvnitř kapitoly, přechod na jinou KAPITOLU (hranice),
 * nebo zrušení (vráceno zpět naplocho). */
sealed class PageTurnResult {
    data class WithinChapter(val newState: NovelPageCurlState) : PageTurnResult()
    data class ChapterBoundary(val direction: TurnDirection) : PageTurnResult()
    data class Cancelled(val newState: NovelPageCurlState) : PageTurnResult()
}

/** Průběžný tah prstem - aktualizuje míru ohybu, NEMĚNÍ currentPageIndex (to se děje až
 * po puštění). Na hranici kapitoly (první/poslední stránka) se ohyb tím směrem nepovolí -
 * hranici řeší až [onDragEnd]/[onEdgeTap], aby prázdná animace nikdy neproběhla. */
fun NovelPageCurlState.withDrag(deltaProgress: Float): NovelPageCurlState {
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
 * existující onNext()/onPrev() z ReaderViewModelu.
 */
fun NovelPageCurlState.onDragEnd(completionThreshold: Float = 0.4f): PageTurnResult {
    val magnitude = kotlin.math.abs(dragProgress)
    if (magnitude < completionThreshold) {
        return PageTurnResult.Cancelled(copy(dragProgress = 0f))
    }
    val direction = if (dragProgress > 0f) TurnDirection.NEXT else TurnDirection.PREV
    return completeTurn(direction)
}

/** Ťuknutí na okraj obrazovky = stejný výsledek jako dokončený tah, bez postupného ohybu -
 * proto funguje i pro jednostránkovou kapitolu, kde [withDrag] nikdy žádný ohyb nepovolí. */
fun NovelPageCurlState.onEdgeTap(direction: TurnDirection): PageTurnResult = completeTurn(direction)

private fun NovelPageCurlState.completeTurn(direction: TurnDirection): PageTurnResult {
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

Run: `cd "C:\Users\ilekr\Desktop\jiyu" && export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.haise.jiyu.ui.reader.NovelPageCurlStateTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 7 testů zelených

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/NovelPageCurlState.kt \
        app/src/test/kotlin/com/haise/jiyu/ui/reader/NovelPageCurlStateTest.kt
git commit -m "feat: pridat NovelPageCurlState - rozhodovaci logika gesta otaceni stranek"
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
  (Task 2), `NovelPageCurlState`/`TurnDirection`/`PageTurnResult`/`.withDrag()`/`.onDragEnd()`/`.onEdgeTap()`
  (Task 3), `Point`/`computePageCurlGeometry` (Task 4), `drawPageCurl` (Task 5).
- Produces: `@Composable fun PageCurlNovelReader(text: String, fontSize: Float, lineSpacing: Float, textColor: Color, bgColor: Color, onChapterBoundary: (TurnDirection) -> Unit)`

**Poznámka:** Tenhle task propojuje VŠECHNY předchozí vrstvy poprvé dohromady - přesná
souhra `pointerInput` gest, časování rasterizace stránky do bitmapy přes `GraphicsLayer`
(musí doběhnout dřív, než uživatel začne tahat) a finální vizuální ladění (šířka stínu,
práh dokončení tahu) se doladí až při ručním testu na zařízení v Task 8. Kód níže je
funkční výchozí bod, ne finální pixel-perfect podoba.

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
        var curlState by remember { mutableStateOf(NovelPageCurlState(0, 1)) }

        LaunchedEffect(text, fontSize, lineSpacing, widthPx, heightPx) {
            val previousOffset = pages.getOrNull(curlState.currentPageIndex)?.startIndex ?: 0
            val newPages = paginateNovelText(text, layoutProvider, widthPx, heightPx, fontSize)
            val newIndex = findPageIndexForOffset(newPages, previousOffset)
            pages = newPages
            curlState = NovelPageCurlState(currentPageIndex = newIndex, pageCount = newPages.size)
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
                    drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = null)
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
(řádek 179-180), stejný vzor:

```kotlin
    val novelPageCurl: StateFlow<Boolean> = settings.novelPageCurl
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

- [ ] **Step 3: Napojit do `ReaderScreen.kt`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt`, přidat čtení state (za
řádek 95, `val oledMode by viewModel.oledMode.collectAsState()`):

```kotlin
    val novelPageCurl by viewModel.novelPageCurl.collectAsState()
```

A předat do volání `NovelContent` (za `onRemoveGlossaryEntry`, řádek 235):

```kotlin
                pageCurlEnabled = novelPageCurl,
```

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

### Task 7: Manuální ověření na emulátoru/zařízení

**Files:** žádné (jen ověřovací krok - vizuální kvalitu ohybu nejde automatizovaně otestovat).

- [ ] **Step 1: Sestavit a nainstalovat debug APK**

```bash
cd "C:\Users\ilekr\Desktop\jiyu"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug --console=plain
"C:\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Zapnout toggle**

Nastavení → Nastavení čtečky → sekce "Light novel čtečka" → zapnout "Použití 3D efektu
při otáčení stránek".

- [ ] **Step 3: Otevřít libovolnou light novel kapitolu a ověřit**

- Kapitola se zobrazuje po stránkách, ne jako scroll.
- Dole je vidět "Stránka X z Y · Z %".
- Tah prstem zleva/zprava ohýbá stránku, ohyb sleduje pozici prstu.
- Puštění před prahem (~40 % přes obrazovku) se vrátí naplocho, za prahem dokončí obrat.
- Ťuknutí na levý/pravý okraj obrátí stránku bez tahu.
- Na poslední stránce kapitoly tah/ťuk doprava přejde na DALŠÍ kapitolu (ne prázdná animace).
- Na první stránce kapitoly tah/ťuk doleva přejde na PŘEDCHOZÍ kapitolu.
- Zvětšení/zmenšení fontu v nastavení čtečky uprostřed čtení nezpůsobí skok na jinou
  část textu (repaginace zachová přibližnou pozici).
- Vypnutí toggle uprostřed čtení vrátí dnešní scroll bez pádu appky.

- [ ] **Step 4: Zaznamenat zjištění**

Pokud vizuální kvalita ohybu (stín, zrcadlený rub, ostrost fold linie) neodpovídá
očekávání, upravit konstanty v `PageCurlEffect.kt` (`shadowWidth`, `radius`, barvy/alpha
gradientů) a zopakovat Step 1-3 - to je očekávaná iterace, ne chyba plánu (viz spec,
sekce "Vykreslení ohybu").

---

## Self-Review (proveden autorem plánu)

**Pokrytí specu:** Paginátor → Task 2. Zachování pozice při repaginaci →
`findPageIndexForOffset` (Task 2) + `LaunchedEffect` v Task 6. Gesto/stav (tah, ťuk,
hranice kapitoly, 1-stránková kapitola) → Task 3. Vykreslení ohybu (rasterizace, clip,
Matrix, stín, zrcadlený rub) → Task 4 + Task 5 + rasterizace v Task 6. Toggle → Task 1.
Ukazatel postupu → Task 6. Edge cases (prázdný text, extrémní font) → testy v Task 2.
Vypnutí uprostřed čtení → implicitní větev v Task 6, žádný extra kód. Mimo rozsah (TTS
toggle, persistence fontu/tématu, plochý bezcurlový mezistav) → nedotčeno, žádný task
na ně nesahá.

**Placeholder scan:** žádné TBD/TODO, všechny kroky mají konkrétní kód.

**Typová konzistence:** `NovelPage`/`TextLayoutProvider`/`paginateNovelText`/
`findPageIndexForOffset` (Task 2) používány stejně v Task 6. `NovelPageCurlState`/
`TurnDirection`/`PageTurnResult`/`.withDrag()`/`.onDragEnd()`/`.onEdgeTap()` (Task 3)
používány stejně v Task 6. `Point`/`PageCurlGeometry`/`computePageCurlGeometry` (Task 4)
používány stejně v Task 5 i Task 6. `drawPageCurl` (Task 5) volán stejně v Task 6.
