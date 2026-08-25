# Reader Page Prefetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Předstahovat obrázky několika dalších stránek kapitoly do Coil cache dřív, než na ně dojde v čtečce řada, aby otáčení/scrollování stránek nečekalo na síť.

**Architecture:** Nová čistá funkce `computePrefetchIndices` (samostatný soubor, JVM-testovatelná) spočítá, které indexy stránek ještě chybí předstáhnout. `ReaderViewModel` ji zavolá po načtení kapitoly a při každé změně stránky, a pro vrácené indexy pošle Coilu `imageLoader.enqueue(...)` s nízkou prioritou - žádná nová cache, žádná změna UI/Compose souborů.

**Tech Stack:** Kotlin, Coil 2.x (`coil.request.ImageRequest`, `coil.request.Priority`, `coil.Coil`), JUnit4 (existing test convention v `app/src/test/kotlin`).

**Spec:** `docs/superpowers/specs/2026-08-25-reader-page-prefetch-design.md`

## Global Constraints

- Práce probíhá přímo na `master` (zavedená konvence tohoto repa, žádná feature branch/worktree).
- Žádný release/verze appky v rámci tohoto plánu - `app/build.gradle.kts` se nemění.
- Žádný Compose/UI soubor se nemění (`ReaderPager.kt`, `WebtoonReader.kt`, `ReaderImage.kt`, jakákoli Screen obrazovka) - viz spec, uživatel výslovně nechce vizuální změny.
- Prefetch okno je natvrdo 4 stránky (`PREFETCH_WINDOW`), vždy zapnuto, žádné nové uživatelské nastavení.
- Prefetch přes hranici kapitoly (další kapitola) je mimo rozsah - existující `preloadNextChapter()` v `ReaderViewModel.kt` se nemění.

---

### Task 1: Čistá funkce `computePrefetchIndices`

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ChapterPagePrefetch.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/ui/reader/ChapterPagePrefetchTest.kt`

**Interfaces:**
- Produces: `const val PREFETCH_WINDOW: Int = 4` a `fun computePrefetchIndices(fromIndex: Int, pageCount: Int, alreadyPrefetched: Set<Int>, count: Int = PREFETCH_WINDOW): List<Int>` v balíčku `com.haise.jiyu.ui.reader` - Task 2 tuhle funkci a konstantu volá BEZ importu (stejný balíček jako `ReaderViewModel.kt`).

- [ ] **Step 1: Napiš selhávající testy**

Vytvoř `app/src/test/kotlin/com/haise/jiyu/ui/reader/ChapterPagePrefetchTest.kt`:

```kotlin
package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterPagePrefetchTest {

    @Test
    fun `window in the middle of the list returns count indices ahead`() {
        assertEquals(
            listOf(2, 3, 4, 5),
            computePrefetchIndices(fromIndex = 2, pageCount = 20, alreadyPrefetched = emptySet(), count = 4),
        )
    }

    @Test
    fun `window near the end is truncated to page count`() {
        assertEquals(
            listOf(8, 9),
            computePrefetchIndices(fromIndex = 8, pageCount = 10, alreadyPrefetched = emptySet(), count = 4),
        )
    }

    @Test
    fun `already prefetched indices are skipped`() {
        assertEquals(
            listOf(3, 5),
            computePrefetchIndices(fromIndex = 2, pageCount = 20, alreadyPrefetched = setOf(2, 4), count = 4),
        )
    }

    @Test
    fun `negative fromIndex returns empty list`() {
        assertEquals(
            emptyList<Int>(),
            computePrefetchIndices(fromIndex = -1, pageCount = 10, alreadyPrefetched = emptySet()),
        )
    }

    @Test
    fun `fromIndex at or past the end of the list returns empty list`() {
        assertEquals(
            emptyList<Int>(),
            computePrefetchIndices(fromIndex = 10, pageCount = 10, alreadyPrefetched = emptySet()),
        )
    }

    @Test
    fun `zero page count returns empty list regardless of fromIndex`() {
        assertEquals(
            emptyList<Int>(),
            computePrefetchIndices(fromIndex = 0, pageCount = 0, alreadyPrefetched = emptySet()),
        )
    }

    @Test
    fun `default count is PREFETCH_WINDOW`() {
        assertEquals(4, PREFETCH_WINDOW)
        assertEquals(
            listOf(0, 1, 2, 3),
            computePrefetchIndices(fromIndex = 0, pageCount = 100, alreadyPrefetched = emptySet()),
        )
    }
}
```

- [ ] **Step 2: Ověř, že testy selžou (funkce ještě neexistuje)**

Spusť (POZOR - v Bash tool exportuj `JAVA_HOME` v TÉTO KONKRÉTNÍ zprávě, shell state se mezi voláními neuchovává; nikdy neposílej výstup přes `| tail`, exit kód by pak byl z `tail`, ne z gradlew):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --tests "com.haise.jiyu.ui.reader.ChapterPagePrefetchTest" --console=plain > /tmp/test1.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/test1.log
```

Očekávej: FAIL / kompilační chybu (`computePrefetchIndices`/`PREFETCH_WINDOW` neexistuje).

- [ ] **Step 3: Vytvoř `ChapterPagePrefetch.kt`**

```kotlin
package com.haise.jiyu.ui.reader

/** Kolik stránek dopředu se má předstáhnout - viz [computePrefetchIndices]. */
const val PREFETCH_WINDOW = 4

/**
 * Spočítá, které indexy stránek je potřeba předstáhnout (aktuální pozice + [count] dopředu),
 * vynechá ty, co jsou už v [alreadyPrefetched], a nikdy nepřeteče za konec [pageCount].
 */
fun computePrefetchIndices(
    fromIndex: Int,
    pageCount: Int,
    alreadyPrefetched: Set<Int>,
    count: Int = PREFETCH_WINDOW,
): List<Int> {
    if (fromIndex < 0 || pageCount <= 0) return emptyList()
    return (fromIndex until minOf(fromIndex + count, pageCount))
        .filter { it !in alreadyPrefetched }
}
```

- [ ] **Step 4: Ověř, že testy projdou**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --tests "com.haise.jiyu.ui.reader.ChapterPagePrefetchTest" --console=plain > /tmp/test2.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/test2.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`, 7 testů zelených.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/ilekr/Desktop/jiyu
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/ChapterPagePrefetch.kt app/src/test/kotlin/com/haise/jiyu/ui/reader/ChapterPagePrefetchTest.kt
git commit -m "feat: cista funkce computePrefetchIndices pro prefetch stranek ctecky"
```

---

### Task 2: Napojit prefetch do `ReaderViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt`

**Interfaces:**
- Consumes: `PREFETCH_WINDOW`, `computePrefetchIndices(fromIndex, pageCount, alreadyPrefetched, count)` z Task 1 (stejný balíček `com.haise.jiyu.ui.reader`, bez importu).
- Existující interní stav, který tenhle task čte/používá (beze změny signatur): `private val context: Context` (konstruktor pole `ReaderViewModel`), `_pages: MutableStateFlow<List<String>>`, `_pageReferer: MutableStateFlow<String?>`, `_initialPage: MutableStateFlow<Int>`.

Tenhle task nemá vlastní automatizovaný test (Coil + Android Context by vyžadovaly Robolectric/mock navíc, viz spec "Testování" - ruční ověření stačí). Místo TDD kroků: přesná editace + kompilace + manuální ověření.

- [ ] **Step 1: Přidej import Coil typů**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt` najdi blok importů na začátku souboru (řádky 1-46, začíná `package com.haise.jiyu.ui.reader`, končí `import com.haise.jiyu.util.report`). Přidej za `import android.content.Context` (řádek 3) tyhle tři řádky:

```kotlin
import coil.Coil
import coil.request.ImageRequest
import coil.request.Priority
```

- [ ] **Step 2: Přidej stav `prefetchedPageIndices`**

Najdi (existující kód, beze změny až na přidaný řádek):

```kotlin
    // ── Přednačítání další kapitoly ──────────────────────────────────────────
    private val nextChapterCache = mutableMapOf<String, List<String>>()
    private var preloadJob: Job? = null
    private var novelPreloadJob: Job? = null
    private var mangaTranslatePreloadJob: Job? = null
```

Nahraď za (přidán jeden řádek + komentář na konci bloku):

```kotlin
    // ── Přednačítání další kapitoly ──────────────────────────────────────────
    private val nextChapterCache = mutableMapOf<String, List<String>>()
    private var preloadJob: Job? = null
    private var novelPreloadJob: Job? = null
    private var mangaTranslatePreloadJob: Job? = null

    /** Indexy stránek AKTUÁLNÍ kapitoly, pro které už proběhl [prefetchPagesFrom] - viz reset v [loadChapter]. */
    private val prefetchedPageIndices = mutableSetOf<Int>()
```

- [ ] **Step 3: Resetuj `prefetchedPageIndices` na začátku `loadChapter`**

Najdi (existující kód):

```kotlin
    private suspend fun loadChapter(id: String) {
        _loading.value = true
        _pages.value = emptyList()
        _translatedPages.value = emptyMap()
```

Nahraď za:

```kotlin
    private suspend fun loadChapter(id: String) {
        _loading.value = true
        _pages.value = emptyList()
        prefetchedPageIndices.clear()
        _translatedPages.value = emptyMap()
```

- [ ] **Step 4: Zavolej prefetch na konci `loadChapter`, jakmile jsou `_pages` finální**

Najdi (existující kód, konec `loadChapter` - poslední řádky funkce):

```kotlin
        lastPageChangeMs = System.currentTimeMillis()
        _loading.value = false
        // Kazde plne nacteni kapitoly (jumpToChapter/navigateNext/navigatePrev/pocatecni otevreni)
        // zacina cerstvym jednosegmentovym seznamem - i pri zapnutem "Nekonecnem cteni" se dalsi
        // segmenty pridavaji az prubezne za cteni (viz appendNextWebtoonSegment), ne predem.
        appendingSegmentJob?.cancel()
        _webtoonSegments.value = listOf(WebtoonSegment(chapter.id, chapter.name, _pages.value))
    }
```

Nahraď za (přidán jeden řádek na začátku):

```kotlin
        prefetchPagesFrom(_initialPage.value)
        lastPageChangeMs = System.currentTimeMillis()
        _loading.value = false
        // Kazde plne nacteni kapitoly (jumpToChapter/navigateNext/navigatePrev/pocatecni otevreni)
        // zacina cerstvym jednosegmentovym seznamem - i pri zapnutem "Nekonecnem cteni" se dalsi
        // segmenty pridavaji az prubezne za cteni (viz appendNextWebtoonSegment), ne predem.
        appendingSegmentJob?.cancel()
        _webtoonSegments.value = listOf(WebtoonSegment(chapter.id, chapter.name, _pages.value))
    }
```

Poznámka: `computePrefetchIndices` uvnitř `prefetchPagesFrom` (viz Step 5) vrátí prázdný seznam, když `_pages.value` je prázdný (ComicK/novela/chyba načtení) - volání na konci `loadChapter` je bezpečné volat vždy, žádná podmínka navíc není potřeba.

- [ ] **Step 5: Přidej funkci `prefetchPagesFrom`**

Najdi (existující kód - konec `preloadNextChapter`, hned před komentářem k nekonečnému čtení):

```kotlin
    private fun preloadNextChapter() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return
        val nextChapter = allChapters[idx - 1]
        if (nextChapterCache.containsKey(nextChapter.id)) return
        if (nextChapter.downloadStatus == DownloadStatus.DOWNLOADED) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val manga = repository.getManga(nextChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(nextChapter.sourceId, nextChapter.url, manga.url)
                val urls = rawPages.mapNotNull { it.imageUrl?.takeIf { u -> u.isNotBlank() } ?: it.url.takeIf { u -> u.isNotBlank() } }
                if (urls.isNotEmpty()) nextChapterCache[nextChapter.id] = urls
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterPages")
            }
        }
    }
```

Nahraď za (přidána nová funkce hned za `preloadNextChapter`):

```kotlin
    private fun preloadNextChapter() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return
        val nextChapter = allChapters[idx - 1]
        if (nextChapterCache.containsKey(nextChapter.id)) return
        if (nextChapter.downloadStatus == DownloadStatus.DOWNLOADED) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val manga = repository.getManga(nextChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(nextChapter.sourceId, nextChapter.url, manga.url)
                val urls = rawPages.mapNotNull { it.imageUrl?.takeIf { u -> u.isNotBlank() } ?: it.url.takeIf { u -> u.isNotBlank() } }
                if (urls.isNotEmpty()) nextChapterCache[nextChapter.id] = urls
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterPages")
            }
        }
    }

    /**
     * Předstáhne obrázky nadcházejících stránek AKTUÁLNÍ kapitoly (viz [computePrefetchIndices])
     * do Coil cache, dřív než na ně dojde řada v čtečce - řeší "kapitola se dlouho dokresluje
     * po stránkách". Volá se po dokončení [loadChapter] (od [ReaderViewModel._initialPage]) a při
     * každé změně stránky (viz [onPageChanged], od `index + 1`). Fire-and-forget - selhání
     * jednotlivého požadavku se tiše zahodí, skutečné zobrazení stránky pak proběhne normální
     * cestou přes [RetryableAsyncImage] s vlastním retry UI.
     */
    private fun prefetchPagesFrom(fromIndex: Int) {
        val pages = _pages.value
        val indices = computePrefetchIndices(fromIndex, pages.size, prefetchedPageIndices)
        if (indices.isEmpty()) return
        val referer = _pageReferer.value
        val imageLoader = Coil.imageLoader(context)
        for (index in indices) {
            val url = pages[index]
            if (url.isBlank()) continue
            prefetchedPageIndices += index
            val request = ImageRequest.Builder(context)
                .data(url)
                .apply { if (!referer.isNullOrBlank()) addHeader("Referer", referer) }
                .priority(Priority.LOW)
                .build()
            imageLoader.enqueue(request)
        }
    }
```

- [ ] **Step 6: Zavolej prefetch při změně stránky**

Najdi (existující kód, začátek `onPageChanged`):

```kotlin
    fun onPageChanged(index: Int) {
        _currentPage.value = index

        val total = _pages.value.size
        if (total > 0 && index >= total - 3 && _hasNextChapter.value) preloadNextChapter()
```

Nahraď za:

```kotlin
    fun onPageChanged(index: Int) {
        _currentPage.value = index
        prefetchPagesFrom(index + 1)

        val total = _pages.value.size
        if (total > 0 && index >= total - 3 && _hasNextChapter.value) preloadNextChapter()
```

- [ ] **Step 7: Ověř kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat compileDebugKotlin --console=plain > /tmp/build3.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/build3.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 8: Spusť celou unit testovou sadu (kontrola, že se nic nerozbilo)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --console=plain > /tmp/test3.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/test3.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 9: Ruční ověření (emulátor/zařízení)**

Pokud je dostupný emulátor/zařízení (viz `project_jiyu_environment` paměť pro spuštění): nainstalovat debug build, otevřít kapitolu z manga zdroje (stránkovací režim) i z webtoon zdroje, listovat/scrollovat dopředu a sledovat, že další 2-4 stránky se zobrazí bez viditelného čekání. Vyzkoušet i kapitolu otevřenou z historie (obnovená pozice), ne jen úplně novou. Pokud emulátor není v prostředí dostupný, tenhle krok přeskočit a nechat ruční ověření na uživateli - NEPOKOUŠET SE to obejít jiným (méně spolehlivým) ověřením.

- [ ] **Step 10: Commit**

```bash
cd /c/Users/ilekr/Desktop/jiyu
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt
git commit -m "feat: predstahovat nadchazejici stranky kapitoly do Coil cache"
```
