# Komentáře ke kapitole v čtečce — Implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Appka zobrazí komentáře ke KONKRÉTNÍ kapitole přímo v čtečce (tlačítko → bottom sheet), u 12 zdrojů, které je reálně mají – bez nutnosti JS, jen server-rendered HTML/JSON.

**Architecture:** Tři sdílené, na hierarchii tříd nezávislé parsovací funkce (wpDiscuz HTML, nativní WordPress HTML, MangaK/Comizy JSON formát) + nová volitelná metoda na `MangaSource`. Komentáře se stahují líně (až po kliknutí na tlačítko v čtečce), ne automaticky při otevření kapitoly.

**Tech Stack:** Kotlin, Jetpack Compose, Jsoup (HTML), org.json (JSON), Hilt, JUnit (JVM testy).

**Spec:** `docs/superpowers/specs/2026-08-28-chapter-comments-design.md` (commit `f136fac`)

## Global Constraints

- 12 zdrojů v1: MangaK, Comizy, mangaread.org, wuxiaworldsite, mangazin, mangagg, manhuanext, scythescans, lagoonscans, thunderscans, manhuahot, mangadistrict.
- `ChapterComment`: `id: String, author: String, content: String, createdAt: Long, avatarUrl: String? = null` – žádné vnořené odpovědi (YAGNI).
- Komentáře se stahují LÍNĚ, jen po otevření panelu komentářů v čtečce – nikdy automaticky při `loadChapter()`.
- Práce probíhá přímo na `master`, bez feature branch (zavedená konvence celé iniciativy).
- `JAVA_HOME` musí být nastaven v KAŽDÉM Bash volání zvlášť: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`. Exit kód gradlew VŽDY zachytit ve STEJNÉM bash volání (`; echo EXIT_CODE=$?`), nikdy přes `| tail` ani odděleně.

---

## Task 1: Datový model a tři sdílené parsery (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/source/comments/ChapterComment.kt`
- Create: `app/src/main/kotlin/com/haise/jiyu/source/comments/WpDiscuzComments.kt`
- Create: `app/src/main/kotlin/com/haise/jiyu/source/comments/NativeWpComments.kt`
- Create: `app/src/main/kotlin/com/haise/jiyu/source/comments/MangaReaderJsonComments.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/source/comments/WpDiscuzCommentsTest.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/source/comments/NativeWpCommentsTest.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/source/comments/MangaReaderJsonCommentsTest.kt`

**Interfaces:**
- Produces: `data class ChapterComment(val id: String, val author: String, val content: String, val createdAt: Long, val avatarUrl: String? = null)`, `fun parseWpDiscuzComments(doc: Document): List<ChapterComment>`, `fun parseNativeWpComments(doc: Document): List<ChapterComment>`, `fun parseMangaReaderJsonComments(initialChapter: JSONObject): List<ChapterComment>`. Tyhle typy/funkce používají Task 2 a Task 3.

- [ ] **Step 1: Vytvořit `ChapterComment.kt`**

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

- [ ] **Step 2: Napsat padající test pro `parseWpDiscuzComments`**

```kotlin
package com.haise.jiyu.source.comments

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WpDiscuzCommentsTest {

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun `parses a single comment with all fields`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-left"><div class="wpd-avatar"><img src="https://example.com/avatar1.jpg"></div></div>
              <div class="wpd-comment-right" id="comment-100">
                <div class="wpd-comment-header">
                  <div class="wpd-comment-author">Alice</div>
                  <div class="wpd-comment-date" title="11.08.2026 00:22">17 days ago</div>
                </div>
                <div class="wpd-comment-text"><p>Great chapter!</p></div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("100", c.id)
        assertEquals("Alice", c.author)
        assertEquals("Great chapter!", c.content)
        assertEquals("https://example.com/avatar1.jpg", c.avatarUrl)
        assertTrue(c.createdAt > 0L)
    }

    @Test
    fun `parses multiple comments`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-author">Bob</div>
                <div class="wpd-comment-text"><p>First</p></div>
              </div>
            </div>
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-2">
                <div class="wpd-comment-author">Carol</div>
                <div class="wpd-comment-text"><p>Second</p></div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(2, result.size)
        assertEquals("Bob", result[0].author)
        assertEquals("Carol", result[1].author)
    }

    @Test
    fun `empty document returns empty list`() {
        assertEquals(emptyList<ChapterComment>(), parseWpDiscuzComments(doc("<html><body></body></html>")))
    }

    @Test
    fun `comment without author is skipped`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-text"><p>No author here</p></div>
              </div>
            </div>
        """.trimIndent()

        assertEquals(emptyList<ChapterComment>(), parseWpDiscuzComments(doc(html)))
    }

    @Test
    fun `comment without date title defaults createdAt to zero but is still included`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-author">Dan</div>
                <div class="wpd-comment-text"><p>No date</p></div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(1, result.size)
        assertEquals(0L, result[0].createdAt)
        assertNull(result[0].avatarUrl)
    }
}
```

- [ ] **Step 3: Ověřit RED (test nekompiluje - `parseWpDiscuzComments` ani `ChapterComment` neexistují mimo Step 1)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.source.comments.WpDiscuzCommentsTest"; echo EXIT_CODE=$?
```
Expected: `Unresolved reference: parseWpDiscuzComments`, `EXIT_CODE` nesmí být `0`.

- [ ] **Step 4: Implementovat `parseWpDiscuzComments`**

Vytvořit `app/src/main/kotlin/com/haise/jiyu/source/comments/WpDiscuzComments.kt`:
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

- [ ] **Step 5: Ověřit GREEN**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.source.comments.WpDiscuzCommentsTest"; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`, 5 testů zelených.

- [ ] **Step 6: Napsat padající test pro `parseNativeWpComments`**

```kotlin
package com.haise.jiyu.source.comments

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWpCommentsTest {

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun `parses a single comment with all fields`() {
        val html = """
            <li class="comment" id="comment-130316">
              <article class="comment-body">
                <div class="comment-avatar"><img src="https://example.com/avatar.jpg"></div>
                <div class="comment-author">ana</div>
                <div class="comment-content"><p>Nice one</p></div>
                <div class="comment-metadata"><a href="#">June 24, 2026 at 6:12 am</a></div>
              </article>
            </li>
        """.trimIndent()

        val result = parseNativeWpComments(doc(html))

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("130316", c.id)
        assertEquals("ana", c.author)
        assertEquals("Nice one", c.content)
        assertEquals("https://example.com/avatar.jpg", c.avatarUrl)
        assertTrue(c.createdAt > 0L)
    }

    @Test
    fun `parses multiple comments`() {
        val html = """
            <li class="comment" id="comment-1">
              <article class="comment-body">
                <div class="comment-author">Eve</div>
                <div class="comment-content"><p>First</p></div>
              </article>
            </li>
            <li class="comment" id="comment-2">
              <article class="comment-body">
                <div class="comment-author">Frank</div>
                <div class="comment-content"><p>Second</p></div>
              </article>
            </li>
        """.trimIndent()

        val result = parseNativeWpComments(doc(html))

        assertEquals(2, result.size)
        assertEquals("Eve", result[0].author)
        assertEquals("Frank", result[1].author)
    }

    @Test
    fun `empty document returns empty list`() {
        assertEquals(emptyList<ChapterComment>(), parseNativeWpComments(doc("<html><body></body></html>")))
    }

    @Test
    fun `comment without author is skipped`() {
        val html = """
            <li class="comment" id="comment-1">
              <article class="comment-body">
                <div class="comment-content"><p>No author here</p></div>
              </article>
            </li>
        """.trimIndent()

        assertEquals(emptyList<ChapterComment>(), parseNativeWpComments(doc(html)))
    }

    @Test
    fun `comment without metadata date defaults createdAt to zero but is still included`() {
        val html = """
            <li class="comment" id="comment-1">
              <article class="comment-body">
                <div class="comment-author">Gina</div>
                <div class="comment-content"><p>No date</p></div>
              </article>
            </li>
        """.trimIndent()

        val result = parseNativeWpComments(doc(html))

        assertEquals(1, result.size)
        assertEquals(0L, result[0].createdAt)
        assertNull(result[0].avatarUrl)
    }
}
```

- [ ] **Step 7: Ověřit RED**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.source.comments.NativeWpCommentsTest"; echo EXIT_CODE=$?
```
Expected: `Unresolved reference: parseNativeWpComments`, `EXIT_CODE` nesmí být `0`.

- [ ] **Step 8: Implementovat `parseNativeWpComments`**

Vytvořit `app/src/main/kotlin/com/haise/jiyu/source/comments/NativeWpComments.kt`:
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

- [ ] **Step 9: Ověřit GREEN**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.source.comments.NativeWpCommentsTest"; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`, 5 testů zelených.

- [ ] **Step 10: Napsat padající test pro `parseMangaReaderJsonComments`**

```kotlin
package com.haise.jiyu.source.comments

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderJsonCommentsTest {

    @Test
    fun `parses a single comment with all fields`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "abc123", "content": "Love it", "user": {"name": "Tester"}, "created_at": "2026-08-28T21:51:22.000Z"}
              ],
              "comments_count": 1
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("abc123", c.id)
        assertEquals("Tester", c.author)
        assertEquals("Love it", c.content)
        assertTrue(c.createdAt > 0L)
    }

    @Test
    fun `parses multiple comments`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "1", "content": "First", "user": {"name": "A"}, "created_at": "2026-08-28T21:00:00.000Z"},
                {"id": "2", "content": "Second", "user": {"name": "B"}, "created_at": "2026-08-28T22:00:00.000Z"}
              ]
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(2, result.size)
        assertEquals("A", result[0].author)
        assertEquals("B", result[1].author)
    }

    @Test
    fun `missing latest_comments field returns empty list`() {
        val json = JSONObject("""{"comments_count": 0}""")

        assertEquals(emptyList<ChapterComment>(), parseMangaReaderJsonComments(json))
    }

    @Test
    fun `null latest_comments returns empty list`() {
        val json = JSONObject("""{"latest_comments": null}""")

        assertEquals(emptyList<ChapterComment>(), parseMangaReaderJsonComments(json))
    }

    @Test
    fun `comment without id is skipped`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"content": "No id here", "user": {"name": "C"}, "created_at": "2026-08-28T21:00:00.000Z"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(emptyList<ChapterComment>(), parseMangaReaderJsonComments(json))
    }

    @Test
    fun `comment without user falls back to unknown author`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "1", "content": "Anonymous-ish", "created_at": "2026-08-28T21:00:00.000Z"}
              ]
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(1, result.size)
        assertEquals("?", result[0].author)
    }

    @Test
    fun `unparseable created_at defaults to zero`() {
        val json = JSONObject(
            """
            {
              "latest_comments": [
                {"id": "1", "content": "Bad date", "user": {"name": "D"}, "created_at": "not-a-date"}
              ]
            }
            """.trimIndent()
        )

        val result = parseMangaReaderJsonComments(json)

        assertEquals(1, result.size)
        assertEquals(0L, result[0].createdAt)
    }
}
```

- [ ] **Step 11: Ověřit RED**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.source.comments.MangaReaderJsonCommentsTest"; echo EXIT_CODE=$?
```
Expected: `Unresolved reference: parseMangaReaderJsonComments`, `EXIT_CODE` nesmí být `0`.

- [ ] **Step 12: Implementovat `parseMangaReaderJsonComments`**

Vytvořit `app/src/main/kotlin/com/haise/jiyu/source/comments/MangaReaderJsonComments.kt`:
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

- [ ] **Step 13: Ověřit GREEN**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.source.comments.MangaReaderJsonCommentsTest"; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`, 7 testů zelených.

- [ ] **Step 14: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/source/comments/ \
        app/src/test/kotlin/com/haise/jiyu/source/comments/
git commit -m "feat: datovy model a tri sdilene parsery komentaru ke kapitole"
```

---

## Task 2: MangaSource rozhraní + MadaraSource rozšíření a zapojení 7 instancí

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/MangaSource.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/madara/MadaraSource.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/SourceManager.kt`

**Interfaces:**
- Consumes: `parseWpDiscuzComments`, `parseNativeWpComments`, `ChapterComment` (Task 1).
- Produces: `MangaSource.supportsChapterComments: Boolean` (default `false`), `MangaSource.getChapterComments(chapter: SChapter): List<ChapterComment>` (default `emptyList()`) - konzumuje Task 3/4/5. `MadaraCommentStyle` enum, `MadaraSelectors.commentStyle: MadaraCommentStyle? = null`, `MadaraSource.supportsChapterComments`/`getChapterComments` override.

Tenhle task nemá vlastní nový automatizovaný test (orchestrace síť+HTML parsing dohromady, viz spec "Testování") - ověřuje se kompilací a tím, že existující testy dál procházejí.

**DŮLEŽITÉ POŘADÍ:** Rozhraní `MangaSource` (Step 1) MUSÍ být hotové PŘED úpravou `MadaraSource` (Step 2-3), protože `MadaraSource` bude tyhle dva členy přepisovat klíčovým slovem `override` - bez existence výchozí implementace na rozhraní by to nezkompilovalo.

- [ ] **Step 1: Přidat výchozí implementace do `MangaSource` rozhraní**

V `app/src/main/kotlin/com/haise/jiyu/source/MangaSource.kt` přidat za existující `suspend fun getImageUrl(page: Page): String = page.url` (poslední člen rozhraní, před uzavírací `}`):

```kotlin

    /** Zdroj poskytuje komentare k JEDNOTLIVYM kapitolam (ne jen k titulu) - viz [getChapterComments].
     * Vychozi = zadny zdroj neposkytuje, appka tak nemusi zkouset stahovat komentare u zdroje,
     * ktery zadne nema. */
    val supportsChapterComments: Boolean get() = false

    /** Komentare ke KONKRETNI kapitole. Vola se az line, kdyz uzivatel otevre panel komentaru v
     * ctecce (viz ReaderViewModel) - NE automaticky pri otevreni kapitoly, aby appka nedelala
     * network navic u vetsiny cteni, kdy uzivatel komentare vubec neotevre. */
    suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> = emptyList()
```

- [ ] **Step 2: Přidat `MadaraCommentStyle` enum a `commentStyle` pole do `MadaraSelectors`**

V `app/src/main/kotlin/com/haise/jiyu/source/madara/MadaraSource.kt` najít definici `data class MadaraSelectors` (aktuálně obsahuje pole `listItem`, `titleLink`, `description`, `status`, `chapterList`, `pageImage`, `novelContent` a `companion object { val DEFAULT = MadaraSelectors() }`). Přidat NOVÝ enum před `data class MadaraSelectors` a nové pole DOVNITŘ `MadaraSelectors` (za `novelContent`, před uzavírací `)`):

```kotlin
enum class MadaraCommentStyle { WPDISCUZ, NATIVE_WP }
```

```kotlin
    /** null = zdroj (tenhle konkretni web) komentare k pripadne kapitole neposkytuje, nebo
     * pouziva Disqus (nescrapovatelny bez JS) - vetsina Madara webu. Nastavuje se explicitne
     * jen pro zive overene weby (viz SourceManager.kt). */
    val commentStyle: MadaraCommentStyle? = null,
```

- [ ] **Step 3: Přidat `supportsChapterComments`/`getChapterComments` override do `MadaraSource`**

V TÉŽE souboru, uvnitř `class MadaraSource`, přidat za `override val isAdult: Boolean get() = isAdultOverride` (na začátku třídy):

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

- [ ] **Step 4: Zapojit `commentStyle` u 7 existujících `MadaraSource(...)` volání v `SourceManager.kt`**

V `app/src/main/kotlin/com/haise/jiyu/source/SourceManager.kt` upravit těchto 7 řádků (najít podle `id` v prvním parametru):

Za `contentTypeOverride = "MANHUA"` u `wuxiaworldsite` přidat `selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ),` - PŘESNĚJI, celé volání dnes vypadá takto:
```kotlin
        MadaraSource(
            "wuxiaworldsite", "Wuxiaworld.site", "https://wuxiaworld.site", client,
            contentTypeOverride = "NOVEL",
            popularUrl = { root, page, orderby -> "$root/novels-list/page/$page/?m_orderby=$orderby" },
        ),
```
nahradit za:
```kotlin
        MadaraSource(
            "wuxiaworldsite", "Wuxiaworld.site", "https://wuxiaworld.site", client,
            contentTypeOverride = "NOVEL",
            selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ),
            popularUrl = { root, page, orderby -> "$root/novels-list/page/$page/?m_orderby=$orderby" },
        ),
```

`manhuahot` - dnes: `MadaraSource("manhuahot",     "Manhua Hot",         "https://manhuahot.com",        client, contentTypeOverride = "MANHUA"),`
nahradit za: `MadaraSource("manhuahot", "Manhua Hot", "https://manhuahot.com", client, contentTypeOverride = "MANHUA", selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.NATIVE_WP)),`

`mangazin` - dnes: `MadaraSource("mangazin",      "Mangazin",           "https://mangazin.org",         client, contentTypeOverride = "MANHUA"),`
nahradit za: `MadaraSource("mangazin", "Mangazin", "https://mangazin.org", client, contentTypeOverride = "MANHUA", selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ)),`

`mangagg` - dnes:
```kotlin
        MadaraSource(
            "mangagg", "MangaGG", "https://mangagg.com", client,
            contentTypeOverride = "MANHUA",
            popularUrl = { root, page, orderby -> "$root/comic/page/$page/?m_orderby=$orderby" },
        ),
```
nahradit za:
```kotlin
        MadaraSource(
            "mangagg", "MangaGG", "https://mangagg.com", client,
            contentTypeOverride = "MANHUA",
            selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ),
            popularUrl = { root, page, orderby -> "$root/comic/page/$page/?m_orderby=$orderby" },
        ),
```

`mangaread` - dnes: `MadaraSource("mangaread",     "MangaRead",          "https://www.mangaread.org",    client, contentTypeOverride = "MANGA"),`
nahradit za: `MadaraSource("mangaread", "MangaRead", "https://www.mangaread.org", client, contentTypeOverride = "MANGA", selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ)),`

`mangadistrict` - dnes:
```kotlin
        MadaraSource(
            "mangadistrict", "Manga District", "https://mangadistrict.com", client,
            contentTypeOverride = "MANHWA", isAdultOverride = true,
            popularUrl = { root, page, orderby -> "$root/series/page/$page/?m_orderby=$orderby" },
        ),
```
nahradit za:
```kotlin
        MadaraSource(
            "mangadistrict", "Manga District", "https://mangadistrict.com", client,
            contentTypeOverride = "MANHWA", isAdultOverride = true,
            selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.NATIVE_WP),
            popularUrl = { root, page, orderby -> "$root/series/page/$page/?m_orderby=$orderby" },
        ),
```

`manhuanext` - dnes: `MadaraSource("manhuanext", "ManhuaNext", "https://manhuanext.com", client, contentTypeOverride = "MANHUA"),`
nahradit za: `MadaraSource("manhuanext", "ManhuaNext", "https://manhuanext.com", client, contentTypeOverride = "MANHUA", selectors = MadaraSelectors(commentStyle = MadaraCommentStyle.WPDISCUZ)),`

(Import `MadaraCommentStyle` do `SourceManager.kt` - přidat `import com.haise.jiyu.source.madara.MadaraCommentStyle` vedle existujícího `import com.haise.jiyu.source.madara.MadaraSelectors`.)

- [ ] **Step 5: Ověřit kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:compileDebugKotlin; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`.

- [ ] **Step 6: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/source/MangaSource.kt \
        app/src/main/kotlin/com/haise/jiyu/source/madara/MadaraSource.kt \
        app/src/main/kotlin/com/haise/jiyu/source/SourceManager.kt
git commit -m "feat: rozhrani MangaSource + komentare ke kapitole u 7 MadaraSource zdroju (wpDiscuz + nativni WP)"
```

---

## Task 3: Zbylých 5 non-Madara zdrojů (MangaK, Comizy, Thunderscans, ScytheScans, LagoonScans)

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/mangak/MangaKSource.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/comizy/ComizySource.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/thunderscans/ThunderscansSource.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/scythescans/ScytheScansSource.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/source/lagoonscans/LagoonScansSource.kt`

**Interfaces:**
- Consumes: `parseWpDiscuzComments`, `parseMangaReaderJsonComments`, `ChapterComment` (Task 1), `MangaSource.supportsChapterComments`/`getChapterComments` výchozí implementace (Task 2 Step 1).
- Produces: override těchto dvou členů na 5 konkrétních třídách - konzumuje Task 4/5.

- [ ] **Step 1: `MangaKSource.kt`**

Přidat za `override val homepageUrl get() = base` (nebo kamkoliv do těla třídy, konzistentně s ostatními override):

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

- [ ] **Step 2: `ComizySource.kt`**

Přidat do těla třídy (POZOR: `chapter.url` je tu už absolutní, jiný kód než MangaK - `ComizySource.getChapterList` ukládá `url = base + c.optString("url")`):

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

- [ ] **Step 3: `ThunderscansSource.kt`, `ScytheScansSource.kt`, `LagoonScansSource.kt`**

Do KAŽDÉ z těchto tří tříd přidat stejný kód (všechny tři mají vlastní `private fun get(url: String): String` metodu vracející syrové HTML a už importují `org.jsoup.Jsoup`):

```kotlin
    override val supportsChapterComments: Boolean get() = true

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                com.haise.jiyu.source.comments.parseWpDiscuzComments(Jsoup.parse(get(chapter.url)))
            } catch (_: Exception) { emptyList() }
        }
```

- [ ] **Step 4: Ověřit kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:compileDebugKotlin; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`.

- [ ] **Step 5: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/source/mangak/MangaKSource.kt \
        app/src/main/kotlin/com/haise/jiyu/source/comizy/ComizySource.kt \
        app/src/main/kotlin/com/haise/jiyu/source/thunderscans/ThunderscansSource.kt \
        app/src/main/kotlin/com/haise/jiyu/source/scythescans/ScytheScansSource.kt \
        app/src/main/kotlin/com/haise/jiyu/source/lagoonscans/LagoonScansSource.kt
git commit -m "feat: komentare ke kapitole u MangaK/Comizy (JSON) a Thunderscans/ScytheScans/LagoonScans (wpDiscuz)"
```

---

## Task 4: MangaRepository + ReaderViewModel (líné načítání)

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt`

**Interfaces:**
- Consumes: `MangaSource.supportsChapterComments`/`getChapterComments` (Task 3), `ChapterComment` (Task 1).
- Produces: `MangaRepository.getChapterComments(sourceId: String, chapterUrl: String): List<ChapterComment>`, `MangaRepository.sourceSupportsChapterComments(sourceId: String): Boolean`, `ReaderViewModel.chapterComments: StateFlow<List<ChapterComment>>`, `ReaderViewModel.commentsLoading: StateFlow<Boolean>`, `ReaderViewModel.commentsSupported: StateFlow<Boolean>`, `ReaderViewModel.loadChapterComments(): Unit`. Konzumuje Task 5 (UI).

- [ ] **Step 1: Přidat metody do `MangaRepository.kt`**

Přidat za existující `suspend fun getChapterPages(sourceId: String, chapterUrl: String, mangaUrl: String): List<com.haise.jiyu.source.Page> { ... }`:

```kotlin
    suspend fun getChapterComments(sourceId: String, chapterUrl: String): List<com.haise.jiyu.source.comments.ChapterComment> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getChapterComments(SChapter(sourceId, "", chapterUrl, "", 0f, 0L))
    }

    suspend fun sourceSupportsChapterComments(sourceId: String): Boolean =
        sourceManager.getById(sourceId)?.supportsChapterComments ?: false
```

- [ ] **Step 2: Přidat stav a `loadChapterComments()` do `ReaderViewModel.kt`**

Přidat za existující `private val _comickUnavailable = MutableStateFlow(false)` / `val comickUnavailable: StateFlow<Boolean> = _comickUnavailable.asStateFlow()`:

```kotlin
    private val _chapterComments = MutableStateFlow<List<com.haise.jiyu.source.comments.ChapterComment>>(emptyList())
    val chapterComments: StateFlow<List<com.haise.jiyu.source.comments.ChapterComment>> = _chapterComments.asStateFlow()

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

- [ ] **Step 3: Přidat reset do `loadChapter(id: String)` - DVĚ samostatná místa**

Místo A - za existující `_translatedPages.value = emptyMap()` (v úvodním resetovacím bloku, PŘED `val chapter = repository.getChapter(id) ...` - `chapter` proměnná tam ještě neexistuje):
```kotlin
        _chapterComments.value = emptyList()
        commentsJob?.cancel()
        commentsJob = null
```

Místo B - za `currentChapter = chapter` (existující řádek hned po `val chapter = repository.getChapter(id) ?: run { ... }`):
```kotlin
        _commentsSupported.value = repository.sourceSupportsChapterComments(chapter.sourceId)
```

- [ ] **Step 4: Ověřit kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:compileDebugKotlin; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`.

- [ ] **Step 5: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt
git commit -m "feat: line nacitani komentaru ke kapitole (MangaRepository + ReaderViewModel)"
```

---

## Task 5: UI - tlačítko, bottom sheet, zapojení do čtečky

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderControls.kt`
- Create: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ChapterCommentsBottomSheet.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderContent.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: `ReaderViewModel.chapterComments`/`commentsLoading`/`commentsSupported`/`loadChapterComments()` (Task 4), `ChapterComment` (Task 1).

Tenhle task nemá vlastní automatizovaný test (čistě UI) - ověřuje se kompilací a finálním regresním testem v Task 6.

- [ ] **Step 1: Přidat stringy do všech 4 lokalizačních souborů**

`app/src/main/res/values/strings.xml` - za `reader_glossary_button` (řádek 704):
```xml
    <string name="reader_comments_button">Komentáře</string>
    <string name="reader_comments_empty">Tahle kapitola zatím nemá komentáře</string>
```

`app/src/main/res/values-en/strings.xml` - za `reader_glossary_button` (řádek 654):
```xml
    <string name="reader_comments_button">Comments</string>
    <string name="reader_comments_empty">This chapter has no comments yet</string>
```

`app/src/main/res/values-es/strings.xml` - za `reader_glossary_button` (řádek 651):
```xml
    <string name="reader_comments_button">Comentarios</string>
    <string name="reader_comments_empty">Este capítulo aún no tiene comentarios</string>
```

`app/src/main/res/values-fr/strings.xml` - za `reader_glossary_button` (řádek 651):
```xml
    <string name="reader_comments_button">Commentaires</string>
    <string name="reader_comments_empty">Ce chapitre n'a pas encore de commentaires</string>
```

- [ ] **Step 2: Přidat tlačítko do `ReaderControls.kt`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderControls.kt`:

1. Přidat parametry do `fun ReaderBottomPanel(...)` za `onShowGlossary: () -> Unit,`:
```kotlin
    onShowComments: () -> Unit = {},
    commentsSupported: Boolean = false,
```

2. V těle `ReaderBottomPanel`, ve volání `ReaderAdvancedSheetContent(...)`, přidat za `onShowGlossary = onShowGlossary,`:
```kotlin
                onShowComments = onShowComments,
                commentsSupported = commentsSupported,
```

3. Přidat parametry do `private fun ReaderAdvancedSheetContent(...)` za `onShowGlossary: () -> Unit,`:
```kotlin
    onShowComments: () -> Unit = {},
    commentsSupported: Boolean = false,
```

4. V těle `ReaderAdvancedSheetContent`, najít existující tlačítko glosáře (`Text(stringResource(R.string.reader_glossary_button), ..., modifier = Modifier...clickable(onClick = onShowGlossary)...)`) a za JEHO uzavírací `)` (konec toho `Text(...)` volání, uvnitř téhož `Row`) přidat nové tlačítko, viditelné jen když je podporováno:
```kotlin
            if (commentsSupported) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.reader_comments_button),
                    color = Color(0xFF8B5CF6),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(onClick = onShowComments)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
```

- [ ] **Step 3: Vytvořit `ChapterCommentsBottomSheet.kt`**

```kotlin
package com.haise.jiyu.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.source.comments.ChapterComment

// ── Komentare ke KONKRETNI kapitole (ne k titulu - to resi ComicK v MangaDetailScreen) ──
// Vola se az na vyzadani (viz ReaderViewModel.loadChapterComments), otevira se tlacitkem
// vedle Slovniku v pokrocilych nastavenich ctecky (viz ReaderControls).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterCommentsBottomSheet(
    comments: List<ChapterComment>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.reader_comments_button),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            when {
                loading && comments.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { JiyuLoadingIndicator(size = 28.dp, strokeWidth = 2.dp) }

                comments.isEmpty() -> Text(
                    stringResource(R.string.reader_comments_empty),
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                )

                else -> comments.forEach { comment -> ChapterCommentRow(comment) }
            }
        }
    }
}

@Composable
private fun ChapterCommentRow(comment: ChapterComment) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AsyncImage(
            model = comment.avatarUrl,
            contentDescription = comment.author,
            modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF1A2340)),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    text = chapterCommentRelativeTime(comment.createdAt),
                    color = Color(0xFFB0BEC5).copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(comment.content, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun chapterCommentRelativeTime(createdAtMs: Long): String {
    if (createdAtMs <= 0L) return ""
    val diffMin = (System.currentTimeMillis() - createdAtMs) / 60_000L
    return when {
        diffMin < 1     -> "teď"
        diffMin < 60    -> "před ${diffMin} min"
        diffMin < 1440  -> "před ${diffMin / 60} h"
        diffMin < 43200 -> "před ${diffMin / 1440} dny"
        else            -> java.text.SimpleDateFormat("d. M. yyyy", java.util.Locale.getDefault()).format(java.util.Date(createdAtMs))
    }
}
```

`Modifier.background(...)` v `AsyncImage` vyžaduje `import androidx.compose.foundation.background` - přidat do importů výše (chybí v seznamu nad kódem, doplnit spolu s ním).

- [ ] **Step 4: Zapojit do `ReaderContent.kt`**

1. Přidat nové parametry do `fun ReaderContent(...)` za `onRemoveGlossaryEntry: (GlossaryEntity) -> Unit = {},`:
```kotlin
    chapterComments: List<com.haise.jiyu.source.comments.ChapterComment> = emptyList(),
    commentsLoading: Boolean = false,
    commentsSupported: Boolean = false,
    onShowComments: () -> Unit = {},
```

2. Přidat nový lokální stav za existující `var showGlossarySheet by remember { mutableStateOf(false) }`:
```kotlin
    var showCommentsSheet by remember { mutableStateOf(false) }
```

3. Ve volání `ReaderBottomPanel(...)` přidat za `onShowGlossary = { showGlossarySheet = true },`:
```kotlin
                    onShowComments = { showCommentsSheet = true; onShowComments() },
                    commentsSupported = commentsSupported,
```

4. Za existující blok:
```kotlin
    if (showGlossarySheet) {
        GlossaryBottomSheet(
            glossary = glossary,
            targetLanguage = targetLanguage,
            onAdd = onAddGlossaryEntry,
            onRemove = onRemoveGlossaryEntry,
            onDismiss = { showGlossarySheet = false },
        )
    }
```
přidat:
```kotlin

    if (showCommentsSheet) {
        ChapterCommentsBottomSheet(
            comments = chapterComments,
            loading = commentsLoading,
            onDismiss = { showCommentsSheet = false },
        )
    }
```

- [ ] **Step 5: Zapojit do `ReaderScreen.kt`**

1. Přidat `collectAsState()` za existující `val glossary             by viewModel.glossary.collectAsState()` (řádek 111):
```kotlin
    val chapterComments       by viewModel.chapterComments.collectAsState()
    val commentsLoading       by viewModel.commentsLoading.collectAsState()
    val commentsSupported     by viewModel.commentsSupported.collectAsState()
```

2. Ve volání `ReaderContent(...)` (začíná řádek 263) přidat za `onRemoveGlossaryEntry = { viewModel.removeGlossaryEntry(it) },` (řádek 340):
```kotlin
                chapterComments = chapterComments,
                commentsLoading = commentsLoading,
                commentsSupported = commentsSupported,
                onShowComments = { viewModel.loadChapterComments() },
```

- [ ] **Step 6: Ověřit kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:compileDebugKotlin; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`.

- [ ] **Step 7: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderControls.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ChapterCommentsBottomSheet.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderContent.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-fr/strings.xml
git commit -m "feat: UI pro komentare ke kapitole - tlacitko a bottom sheet v ctecce"
```

---

## Task 6: Finální regresní test

**Files:** žádné (jen ověření).

- [ ] **Step 1: Spustit celou testovací sadu bez filtru**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest; echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`, žádný regresní pád - včetně nových `WpDiscuzCommentsTest` (5), `NativeWpCommentsTest` (5), `MangaReaderJsonCommentsTest` (7) a všech existujících testů.

- [ ] **Step 2: Pokud vše prochází, žádný další commit není potřeba (Task 1-5 už jsou commitnuté).**
