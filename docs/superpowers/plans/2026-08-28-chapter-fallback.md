# Fallback pro neúplnou kapitolu (ComicK agregátor) — Implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Appka při otevření kapitoly v ComicK agregovaném režimu zjistí, jestli vybraná kapitola nemá podezřele málo stránek (nahlášený bug: MangaK/The Raider/kap.19 měla 5 stránek místo 11-19 u sousedních), a pokud ano, tiše zkusí přepnout na jiný už nalezený zdroj se stejnou kapitolou. Výsledek se zapamatuje natrvalo, takže se kontrola u té samé kapitoly příště přeskočí.

**Architecture:** Rozšíření existujícího `SourceResolverViewModel.selectCandidate()` (volá se při každém otevření kapitoly ComicK titulu) o kontrolu počtu stránek a fallback na alternativní kandidáty. Výsledek se persistuje do tří nových sloupců na `ChapterEntity`. `ReaderViewModel` zobrazí jednorázovou hlášku, když otevírá kapitolu, která byla takhle přesměrována.

**Tech Stack:** Kotlin, Jetpack Compose, Room (DB migrace), Hilt, JUnit + Robolectric (JVM testy).

**Spec:** `docs/superpowers/specs/2026-08-28-chapter-fallback-design.md` (commit `980bfc8`)

## Global Constraints

- Práh "podezřele krátká kapitola": < 6 stránek (konstanta `SUSPICIOUSLY_SHORT_PAGE_FLOOR = 6`).
- Max. počet alternativních kandidátů ke zkoušení: 3.
- Kontrola se provádí JEN při skutečném otevření konkrétní kapitoly (ne proaktivně při přidání mangy).
- Jakmile appka kapitolu jednou ověří (`verifiedPageCount != null`), příště se kontrola přeskočí.
- Žádná nová UI komponenta navíc v `ReaderScreen` (žádný `Scaffold`/`SnackbarHost`) - hláška se zobrazí stejným vizuálním vzorem jako existující `translationError` banner (`AnimatedVisibility` + barevný `Box` s `Text`, TopCenter).
- Práce probíhá přímo na `master`, bez feature branch (zavedená konvence celé ComicK agregátor iniciativy).
- `JAVA_HOME` musí být nastaven v KAŽDÉM Bash volání zvlášť: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`. Nikdy nekontrolovat výsledek gradlew přes `| tail` (maskuje skutečný exit code) - vždy nechat vypsat plný výstup a zkontrolovat `echo EXIT_CODE=$?` hned po příkazu.

---

## Task 1: DB vrstva - nová pole na ChapterEntity, DAO metoda, migrace

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/db/entity/ChapterEntity.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/db/ChapterDao.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/db/AppDatabase.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/di/AppModule.kt`
- Modify: `app/src/test/kotlin/com/haise/jiyu/data/db/ChapterDaoTest.kt`
- Modify: `app/src/test/kotlin/com/haise/jiyu/data/db/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Produces: `ChapterEntity.verifiedPageCount: Int? = null`, `ChapterEntity.isFallbackSource: Boolean = false`, `ChapterEntity.fallbackChapterId: String? = null`. `ChapterDao.setVerifiedPageCount(id: String, count: Int, isFallback: Boolean, fallbackChapterId: String? = null): Unit` (suspend). `AppDatabase.MIGRATION_34_35`. Tyhle produkty používá Task 3 (`SourceResolverViewModel`) a Task 4 (`ReaderViewModel`).

- [ ] **Step 1: Napsat padající testy pro `setVerifiedPageCount`**

V `app/src/test/kotlin/com/haise/jiyu/data/db/ChapterDaoTest.kt` přidat dva nové testy na konec třídy `ChapterDaoTest` (za `relink preserves read and download state...`):

```kotlin
    @Test
    fun `setVerifiedPageCount writes count and isFallback without touching other fields`() = runTest {
        dao.insertNewOnly(listOf(chapter("ch-1", read = true, status = DownloadStatus.DOWNLOADED)))

        dao.setVerifiedPageCount("ch-1", count = 5, isFallback = false)

        val result = dao.getById("ch-1")!!
        assertEquals(5, result.verifiedPageCount)
        assertEquals(false, result.isFallbackSource)
        assertNull(result.fallbackChapterId)
        assertEquals(true, result.read)
        assertEquals(DownloadStatus.DOWNLOADED, result.downloadStatus)
    }

    @Test
    fun `setVerifiedPageCount can record a redirect to a better chapter`() = runTest {
        dao.insertNewOnly(listOf(chapter("short-ch", chapterNumber = 19f)))
        dao.insertNewOnly(listOf(chapter("better-ch", chapterNumber = 19f)))

        dao.setVerifiedPageCount("short-ch", count = 5, isFallback = false, fallbackChapterId = "better-ch")
        dao.setVerifiedPageCount("better-ch", count = 13, isFallback = true)

        val short = dao.getById("short-ch")!!
        assertEquals(5, short.verifiedPageCount)
        assertEquals(false, short.isFallbackSource)
        assertEquals("better-ch", short.fallbackChapterId)

        val better = dao.getById("better-ch")!!
        assertEquals(13, better.verifiedPageCount)
        assertEquals(true, better.isFallbackSource)
        assertNull(better.fallbackChapterId)
    }
```

- [ ] **Step 2: Ověřit, že testy nekompilují (RED)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.data.db.ChapterDaoTest" 2>&1
echo EXIT_CODE=$?
```
Expected: selhání kompilace - `Unresolved reference: setVerifiedPageCount`, `Unresolved reference: verifiedPageCount`, `Unresolved reference: isFallbackSource`, `Unresolved reference: fallbackChapterId`. `EXIT_CODE` nesmí být `0`.

- [ ] **Step 3: Přidat 3 nová pole na `ChapterEntity`**

V `app/src/main/kotlin/com/haise/jiyu/data/db/entity/ChapterEntity.kt` přidat za pole `discoveredAt` (poslední pole, řádek 48, před uzavírací `)`):

```kotlin
    /** Overeny pocet stranek pri ONLINE cteni (ne stazeni - to je pageCount) - jakmile appka
     * jednou zkontroluje kompletnost kapitoly (viz SourceResolverViewModel fallback), zapise
     * sem vysledek, aby se pri pristim otevreni uz nic znovu nekontrolovalo. null = jeste
     * neoverovano. */
    val verifiedPageCount: Int? = null,
    /** true, pokud appka tuhle kapitolu dotahla z JINEHO zdroje, nez byl puvodne vybrany
     * "nejvhodnejsi" kandidat, protoze puvodni verze byla podezrele kratka - viz
     * SourceResolverViewModel. Ridi jednorazovou hlasku v ctecce (ReaderViewModel.loadChapter). */
    val isFallbackSource: Boolean = false,
    /** Kdyz appka pri kontrole tehle (puvodni, kratke) kapitoly najde lepsi alternativu, ulozi
     * sem ID kapitoly, na kterou se ma misto ni presmerovat - viz SourceResolverViewModel. Bez
     * tohohle by se PUVODNI (kratky) radek nikdy neoznacil jako "jiz overeno" a appka by
     * kontrolu opakovala pri kazdem otevireni znovu, protoze selectCandidate vzdy nejdriv najde
     * puvodniho "nejvhodnejsiho" kandidata, ne rovnou tu nahradni kapitolu. null = beze zmeny. */
    val fallbackChapterId: String? = null,
```

- [ ] **Step 4: Přidat DAO metodu `setVerifiedPageCount`**

V `app/src/main/kotlin/com/haise/jiyu/data/db/ChapterDao.kt` přidat za metodu `relink` (za řádek 74, před komentář `// Manga/kapitola id se generuje...`):

```kotlin
    @Query("""
        UPDATE chapter SET verifiedPageCount = :count, isFallbackSource = :isFallback,
               fallbackChapterId = :fallbackChapterId WHERE id = :id
    """)
    suspend fun setVerifiedPageCount(id: String, count: Int, isFallback: Boolean, fallbackChapterId: String? = null)
```

- [ ] **Step 5: Ověřit, že DAO testy prochází (GREEN)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.data.db.ChapterDaoTest" 2>&1
echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`. `ChapterDaoTest` používá `Room.inMemoryDatabaseBuilder` (odvozuje schéma přímo z entit, migrace se tu netestuje) - stačí nová pole na entitě + nová DAO metoda z kroku 3-4.

- [ ] **Step 6: Zvýšit verzi DB a přidat migraci**

V `app/src/main/kotlin/com/haise/jiyu/data/db/AppDatabase.kt`:
1. Řádek 46: `version = 34,` → `version = 35,`
2. Za `MIGRATION_33_34` (za řádek 331, před uzavírací `}` objektu `companion object`) přidat:

```kotlin
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapter ADD COLUMN verifiedPageCount INTEGER")
                db.execSQL("ALTER TABLE chapter ADD COLUMN isFallbackSource INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chapter ADD COLUMN fallbackChapterId TEXT")
            }
        }
```

- [ ] **Step 7: Zaregistrovat migraci v `AppModule.kt`**

V `app/src/main/kotlin/com/haise/jiyu/di/AppModule.kt` přidat za `AppDatabase.MIGRATION_33_34,` (řádek 343) novou řádku do stejného `.addMigrations(...)` bloku:

```kotlin
                AppDatabase.MIGRATION_33_34,
                AppDatabase.MIGRATION_34_35,
```

- [ ] **Step 8: Napsat padající test pro migraci 34→35**

V `app/src/test/kotlin/com/haise/jiyu/data/db/AppDatabaseMigrationTest.kt`:
1. V `.addMigrations(...)` bloku (za `AppDatabase.MIGRATION_33_34,`, řádek 102) přidat `AppDatabase.MIGRATION_34_35,`.
2. Za blok komentovaný `// MIGRATION_33_34: discoveredAt...` (za řádek 177, `assertEquals(555L, chapters.getById("ch2")?.discoveredAt)`) a před `db.close()` přidat:

```kotlin
        // MIGRATION_34_35: verifiedPageCount/isFallbackSource/fallbackChapterId pridane na
        // chapter (verifiedPageCount/fallbackChapterId nullable, isFallbackSource NOT NULL
        // default 0 pro existujici radky), musi byt citelne/zapisovatelne a prezit round-trip.
        val ch2Before = chapters.getById("ch2")!!
        assertEquals(null, ch2Before.verifiedPageCount)
        assertEquals(false, ch2Before.isFallbackSource)
        assertEquals(null, ch2Before.fallbackChapterId)
        chapters.setVerifiedPageCount("ch2", count = 5, isFallback = false, fallbackChapterId = "ch1")
        val ch2After = chapters.getById("ch2")!!
        assertEquals(5, ch2After.verifiedPageCount)
        assertEquals(false, ch2After.isFallbackSource)
        assertEquals("ch1", ch2After.fallbackChapterId)
```

- [ ] **Step 9: Ověřit, že test migrace selže bez migrace (RED)**

Nejdřív dočasně zakomentovat řádek `AppDatabase.MIGRATION_34_35,` přidaný v kroku 8.1, spustit:
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.data.db.AppDatabaseMigrationTest" 2>&1
echo EXIT_CODE=$?
```
Expected: selhání s `IllegalStateException` (Room identity hash mismatch - DB neobsahuje sloupce, co entita očekává). `EXIT_CODE` nesmí být `0`. Pak řádek zase odkomentovat.

- [ ] **Step 10: Ověřit, že test migrace prochází (GREEN)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.data.db.AppDatabaseMigrationTest" 2>&1
echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`.

- [ ] **Step 11: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/data/db/entity/ChapterEntity.kt \
        app/src/main/kotlin/com/haise/jiyu/data/db/ChapterDao.kt \
        app/src/main/kotlin/com/haise/jiyu/data/db/AppDatabase.kt \
        app/src/main/kotlin/com/haise/jiyu/di/AppModule.kt \
        app/src/test/kotlin/com/haise/jiyu/data/db/ChapterDaoTest.kt \
        app/src/test/kotlin/com/haise/jiyu/data/db/AppDatabaseMigrationTest.kt
git commit -m "feat: pridat DB pole pro overeny pocet stranek kapitoly (fallback neuplne kapitoly)"
```

---

## Task 2: Čisté funkce - detekce a výběr lepší alternativy

**Files:**
- Create: `app/src/test/kotlin/com/haise/jiyu/ui/resolver/SourceResolverFallbackTest.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/resolver/SourceResolverViewModel.kt`

**Interfaces:**
- Consumes: nic z Task 1 (čisté funkce nezávisí na DB).
- Produces: `internal fun isSuspiciouslyShort(pageCount: Int): Boolean`, `internal fun <T> pickBetterAlternative(originalPageCount: Int, alternatives: List<Pair<T, Int>>): T?`. Tyhle funkce používá Task 3.

- [ ] **Step 1: Napsat padající testy**

Vytvořit `app/src/test/kotlin/com/haise/jiyu/ui/resolver/SourceResolverFallbackTest.kt`:

```kotlin
package com.haise.jiyu.ui.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverFallbackTest {

    @Test
    fun `page count below the floor is suspiciously short`() {
        assertTrue(isSuspiciouslyShort(5))
    }

    @Test
    fun `page count exactly at the floor is not suspiciously short`() {
        assertFalse(isSuspiciouslyShort(6))
    }

    @Test
    fun `page count above the floor is not suspiciously short`() {
        assertFalse(isSuspiciouslyShort(13))
    }

    @Test
    fun `zero pages is suspiciously short`() {
        assertTrue(isSuspiciouslyShort(0))
    }

    @Test
    fun `no alternatives means no better candidate`() {
        assertNull(pickBetterAlternative(originalPageCount = 5, alternatives = emptyList()))
    }

    @Test
    fun `alternative below the floor is rejected even if better than original`() {
        // Sam o sobe porad podezrele kratky - neni duvod si myslet, ze je "kompletni".
        assertNull(pickBetterAlternative(originalPageCount = 3, alternatives = listOf("alt" to 5)))
    }

    @Test
    fun `alternative above the floor but not better than original is rejected`() {
        assertNull(pickBetterAlternative(originalPageCount = 13, alternatives = listOf("alt" to 8)))
    }

    @Test
    fun `single alternative above the floor and better than original wins`() {
        assertEquals("alt", pickBetterAlternative(originalPageCount = 5, alternatives = listOf("alt" to 13)))
    }

    @Test
    fun `multiple alternatives above the floor - the one with the most pages wins`() {
        val result = pickBetterAlternative(
            originalPageCount = 5,
            alternatives = listOf("alt-a" to 8, "alt-b" to 19, "alt-c" to 11),
        )
        assertEquals("alt-b", result)
    }

    @Test
    fun `alternative with the same page count as original is rejected`() {
        assertNull(pickBetterAlternative(originalPageCount = 13, alternatives = listOf("alt" to 13)))
    }
}
```

- [ ] **Step 2: Ověřit, že testy nekompilují (RED)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.ui.resolver.SourceResolverFallbackTest" 2>&1
echo EXIT_CODE=$?
```
Expected: `Unresolved reference: isSuspiciouslyShort`, `Unresolved reference: pickBetterAlternative`. `EXIT_CODE` nesmí být `0`.

- [ ] **Step 3: Implementovat obě funkce**

V `app/src/main/kotlin/com/haise/jiyu/ui/resolver/SourceResolverViewModel.kt` přidat za `isCompleteEnoughForEarlyExit`/`EARLY_EXIT_COMPLETENESS_THRESHOLD` (za řádek 40, před `@HiltViewModel`):

```kotlin
private const val SUSPICIOUSLY_SHORT_PAGE_FLOOR = 6

/** Kapitola s min poctem stranek je podezrela z neuplnosti - viz nahlaseny bug (MangaK/The
 * Raider/kap.19: 5 stranek vs. 11-19 u sousednich). Zaporne/nulove hodnoty (getPageList
 * selhalo/prazdne) jsou taky podezrele. */
internal fun isSuspiciouslyShort(pageCount: Int): Boolean = pageCount < SUSPICIOUSLY_SHORT_PAGE_FLOOR

/** Ze seznamu uz OVERENYCH alternativ (kandidat, pocet stranek) vybere tu s nejvic strankami,
 * pokud prekonava jak puvodni pocet, tak minimalni prah - jinak null (puvodni kapitola je porad
 * nejlepsi dostupna moznost, i kdyz je kratka - napr. MangaK/kap.19, kde zadna alternativa
 * nemela vic). Pri shode poctu stranek zustava puvodni (>, ne >=). */
internal fun <T> pickBetterAlternative(originalPageCount: Int, alternatives: List<Pair<T, Int>>): T? =
    alternatives
        .filter { (_, count) -> count >= SUSPICIOUSLY_SHORT_PAGE_FLOOR && count > originalPageCount }
        .maxByOrNull { (_, count) -> count }
        ?.first
```

- [ ] **Step 4: Ověřit, že testy prochází (GREEN)**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.ui.resolver.SourceResolverFallbackTest" 2>&1
echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`, 9 testů zelených.

- [ ] **Step 5: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/test/kotlin/com/haise/jiyu/ui/resolver/SourceResolverFallbackTest.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/resolver/SourceResolverViewModel.kt
git commit -m "feat: cisté funkce pro detekci podezrele kratke kapitoly a vyber lepsi alternativy"
```

---

## Task 3: Zapojení fallbacku do `selectCandidate`

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/resolver/SourceResolverViewModel.kt`

**Interfaces:**
- Consumes: `ChapterDao.setVerifiedPageCount` (Task 1, přes nový `MangaRepository` wrapper), `ChapterEntity.verifiedPageCount`/`isFallbackSource`/`fallbackChapterId` (Task 1), `isSuspiciouslyShort`/`pickBetterAlternative` (Task 2).
- Produces: přepracovaná `SourceResolverViewModel.selectCandidate()` - chování navenek stejné (pořád nastaví `_openedChapterId`), jen může ukázat na jinou kapitolu, než byl původní `bestMatch`.

Tenhle task nemá vlastní nový automatizovaný test (viz spec, sekce "Testování" - orchestrace síť+DB dohromady není prakticky ověřitelná bez reálného rozbitého zdroje) - ověřuje se tím, že existující `SourceResolverEarlyExitTest` a nový `SourceResolverFallbackTest` (Task 2) dál prochází a projekt se zkompiluje.

- [ ] **Step 1: Přidat wrapper metodu do `MangaRepository`**

V `app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt` přidat za `markDownloaded` (za řádek 411, před `updateReadProgress`):

```kotlin
    suspend fun setVerifiedPageCount(chapterEntityId: String, count: Int, isFallback: Boolean, fallbackChapterId: String? = null) =
        chapterDao.setVerifiedPageCount(chapterEntityId, count, isFallback, fallbackChapterId)
```

- [ ] **Step 2: Přidat import `ChapterEntity` do `SourceResolverViewModel.kt`**

Přidat k ostatním importům (za `import com.haise.jiyu.data.repository.MangaRepository`, řádek 8):

```kotlin
import com.haise.jiyu.data.db.entity.ChapterEntity
```

- [ ] **Step 3: Extrahovat sdílené řazení kandidátů do `rankedCandidates()`**

**Poznámka k číslům řádků v tomhle a dalším kroku:** Task 2 už do tohoto souboru přidal kód
(pure funkce) NAD místem, které se mění teď - proto se od téhle chvíle v plánu identifikují
místa k úpravě podle PŘESNÉHO OBSAHU KÓDU (unikátní úryvek), ne podle čísla řádku. Hledej v
souboru doslovně uvedené úryvky.

V `app/src/main/kotlin/com/haise/jiyu/ui/resolver/SourceResolverViewModel.kt` uvnitř `onCompletion { ... }` bloku najít a nahradit tenhle přesný úryvek:

```kotlin
                        val sorted = _candidates.value.sortedWith(
                            compareByDescending<ResolvedCandidate> { it.isFavorite }
                                .thenByDescending { matchesPreferredGroup(it) }
                                .thenByDescending { it.hasRequestedChapter }
                                .thenByDescending { it.matchedChapterCount }
                                .thenBy { it.nearestChapterDistance ?: Float.MAX_VALUE }
                        )
                        _candidates.value = sorted
```

za:

```kotlin
                        val sorted = rankedCandidates()
                        _candidates.value = sorted
```

A přidat novou privátní metodu `rankedCandidates()` hned za funkci `matchesPreferredGroup` a před `fun selectCandidate` - v souboru najít tenhle přesný úryvek (konec `matchesPreferredGroup` a začátek `selectCandidate`):

```kotlin
        return preferredGroupTokens.any { token -> sourceName.contains(token) || token.contains(sourceName) }
    }

    fun selectCandidate(candidate: ResolvedCandidate) {
```

a nahradit ho (vloží `rankedCandidates()` mezi obě funkce, `selectCandidate` beze změny na tomhle místě - přepracuje se až v kroku 4):

```kotlin
        return preferredGroupTokens.any { token -> sourceName.contains(token) || token.contains(sourceName) }
    }

    /**
     * Sdilene razeni kandidatu - stejna priorita jako drive primo v [onCompletion] (oblibeny >
     * shoda skupiny > ma pozadovanou kapitolu > nejuplnejsi pokryti > nejblizsi kapitola).
     * Pouziva se jak pro finalni serazeny seznam pro uzivatele, tak pro vyber alternativ v
     * [resolveCompleteChapter].
     */
    private fun rankedCandidates(): List<ResolvedCandidate> =
        _candidates.value.sortedWith(
            compareByDescending<ResolvedCandidate> { it.isFavorite }
                .thenByDescending { matchesPreferredGroup(it) }
                .thenByDescending { it.hasRequestedChapter }
                .thenByDescending { it.matchedChapterCount }
                .thenBy { it.nearestChapterDistance ?: Float.MAX_VALUE }
        )

    fun selectCandidate(candidate: ResolvedCandidate) {
```

(Následující krok 4 pak nahrazuje tělo `selectCandidate` samotné - proveď kroky 3 a 4 v tomhle pořadí, ne naopak, ať se `fun selectCandidate(candidate: ResolvedCandidate) {` v souboru nevyskytne dvakrát.)

- [ ] **Step 4: Přepracovat `selectCandidate()` a přidat `resolveCompleteChapter()`**

Najít v souboru funkci `selectCandidate`, kterou krok 3 právě přesunul za nově přidanou `rankedCandidates()` (začíná `fun selectCandidate(candidate: ResolvedCandidate) {` a končí uzavírací `}` funkce, beze změny obsahu oproti původnímu kódu), a nahradit ji celou (identifikuj podle obsahu, ne podle čísla řádku - viz poznámka v kroku 3):

```kotlin
    fun selectCandidate(candidate: ResolvedCandidate) {
        val target = requestedChapterNumber ?: return
        _resolving.value = true
        viewModelScope.launch {
            try {
                val mangaId = repository.openPreview(candidate.manga)
                val resolvedChapters = repository.getAllChapters(mangaId)
                val bestMatch = resolvedChapters.minByOrNull { abs(it.chapterNumber - target) }
                if (bestMatch == null) {
                    _error.value = appContext.getString(R.string.resolver_chapter_missing_after_select)
                } else {
                    val finalChapter = resolveCompleteChapter(candidate, bestMatch, target)
                    // Realny zdroj (ktery appka jen tise pouziva na pozadi) se do knihovny
                    // NEPRIDAVA (viz MangaRepository.openPreview) - proto se "precteno" musi
                    // rucne propsat zpet na SKUTECNY ComicK titul (ten uzivatel ma v knihovne),
                    // jinak by "Pokracovat ve cteni" i procenta na detailu titulu zustaly navzdy
                    // na 0 % i po precteni desitek kapitol - viz observeContinueReading (vyzaduje
                    // inLibrary = 1, ktere ComicK entita ma, ale resolvnuty realny zdroj nikdy).
                    val comicKId = comicKMangaId
                    if (comicKId != null) {
                        repository.updateReadProgress(chapterId, read = true, lastPageRead = 0, lastReadAt = System.currentTimeMillis())
                        repository.updateLastReadChapter(comicKId, chapterId)
                    }
                    _openedChapterId.value = finalChapter.id
                }
            } catch (e: Exception) {
                e.report("resolver:selectCandidate")
                _error.value = e.toFriendlyMessage()
            } finally {
                _resolving.value = false
            }
        }
    }

    /**
     * Zkontroluje, jestli ma [bestMatch] podezrele malo stranek (viz [isSuspiciouslyShort]), a
     * pokud ano, tise zkusi az 3 dalsi jiz nalezene kandidaty (viz [rankedCandidates]) - kdyz
     * nejaky ma vic stranek, appka na nej kapitolu presmeruje. Vysledek se trvale zapise (viz
     * ChapterEntity.verifiedPageCount/fallbackChapterId), takze se pri pristim otevreni teto
     * kapitoly cely tenhle proces preskoci (viz prvni vetev nize).
     */
    private suspend fun resolveCompleteChapter(candidate: ResolvedCandidate, bestMatch: ChapterEntity, target: Float): ChapterEntity {
        // Uz drive overeno - bud zustava, nebo presmerovat na drive nalezenou nahradu.
        if (bestMatch.verifiedPageCount != null) {
            val redirectId = bestMatch.fallbackChapterId
            if (redirectId != null) {
                repository.getChapter(redirectId)?.let { return it }
            }
            return bestMatch
        }

        // Skutecny pocet stranek puvodniho kandidata.
        val originalPages = try {
            repository.getChapterPages(bestMatch.sourceId, bestMatch.url, candidate.manga.url)
        } catch (e: Exception) {
            e.report("resolver:fallback:originalPages")
            return bestMatch // network selhal - kontrola se proste neprovede, dnesni chovani
        }

        // V poradku - zapamatovat a skoncit.
        if (!isSuspiciouslyShort(originalPages.size)) {
            repository.setVerifiedPageCount(bestMatch.id, originalPages.size, isFallback = false)
            return bestMatch
        }

        // Podezrele kratka - zkusit az 3 dalsi jiz nalezene kandidaty se stejnou kapitolou.
        val checked = mutableListOf<Pair<ChapterEntity, Int>>()
        val alternatives = rankedCandidates().filter { it.hasRequestedChapter && it.source.id != candidate.source.id }.take(3)
        for (alt in alternatives) {
            try {
                val altMangaId = repository.openPreview(alt.manga)
                val altChapters = repository.getAllChapters(altMangaId)
                val altChapter = altChapters.firstOrNull { abs(it.chapterNumber - target) < 0.01f } ?: continue
                val altPages = repository.getChapterPages(altChapter.sourceId, altChapter.url, alt.manga.url)
                checked.add(altChapter to altPages.size)
            } catch (e: Exception) {
                e.report("resolver:fallback:altPages:${alt.source.id}")
            }
        }

        // Vybrat nejlepsi (nebo zustat u puvodni).
        val better = pickBetterAlternative(originalPages.size, checked)
        return if (better == null) {
            repository.setVerifiedPageCount(bestMatch.id, originalPages.size, isFallback = false)
            bestMatch
        } else {
            repository.setVerifiedPageCount(bestMatch.id, originalPages.size, isFallback = false, fallbackChapterId = better.id)
            val betterPageCount = checked.first { it.first.id == better.id }.second
            repository.setVerifiedPageCount(better.id, betterPageCount, isFallback = true)
            better
        }
    }
```

- [ ] **Step 5: Ověřit, že projekt kompiluje a existující testy dál prochází**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.ui.resolver.*" 2>&1
echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0` - `SourceResolverEarlyExitTest` (5 testů) i `SourceResolverFallbackTest` (9 testů) zelené, žádná chyba kompilace v `SourceResolverViewModel.kt`.

- [ ] **Step 6: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/data/repository/MangaRepository.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/resolver/SourceResolverViewModel.kt
git commit -m "feat: pri vyberu zdroje zkusit fallback na jiny kandidat, kdyz je kapitola podezrele kratka"
```

---

## Task 4: Hláška v čtečce

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: `ChapterEntity.isFallbackSource` (Task 1).
- Produces: `ReaderViewModel.fallbackNotice: StateFlow<String?>`, `ReaderViewModel.clearFallbackNotice(): Unit`.

Tenhle task nemá vlastní automatizovaný test (čistě UI-level notifikace, žádná nová testovatelná logika) - ověřuje se kompilací a finálním regresním testem v Task 5.

- [ ] **Step 1: Přidat nové stringy do všech 4 lokalizačních souborů**

`app/src/main/res/values/strings.xml` - přidat za `reader_error_rate_limited` (řádek 755):
```xml
    <string name="reader_fallback_source_notice">Tato kapitola byla dotažena z jiného zdroje, protože původní verze byla neúplná.</string>
```

`app/src/main/res/values-en/strings.xml` - přidat za `reader_error_translation_failed` (řádek 700):
```xml
    <string name="reader_fallback_source_notice">This chapter was fetched from a different source because the original version was incomplete.</string>
```

`app/src/main/res/values-es/strings.xml` - najít `reader_error_translation_failed` a přidat hned za ni:
```xml
    <string name="reader_fallback_source_notice">Este capítulo se obtuvo de otra fuente porque la versión original estaba incompleta.</string>
```

`app/src/main/res/values-fr/strings.xml` - najít `reader_error_translation_failed` a přidat hned za ni:
```xml
    <string name="reader_fallback_source_notice">Ce chapitre a été récupéré depuis une autre source car la version originale était incomplète.</string>
```

- [ ] **Step 2: Přidat `_fallbackNotice` StateFlow a nastavení v `loadChapter`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt` přidat novou StateFlow deklaraci za `_comickUnavailable`/`comickUnavailable` (za řádek 105):

```kotlin
    // Jednorazova hlaska "tahle kapitola byla dotazena z jineho zdroje" - viz
    // SourceResolverViewModel.resolveCompleteChapter a ChapterEntity.isFallbackSource.
    private val _fallbackNotice = MutableStateFlow<String?>(null)
    val fallbackNotice: StateFlow<String?> = _fallbackNotice.asStateFlow()
    fun clearFallbackNotice() { _fallbackNotice.value = null }
```

V `loadChapter(id: String)` přidat za `currentChapter = chapter` (za řádek 632, před `_chapterTitle.value = chapter.name`):

```kotlin
        if (chapter.isFallbackSource) {
            _fallbackNotice.value = context.getString(R.string.reader_fallback_source_notice)
        }
```

- [ ] **Step 3: Zobrazit hlášku v `ReaderScreen`**

V `app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt`:

1. Přidat `collectAsState()` za `translationError` (za řádek 89):
```kotlin
    val fallbackNotice     by viewModel.fallbackNotice.collectAsState()
```

2. Přidat nový `LaunchedEffect` za existující `LaunchedEffect(translationError)` (za řádek 206):
```kotlin
    LaunchedEffect(fallbackNotice) {
        if (fallbackNotice != null) {
            delay(4_000L)
            viewModel.clearFallbackNotice()
        }
    }
```

3. Přidat nový banner za existující `AnimatedVisibility(visible = translationError != null, ...)` blok (za uzavírací `}` na řádku 414, před uzavírací `}` funkce na řádku 415-416):
```kotlin
        AnimatedVisibility(
            visible = fallbackNotice != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF37474F).copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(fallbackNotice.orEmpty(), color = Color.White, fontSize = 13.sp)
            }
        }
```

- [ ] **Step 4: Ověřit, že projekt kompiluje**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:compileDebugKotlin 2>&1
echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`.

- [ ] **Step 5: Commit**

```bash
cd "/c/Users/ilekr/Desktop/jiyu"
git add app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderViewModel.kt \
        app/src/main/kotlin/com/haise/jiyu/ui/reader/ReaderScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-fr/strings.xml
git commit -m "feat: zobrazit hlasku v ctecce, kdyz je kapitola dotazena z fallback zdroje"
```

---

## Task 5: Finální regresní test

**Files:** žádné (jen ověření).

- [ ] **Step 1: Spustit celou testovací sadu bez filtru**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd "/c/Users/ilekr/Desktop/jiyu"
./gradlew.bat :app:testDebugUnitTest 2>&1
echo EXIT_CODE=$?
```
Expected: `BUILD SUCCESSFUL`, `EXIT_CODE=0`, žádný regresní pád v žádném existujícím testu (`ChapterDaoTest`, `AppDatabaseMigrationTest`, `SourceResolverEarlyExitTest`, `SourceResolverFallbackTest`, ostatní existující testy).

- [ ] **Step 2: Pokud vše prochází, žádný další commit není potřeba (Task 1-4 už jsou commitnuté).**
