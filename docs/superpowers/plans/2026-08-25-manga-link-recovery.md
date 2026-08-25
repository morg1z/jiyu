# Manga Link Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Když titul v knihovně přestane fungovat, protože zdroj přestavěl web (URL se změnila, titul tam pořád je), appka ho při pull-to-refreshi na detailu sama dohledá podle názvu na TOM SAMÉM zdroji a přemapuje na novou URL, se zachováním postupu čtení.

**Architecture:** Dvě čisté, JVM-testovatelné funkce (výběr kandidáta podle názvu, napárování starých/nových kapitol podle čísla) v novém souboru. Nová DAO metoda přemapuje existující řádek kapitoly (id/url/metadata) BEZE ZMĚNY read/download stavu. Orchestrační funkce v `MangaRepository` tyhle kusy skládá dohromady a je zapojená jako fallback do existujícího pull-to-refresh na detailu titulu.

**Tech Stack:** Kotlin, Room (raw `@Query` UPDATE), JUnit4 (JVM testy), Robolectric (existující `ChapterDaoTest.kt`).

**Spec:** `docs/superpowers/specs/2026-08-25-manga-link-recovery-design.md`

## Global Constraints

- Práce probíhá přímo na `master` (zavedená konvence repa, žádná feature branch/worktree).
- Žádný release/verze appky v rámci tohoto plánu.
- `MangaEntity.id` se NIKDY nemění (skutečný SQL `ForeignKey` z `MangaCategoryEntity` bez `ON UPDATE CASCADE` - viz spec) - mění se jen `url` a metadata.
- `ChapterEntity.id` se přemapovat SMÍ (žádný `ForeignKey` na něj v projektu není).
- ComicK (`sourceId == "comick"`) je z recovery vyloučen.
- Žádné UI/Compose soubory se nemění - jen `MangaDetailViewModel.kt`'s `catch` blok (logika, ne UI).
- Recovery nikdy nesmí zhoršit současné chování - jakákoli chyba uvnitř recovery vede na PŮVODNÍ chybovou hlášku, ne na novou.

---

### Task 1: Čisté funkce `findBestTitleMatch` a `planChapterMigration`

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/data/repository/MangaLinkRecovery.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/data/repository/MangaLinkRecoveryTest.kt`

**Interfaces:**
- Produces: `fun findBestTitleMatch(candidates: List<SManga>, originalTitle: String): SManga?` a `data class ChapterMigrationPlan(val relink: List<Pair<ChapterEntity, SChapter>>, val newOnly: List<SChapter>)` + `fun planChapterMigration(oldChapters: List<ChapterEntity>, newChapters: List<SChapter>): ChapterMigrationPlan` v balíčku `com.haise.jiyu.data.repository` - Task 3 tyhle funkce/typ volá BEZ importu (stejný balíček jako `MangaRepository.kt`).
- Consumes: `com.haise.jiyu.source.SManga`, `com.haise.jiyu.source.SChapter` (existující datové třídy, `app/src/main/kotlin/com/haise/jiyu/source/MangaSource.kt`), `com.haise.jiyu.data.db.entity.ChapterEntity` (existující, pole `id: String`, `chapterNumber: Float`).

- [ ] **Step 1: Napiš selhávající testy**

Vytvoř `app/src/test/kotlin/com/haise/jiyu/data/repository/MangaLinkRecoveryTest.kt`:

```kotlin
package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaLinkRecoveryTest {

    private fun sManga(title: String, url: String = "https://example.com/$title") =
        SManga(sourceId = "test", url = url, title = title, coverUrl = null)

    // ── findBestTitleMatch ──────────────────────────────────────────────────

    @Test
    fun `exact case-insensitive match among multiple candidates wins`() {
        val candidates = listOf(sManga("Other Title"), sManga("Solo Leveling"), sManga("Another"))
        val result = findBestTitleMatch(candidates, "solo leveling")
        assertEquals("Solo Leveling", result?.title)
    }

    @Test
    fun `no exact match but exactly one candidate is used`() {
        val candidates = listOf(sManga("Solo Leveling: Ragnarok"))
        val result = findBestTitleMatch(candidates, "Solo Leveling")
        assertEquals("Solo Leveling: Ragnarok", result?.title)
    }

    @Test
    fun `no exact match and multiple candidates gives up`() {
        val candidates = listOf(sManga("Solo Leveling: Ragnarok"), sManga("Solo Leveling Side Story"))
        assertNull(findBestTitleMatch(candidates, "Solo Leveling"))
    }

    @Test
    fun `two exact matches is ambiguous and gives up`() {
        val candidates = listOf(sManga("Solo Leveling", "https://a.com"), sManga("Solo Leveling", "https://b.com"))
        assertNull(findBestTitleMatch(candidates, "Solo Leveling"))
    }

    @Test
    fun `empty candidate list gives up`() {
        assertNull(findBestTitleMatch(emptyList(), "Solo Leveling"))
    }

    // ── planChapterMigration ────────────────────────────────────────────────

    private fun oldChapter(id: String, number: Float) = ChapterEntity(
        id = id, mangaId = "manga-1", sourceId = "test", url = "https://old.com/$id",
        name = "Ch $number", chapterNumber = number, dateUpload = 0L,
    )

    private fun newChapter(url: String, number: Float) = SChapter(
        sourceId = "test", mangaUrl = "https://new.com/manga", url = url,
        name = "Ch $number", chapterNumber = number, dateUpload = 100L,
    )

    @Test
    fun `matching chapter numbers are planned for relink`() {
        val old = listOf(oldChapter("old-1", 1f), oldChapter("old-2", 2f))
        val new = listOf(newChapter("https://new.com/1", 1f), newChapter("https://new.com/2", 2f))

        val plan = planChapterMigration(old, new)

        assertEquals(2, plan.relink.size)
        assertEquals("old-1", plan.relink[0].first.id)
        assertEquals("https://new.com/1", plan.relink[0].second.url)
        assertEquals(0, plan.newOnly.size)
    }

    @Test
    fun `new chapter number with no old match is newOnly`() {
        val old = listOf(oldChapter("old-1", 1f))
        val new = listOf(newChapter("https://new.com/1", 1f), newChapter("https://new.com/2", 2f))

        val plan = planChapterMigration(old, new)

        assertEquals(1, plan.relink.size)
        assertEquals(1, plan.newOnly.size)
        assertEquals(2f, plan.newOnly[0].chapterNumber)
    }

    @Test
    fun `old chapter number with no new match is left out of the plan`() {
        val old = listOf(oldChapter("old-1", 1f), oldChapter("old-2", 2f))
        val new = listOf(newChapter("https://new.com/1", 1f))

        val plan = planChapterMigration(old, new)

        assertEquals(1, plan.relink.size)
        assertEquals("old-1", plan.relink[0].first.id)
    }

    @Test
    fun `duplicate chapter number in newChapters only uses the first occurrence`() {
        val old = listOf(oldChapter("old-1", 1f))
        val new = listOf(newChapter("https://new.com/1a", 1f), newChapter("https://new.com/1b", 1f))

        val plan = planChapterMigration(old, new)

        assertEquals(1, plan.relink.size)
        assertEquals("https://new.com/1a", plan.relink[0].second.url)
        assertEquals(0, plan.newOnly.size)
    }
}
```

- [ ] **Step 2: Ověř, že testy selžou**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --tests "com.haise.jiyu.data.repository.MangaLinkRecoveryTest" --console=plain > /tmp/mlr_test1.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/mlr_test1.log
```

Očekávej: kompilační chybu (`findBestTitleMatch`/`planChapterMigration`/`ChapterMigrationPlan` neexistují).

- [ ] **Step 3: Vytvoř `MangaLinkRecovery.kt`**

```kotlin
package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga

/**
 * Vybere nejjistějšího kandidáta pro "tohle je stejný titul, jen na nové URL" - viz
 * [MangaRepository.recoverMangaLink]. Radši žádnou shodu než špatnou: pokud je přesných
 * shod (case-insensitive) víc, nebo není žádná přesná a kandidátů je víc než 1, appka se
 * vzdá (vrátí null).
 */
fun findBestTitleMatch(candidates: List<SManga>, originalTitle: String): SManga? {
    val exact = candidates.filter { it.title.equals(originalTitle, ignoreCase = true) }
    return when {
        exact.size == 1 -> exact.first()
        exact.isEmpty() && candidates.size == 1 -> candidates.single()
        else -> null
    }
}

data class ChapterMigrationPlan(
    val relink: List<Pair<ChapterEntity, SChapter>>,
    val newOnly: List<SChapter>,
)

/**
 * Napáruje STARÉ (uložené) a NOVÉ (čerstvě stažené ze zdroje) kapitoly podle čísla kapitoly -
 * viz [MangaRepository.recoverMangaLink]. Předpokládá, že [newChapters] nemá duplicitní čísla
 * (agregované zdroje typu ComicK, kde by to neplatilo, [MangaRepository.recoverMangaLink]
 * vůbec nevolá). Duplicitní číslo v [newChapters] se přesto ošetří použitím jen PRVNÍHO
 * výskytu, aby se žádný starý řádek nepřemapoval dvakrát.
 */
fun planChapterMigration(oldChapters: List<ChapterEntity>, newChapters: List<SChapter>): ChapterMigrationPlan {
    val oldByNumber = oldChapters.associateBy { it.chapterNumber }
    val seenNumbers = mutableSetOf<Float>()
    val relink = mutableListOf<Pair<ChapterEntity, SChapter>>()
    val newOnly = mutableListOf<SChapter>()
    for (newCh in newChapters) {
        if (!seenNumbers.add(newCh.chapterNumber)) continue
        val old = oldByNumber[newCh.chapterNumber]
        if (old != null) relink.add(old to newCh) else newOnly.add(newCh)
    }
    return ChapterMigrationPlan(relink, newOnly)
}
```

- [ ] **Step 4: Ověř, že testy projdou**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --tests "com.haise.jiyu.data.repository.MangaLinkRecoveryTest" --console=plain > /tmp/mlr_test2.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/mlr_test2.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`, 9 testů zelených.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/ilekr/Desktop/jiyu
git add app/src/main/kotlin/com/haise/jiyu/data/repository/MangaLinkRecovery.kt app/src/test/kotlin/com/haise/jiyu/data/repository/MangaLinkRecoveryTest.kt
git commit -m "feat: ciste funkce pro vyber kandidata a naparovani kapitol pri oprave odkazu"
```

---

### Task 2: DAO metoda `ChapterDao.relink`

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/db/ChapterDao.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/data/db/ChapterDaoTest.kt` (existující soubor, přidat test)

**Interfaces:**
- Produces: `suspend fun relink(oldId: String, newId: String, newUrl: String, newName: String, dateUpload: Long, scanlationGroup: String?, volume: String?, groupsJson: String?)` na `ChapterDao` - Task 3 tuhle metodu volá.

- [ ] **Step 1: Napiš selhávající test**

V `app/src/test/kotlin/com/haise/jiyu/data/db/ChapterDaoTest.kt` přidej import `import org.junit.Assert.assertNull` (pokud tam ještě není) a novou testovací metodu na konec třídy `ChapterDaoTest` (před poslední `}`):

```kotlin
    @Test
    fun `relink preserves read and download state while changing id and url`() = runTest {
        dao.insertNewOnly(listOf(chapter("old-id", read = true, status = DownloadStatus.DOWNLOADED, chapterNumber = 5f)))

        dao.relink(
            oldId = "old-id",
            newId = "new-id",
            newUrl = "https://new.example.com/ch5",
            newName = "Chapter 5 (renamed)",
            dateUpload = 123456L,
            scanlationGroup = "Some Group",
            volume = "2",
            groupsJson = null,
        )

        val relinked = dao.getById("new-id")!!
        assertEquals(true, relinked.read)
        assertEquals(DownloadStatus.DOWNLOADED, relinked.downloadStatus)
        assertEquals("https://new.example.com/ch5", relinked.url)
        assertEquals("Chapter 5 (renamed)", relinked.name)
        assertEquals("Some Group", relinked.scanlationGroup)
        assertNull(dao.getById("old-id"))
    }
```

- [ ] **Step 2: Ověř, že test selže**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --tests "com.haise.jiyu.data.db.ChapterDaoTest" --console=plain > /tmp/relink_test1.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/relink_test1.log
```

Očekávej: kompilační chybu (`relink` na `ChapterDao` neexistuje).

- [ ] **Step 3: Přidej metodu `relink` do `ChapterDao`**

Najdi v `app/src/main/kotlin/com/haise/jiyu/data/db/ChapterDao.kt` (existující kód):

```kotlin
    @Query("UPDATE chapter SET lastScrollOffset = :offset, lastReadAt = :lastReadAt WHERE id = :id")
    suspend fun updateScrollOffset(id: String, offset: Int, lastReadAt: Long)
```

Nahraď za (přidána nová metoda hned za `updateScrollOffset`):

```kotlin
    @Query("UPDATE chapter SET lastScrollOffset = :offset, lastReadAt = :lastReadAt WHERE id = :id")
    suspend fun updateScrollOffset(id: String, offset: Int, lastReadAt: Long)

    /** Přemapuje kapitolu na novou URL/id při opravě odkazu (viz MangaRepository.recoverMangaLink) -
     * záměrně NEMĚNÍ read/lastPageRead/lastReadAt/lastScrollOffset/downloadStatus/localPath/
     * pageCount/discoveredAt, aby uživatel o postup čtení/stažené soubory nepřišel. */
    @Query("""
        UPDATE chapter SET id = :newId, url = :newUrl, name = :newName, dateUpload = :dateUpload,
               scanlationGroup = :scanlationGroup, volume = :volume, groupsJson = :groupsJson
        WHERE id = :oldId
    """)
    suspend fun relink(
        oldId: String,
        newId: String,
        newUrl: String,
        newName: String,
        dateUpload: Long,
        scanlationGroup: String?,
        volume: String?,
        groupsJson: String?,
    )
```

- [ ] **Step 4: Ověř, že test projde**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --tests "com.haise.jiyu.data.db.ChapterDaoTest" --console=plain > /tmp/relink_test2.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/relink_test2.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`, 4 testy zelené (3 existující + 1 nový).

- [ ] **Step 5: Commit**

```bash
cd /c/Users/ilekr/Desktop/jiyu
git add app/src/main/kotlin/com/haise/jiyu/data/db/ChapterDao.kt app/src/test/kotlin/com/haise/jiyu/data/db/ChapterDaoTest.kt
git commit -m "feat: ChapterDao.relink - premapovani kapitoly na novou URL/id se zachovanim postupu"
```

---

### Task 3: `MangaRepository.recoverMangaLink`

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt:320` (hned za konec `refreshChapters`, před `getChapterPages`)

**Interfaces:**
- Consumes: `findBestTitleMatch`, `planChapterMigration`, `ChapterMigrationPlan` z Task 1 (stejný balíček, bez importu); `ChapterDao.relink(...)` z Task 2 (`chapterDao` už je injectované pole `MangaRepository`); existující `sourceManager: SourceManager`, `mangaDao: MangaDao`, `chapterDao: ChapterDao`, `chapterId(chapter: SChapter): String` (private fun, konec souboru), `serializeChapterGroups(groups: List<SGroup>): String?` (top-level internal fun, konec souboru).
- Produces: `suspend fun recoverMangaLink(mangaId: String): Boolean` na `MangaRepository` - Task 4 tuhle metodu volá.

Tenhle task nemá vlastní automatizovaný test (síť + DB dohromady - viz spec "Testování": orchestrace je "poskládej čisté kusy dohromady", pokryto testy z Task 1+2). Místo TDD kroků: přesná editace + kompilace.

- [ ] **Step 1: Přidej `recoverMangaLink` do `MangaRepository`**

Najdi (existující kód, konec `refreshChapters`):

```kotlin
        val rowIds = chapterDao.insertNewOnly(entities)
        return entities.filterIndexed { index, _ -> rowIds[index] != -1L }
    }

    suspend fun getChapterPages(sourceId: String, chapterUrl: String, mangaUrl: String): List<com.haise.jiyu.source.Page> {
```

Nahraď za (přidána nová funkce mezi `refreshChapters` a `getChapterPages`):

```kotlin
        val rowIds = chapterDao.insertNewOnly(entities)
        return entities.filterIndexed { index, _ -> rowIds[index] != -1L }
    }

    /**
     * Zkusí najít titul znovu NA STEJNÉM zdroji podle názvu, když jeho uložená URL přestala
     * fungovat (web se přestavěl, titul samotný pořád existuje) - viz [findBestTitleMatch]/
     * [planChapterMigration]. Nikdy nemění [MangaEntity.id] (stabilní identita napříč appkou -
     * skutečný SQL ForeignKey z MangaCategoryEntity na ni nemá ON UPDATE CASCADE), jen
     * url/detaily. Volá se jen jako fallback z MangaDetailViewModel.refreshChapters() po
     * selhání běžného obnovení, nikdy pro zdroj ComicK (metadatový katalog, nemá vlastní
     * "web" k opravě).
     *
     * Vrací true, pokud se povedlo najít a přemapovat.
     */
    suspend fun recoverMangaLink(mangaId: String): Boolean {
        val existing = mangaDao.getById(mangaId) ?: return false
        if (existing.sourceId == "comick") return false
        val source = sourceManager.getById(existing.sourceId) ?: return false

        val candidates = try {
            source.search(existing.title)
        } catch (_: Exception) {
            return false
        }
        val match = findBestTitleMatch(candidates, existing.title) ?: return false
        if (match.url == existing.url) return false

        val newChapters = try {
            source.getChapterList(match)
        } catch (_: Exception) {
            emptyList()
        }
        if (newChapters.isEmpty()) return false

        val oldChapters = chapterDao.getAllForManga(mangaId)
        val plan = planChapterMigration(oldChapters, newChapters)

        plan.relink.forEach { (old, new) ->
            chapterDao.relink(
                oldId = old.id,
                newId = chapterId(new),
                newUrl = new.url,
                newName = new.name,
                dateUpload = new.dateUpload,
                scanlationGroup = new.scanlationGroup,
                volume = new.volume,
                groupsJson = serializeChapterGroups(new.groups),
            )
        }
        if (plan.newOnly.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val entities = plan.newOnly.map { chapter ->
                ChapterEntity(
                    id = chapterId(chapter),
                    mangaId = mangaId,
                    sourceId = chapter.sourceId,
                    url = chapter.url,
                    name = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    dateUpload = chapter.dateUpload,
                    scanlationGroup = chapter.scanlationGroup,
                    volume = chapter.volume,
                    groupsJson = serializeChapterGroups(chapter.groups),
                    discoveredAt = now,
                )
            }
            chapterDao.insertNewOnly(entities)
        }

        mangaDao.upsert(existing.copy(url = match.url, title = match.title, coverUrl = match.coverUrl ?: existing.coverUrl))
        return true
    }

    suspend fun getChapterPages(sourceId: String, chapterUrl: String, mangaUrl: String): List<com.haise.jiyu.source.Page> {
```

- [ ] **Step 2: Ověř kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat compileDebugKotlin --console=plain > /tmp/mlr_build1.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/mlr_build1.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /c/Users/ilekr/Desktop/jiyu
git add app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt
git commit -m "feat: MangaRepository.recoverMangaLink - oprava odkazu na stejnem zdroji"
```

---

### Task 4: Napojit do `MangaDetailViewModel.refreshChapters()`

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/detail/MangaDetailViewModel.kt:647-666`

**Interfaces:**
- Consumes: `repository.recoverMangaLink(mangaId: String): Boolean` z Task 3; existující `repository.getManga(mangaId: String): MangaEntity?` a `repository.refreshMangaDetails(mangaId: String, manga: SManga)` (obě už v `MangaRepository`, používané jinde v tomhle souboru).

Tenhle task nemá vlastní automatizovaný test (UI-vrstvá logika nad síťovou operací - stejný důvod jako Task 3). Kompilace + manuální ověření.

- [ ] **Step 1: Uprav `catch` blok v `refreshChapters()`**

Najdi (existující kód):

```kotlin
    fun refreshChapters() {
        val current = manga.value ?: return
        if (!networkMonitor.isOnline) {
            _errorMessage.value = appContext.getString(R.string.detail_error_no_internet)
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                val sManga = SManga(current.sourceId, current.url, current.title, current.coverUrl, current.description, current.status, contentType = current.contentType)
                repository.refreshChapters(mangaId, sManga)
                repository.refreshMangaDetails(mangaId, sManga)
            } catch (e: Exception) {
                _errorMessage.value = appContext.getString(R.string.detail_error_refresh_failed, e.toFriendlyMessage())
            } finally {
                _isRefreshing.value = false
            }
        }
    }
```

Nahraď za:

```kotlin
    fun refreshChapters() {
        val current = manga.value ?: return
        if (!networkMonitor.isOnline) {
            _errorMessage.value = appContext.getString(R.string.detail_error_no_internet)
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                val sManga = SManga(current.sourceId, current.url, current.title, current.coverUrl, current.description, current.status, contentType = current.contentType)
                repository.refreshChapters(mangaId, sManga)
                repository.refreshMangaDetails(mangaId, sManga)
            } catch (e: Exception) {
                // Bezny refresh selhal - zkusi se jeste najit titul podle nazvu na STEJNEM
                // zdroji (web mohl prestavet URL, titul porad existuje) drivnez se ukaze
                // chyba. Cokoliv v recovery selze -> tise se spadne zpet na PUVODNI chybu,
                // recovery nikdy nezhorsi soucasne chovani (viz MangaRepository.recoverMangaLink).
                val recovered = try { repository.recoverMangaLink(mangaId) } catch (_: Exception) { false }
                if (recovered) {
                    _errorMessage.value = null
                    val fresh = repository.getManga(mangaId)
                    if (fresh != null) {
                        val freshManga = SManga(fresh.sourceId, fresh.url, fresh.title, fresh.coverUrl, fresh.description, fresh.status, contentType = fresh.contentType)
                        try { repository.refreshMangaDetails(mangaId, freshManga) } catch (_: Exception) { /* kosmeticke detaily, neni kriticke */ }
                    }
                } else {
                    _errorMessage.value = appContext.getString(R.string.detail_error_refresh_failed, e.toFriendlyMessage())
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }
```

- [ ] **Step 2: Ověř kompilaci**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat compileDebugKotlin --console=plain > /tmp/mlr_build2.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/mlr_build2.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 3: Spusť celou unit testovou sadu**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/ilekr/Desktop/jiyu
./gradlew.bat testDebugUnitTest --console=plain > /tmp/mlr_test_all.log 2>&1
echo REAL_EXIT_CODE=$?
tail -40 /tmp/mlr_test_all.log
```

Očekávej: `REAL_EXIT_CODE=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 4: Ruční ověření (pokud je dostupné zařízení bez konfliktu podpisu)**

Otevřít v appce titul v knihovně, na jeho detailu udělat pull-to-refresh a ověřit, že se appka chová stejně jako dřív (žádná regrese) - skutečnou "opravu rozbité URL" nejde spolehlivě ručně vyvolat (potřeboval by se zdroj se skutečně změněnou URL). Pokud instalace na zařízení koliduje s existujícím podpisem (viz `project_jiyu_reader_prefetch` paměť), tenhle krok přeskočit a nechat na uživateli.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/ilekr/Desktop/jiyu
git add app/src/main/kotlin/com/haise/jiyu/ui/detail/MangaDetailViewModel.kt
git commit -m "feat: napojit opravu odkazu do pull-to-refresh na detailu titulu"
```
