# Oprava odkazu v rámci stejného zdroje — Design

## Kontext a problém

Inspirováno Kotatsu (`explore/domain/RecoverMangaUseCase.kt`, viz `project_jiyu_kotatsu_gap_analysis`
paměť) — když zdroj přestaví svůj web (změní URL schéma), ale konkrétní titul na něm pořád
existuje, appka o tom neví a titul v knihovně se přestane aktualizovat. `ChapterUpdateWorker.kt`
(`work:chapterUpdate:manga`, řádky 66-68) při selhání `repository.refreshChapters(...)` jen
tiše zaloguje chybu a jede dál — titul se sám nikdy neopraví, dokud ho uživatel ručně neodebere
a nepřidá znovu (a tím ztratí historii čtení).

Menší, jednodušší varianta dřív diskutované (a nedokončené) funkce "AutoFix napříč zdroji" —
tahle hledá **jen na tom samém zdroji**, kde titul původně byl.

## Kde se to spustí

`MangaDetailViewModel.refreshChapters()` (pull-to-refresh na detailu titulu, `MangaDetailViewModel.kt:647`)
je JEDINÉ místo v appce, kde se dnes explicitně (na žádost uživatele) zkouší znovu natáhnout
seznam kapitol - appka při běžném otevření detailu žádný nový síťový požadavek nedělá, jen
zobrazí, co už má v Room DB. Recovery se proto napojí do `catch` bloku týhle funkce jako
fallback POTÉ, co běžné `repository.refreshChapters(...)` selže - ne jako nové "vždy při otevření
detailu kontroluj síť" chování (to appka dnes nedělá vůbec a bylo by to větší, samostatná změna).

ComicK je vyloučen (`existing.sourceId == "comick"`) — je to metadatový katalog agregující VÍC
zdrojů, ne web s vlastní URL strukturou k opravě.

## Klíčové architektonické rozhodnutí: NIKDY neměnit `MangaEntity.id`

`MangaEntity.id` i `ChapterEntity.id` jsou dnes odvozené z `"{sourceId}::{url}"`
(`MangaRepository.kt:409` pro kapitoly, komentář u `MangaEntity.id` pro mangu) - ale **jen
při prvním vložení**. Nikde v appce se `id` znovu NEpřepočítává z aktuální `url`, aby se
"ověřila" identita - `id` je stabilní primární klíč, na který se odkazují kategorie
(`MangaCategoryEntity` má skutečný SQL `ForeignKey` na `manga.id`), historie čtení, poznámky,
tagy, glosář, překladová cache i stahování.

**`MangaCategoryEntity`'s `ForeignKey` na `manga.id` NEMÁ `onUpdateAction = CASCADE`** - raw SQL
`UPDATE manga SET id = ...` by proto u titulu v jakékoli kategorii spadl na constraint violation.
Řešení: recovery mění **jen `MangaEntity.url` a metadata** (title/coverUrl/description/...),
`id` zůstává navždy takový, jaký byl při přidání do knihovny - to je ostatně přesně to, co
uživatel od "stabilní identity v appce" očekává.

`ChapterEntity.id` naproti tomu **žádný SQL `ForeignKey` na sebe nemá** (ověřeno grepem přes
`data/db/` - jediné dva `ForeignKey` v projektu jsou u `MangaCategoryEntity`, jeden na mangu,
jeden na kategorii). Měnit `chapter.id` je proto bezpečné - jediný "vedlejší efekt" je, že
překladová cache (`TranslatedPageDao`/`GlossaryDao`) navázaná na STARÉ `chapterId` osiří
(neškodné, jen se znovu nenajde v cache při příštím čtení téhle kapitoly - přijatelné, řeší se
to samo přeložením znovu).

## Algoritmus

Nová funkce `MangaRepository.recoverMangaLink(mangaId: String): Boolean`:

1. Načíst `existing = mangaDao.getById(mangaId)` - `null`/`sourceId == "comick"` → `false`, nic se neděje.
2. `source.search(existing.title)` → kandidáti na STEJNÉM zdroji (`existing.sourceId`).
3. `findBestTitleMatch(candidates, existing.title)` - viz níže. `null` → `false`.
4. Pokud `match.url == existing.url` → `false` (nic se nezlepšilo, URL je stejná jako předtím -
   běžná chyba, ne rozbitý odkaz).
5. `source.getChapterList(match)` → pokud selže nebo je prázdný → `false` (kandidát je stejně
   "rozbitý", relink by nic nevyřešil).
6. `planChapterMigration(oldChapters, newChapters)` - viz níže - napáruje staré/nové kapitoly
   podle čísla.
7. Pro každý napárovaný pár zavolat `chapterDao.relink(...)` (nová DAO metoda - viz níže) -
   přepíše `id`/`url`/`name`/`dateUpload`/`scanlationGroup`/`volume`/`groupsJson` na nové
   hodnoty, **`read`/`lastPageRead`/`lastReadAt`/`lastScrollOffset`/`downloadStatus`/`localPath`/
   `pageCount`/`discoveredAt` zůstávají beze změny** (nejsou v `SET` klauzuli).
8. Nespárované nové kapitoly (`plan.newOnly`) → normální `chapterDao.insertNewOnly(...)` (běžná
   cesta jako `refreshChapters` - tohle je legitimně "nová kapitola" pro notifikace, protože
   vůbec neexistovala ve starém seznamu).
9. Nespárované staré kapitoly (nejsou v `plan`, nikam se nezapisují) zůstávají v DB beze změny -
   osiřelé, ale neškodné (appka je pořád ukáže v seznamu kapitol titulu, jen jejich `url` už
   nikdy nepůjde znovu načíst - v praxi vzácné, řešitelné jen ručním "odebrat kapitolu", což
   appka dnes ani nenabízí, takže se tím tenhle spec nezabývá).
10. `mangaDao.upsert(existing.copy(url = match.url, title = match.title, coverUrl = match.coverUrl ?: existing.coverUrl))`.
11. `true`.

**Volající** (`MangaDetailViewModel.refreshChapters()`):
```kotlin
} catch (e: Exception) {
    val recovered = try { repository.recoverMangaLink(mangaId) } catch (_: Exception) { false }
    if (recovered) {
        _errorMessage.value = null
        val fresh = repository.getManga(mangaId)
        if (fresh != null) {
            val sManga = SManga(fresh.sourceId, fresh.url, fresh.title, fresh.coverUrl, fresh.description, fresh.status, contentType = fresh.contentType)
            try { repository.refreshMangaDetails(mangaId, sManga) } catch (_: Exception) { /* kosmeticke detaily, neni kriticke */ }
        }
    } else {
        _errorMessage.value = appContext.getString(R.string.detail_error_refresh_failed, e.toFriendlyMessage())
    }
}
```
Cokoliv v `recoverMangaLink` selže → appka tiše spadne zpátky na **původní** chybovou hlášku,
přesně jak funguje dnes. Recovery je čistě bonus pokus navíc, nikdy nezhorší current chování.
Žádná nová UI hláška "opraveno" - obnovený seznam kapitol/název/cover, co se prostě zobrazí, je
dostatečný signál, že se něco stalo (konzistentní s tím, že appka dnes taky nehlásí explicitně
"kapitoly obnoveny" po běžném pull-to-refreshi).

## Pomocné čisté funkce (JVM testovatelné)

Nový soubor `app/src/main/kotlin/com/haise/jiyu/data/repository/MangaLinkRecovery.kt`:

```kotlin
package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga

/**
 * Vybere nejjistějšího kandidáta pro "tohle je stejný titul, jen na nové URL". Radši žádnou
 * shodu než špatnou: pokud je přesných shod (case-insensitive) víc, nebo není žádná přesná a
 * kandidátů je víc než 1, appka se vzdá (vrátí null).
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
 * Napáruje STARÉ (uložené) a NOVÉ (čerstvě stažené ze zdroje) kapitoly podle čísla kapitoly.
 * Předpokládá, že [newChapters] nemá duplicitní čísla (agregované zdroje typu ComicK, kde by to
 * neplatilo, [MangaRepository.recoverMangaLink] vůbec nevolá - viz vyloučení ComicK výše).
 * Duplicitní číslo v [newChapters] se přesto ošetří použitím jen PRVNÍHO výskytu, aby se žádný
 * starý řádek nepřemapoval dvakrát.
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

## Nová DAO metoda

`ChapterDao.kt`, nová query vedle `updateProgress`/`updateScrollOffset`:

```kotlin
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

## Chybové stavy

- `source.search(...)` selže (síť/parsing) → chyceno v `recoverMangaLink`, `false`.
- `source.getChapterList(match)` selže/prázdné → `false` (kandidát je stejně nepoužitelný).
- Cokoliv jiné neočekávané → `recoverMangaLink` volající obalí vlastním `try/catch` (viz
  volající kód výše) - žádná výjimka z recovery nikdy neprobublá k uživateli jako NOVÁ chyba,
  max. se ukáže ta PŮVODNÍ (z běžného refreshChapters).

## Testování

- **`MangaLinkRecoveryTest`** (JVM, `app/src/test/kotlin/com/haise/jiyu/data/repository/`):
  `findBestTitleMatch` - přesná shoda mezi víc kandidáty, žádná přesná shoda + 1 kandidát,
  žádná přesná shoda + víc kandidátů (→ null), přesně 1 přesná shoda, 2 přesné shody (→ null,
  nejednoznačné), prázdný seznam kandidátů. `planChapterMigration` - běžné napárování, nová
  kapitola bez odpovídajícího starého čísla, staré číslo bez odpovídající nové kapitoly
  (zůstane mimo plán), duplicitní číslo v `newChapters` (jen první se použije).
- **`ChapterDaoTest`** (Robolectric, existující soubor) - nový test pro `relink()`: vloží
  kapitolu s `read=true`/`downloadStatus=DOWNLOADED`, zavolá `relink` na nové `id`/`url`, ověří
  že `getById(newId)` vrátí řádek se ZACHOVANÝM `read`/`downloadStatus`, a že `getById(oldId)`
  už nic nevrátí (starý řádek přestal existovat pod starým id).
- **`recoverMangaLink` samotné** (síť + DB dohromady) - ruční ověření není prakticky proveditelné
  (potřebovalo by reálný zdroj se změněnou URL) - spoléhá se na pokrytí obou čistých funkcí +
  DAO testu; orchestrace v `MangaRepository` je jen "poskládej tyhle tři kusy dohromady", žádná
  vlastní netriviální logika navíc.

## Rozsah / co NENÍ součástí

- Cross-source AutoFix (hledání na JINÝCH zdrojích) - to je jiná, dřív diskutovaná a
  nedokončená funkce, tenhle spec ji neřeší.
- Automatické spuštění na pozadí (`ChapterUpdateWorker`) - jen manuální pull-to-refresh na
  detailu, viz "Kde se to spustí" výše.
- Žádná nová UI hláška/potvrzení "odkaz opraven" - obnovená data jsou dostatečný signál.
- Mazání osiřelých starých kapitol (nespárovaných) - zůstávají v DB beze změny.
- ComicK zdroj (vyloučen explicitně, viz výše).
