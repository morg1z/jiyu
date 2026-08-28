# Fallback pro neúplnou kapitolu (ComicK agregátor) — Design

## Kontext a problém

Navazuje na dnešní opravu early-exit resolveru (`SourceResolverViewModel.isCompleteEnoughForEarlyExit`,
commit `5afb85a`) a živé vyšetřování hlášeného bugu: uživatel četl "The Raider" přes ComicK
agregovaný režim, appka vybrala MangaK (37/37 kapitol - kompletní zdroj), ale kapitola 19 na
MangaK měla jen 5 obrázků oproti 11-19 u sousedních kapitol. Ověřeno živě v prohlížeči (síťové
požadavky i po scrollu na konec kapitoly ukázaly stále jen těch 5 obrázků) i komentáři přímo na
MangaK ("Feels like a bits missing" + potvrzující odpovědi) - jde o skutečnou mezeru v datech na
straně MangaK, ne o chybu parsování appkou.

Early-exit oprava řeší "appka vybrala CELKOVĚ neúplný zdroj". Neřeší tenhle případ: zdroj je
CELKOVĚ nejúplnější dostupná volba (37/37), ale JEDNA konkrétní kapitola je v něm rozbitá. Tenhle
spec přidává druhou, nezávislou vrstvu ochrany: kontrolu jednotlivé kapitoly při jejím otevření a
tichý pokus o jiný už známý zdroj, pokud vypadá kompletněji.

## Kde se to spustí

`SourceResolverViewModel.selectCandidate()` (`ui/resolver/SourceResolverViewModel.kt:233`) se
volá při KAŽDÉM otevření kapitoly ComicK titulu - ať přijde kandidát z early-exit, nebo z
kompletního hledání. V tu chvíli už má appka `bestMatch` (konkrétní řádek kapitoly u vybraného
zdroje) i `_candidates.value` (ostatní zdroje nalezené do tohoto okamžiku). Kontrola se zapojí
těsně před tím, než `selectCandidate` nastaví `_openedChapterId` (řádek 255) - žádné nové
"kdy se to spustí" místo není potřeba.

Nekontroluje se nic navíc při přidání mangy do knihovny ani na pozadí - jen při skutečném
otevření té konkrétní kapitoly čtenářem (uživatelské rozhodnutí, viz konverzace).

## Trvalé zapamatování: nová pole na `ChapterEntity`

`ChapterEntity.pageCount` dnes appka nastavuje JEN u stažených kapitol (`ChapterDao.markDownloaded`)
- přepoužít ho pro "ověřený online počet stránek" by kolidovalo s tímhle významem (např.
`DownloadManagerScreen` ho čte v kontextu "kolik stránek je stažených"). Místo toho dvě nová
pole:

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
 * sem ID kapitoly, na kterou se ma misto ni presmerovat - viz SourceResolverViewModel krok 1.
 * Bez tohohle by se PUVODNI (kratky) radek nikdy neoznacil jako "jiz overeno" a appka by
 * kontrolu opakovala pri kazdem otevireni znovu, protoze selectCandidate vzdy nejdriv najde
 * puvodniho "nejvhodnejsiho" kandidata, ne rovnou tu nahradni kapitolu. null = beze zmeny. */
val fallbackChapterId: String? = null,
```

Migrace `MIGRATION_34_35` (`AppDatabase.kt`, `version = 35`):
```kotlin
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapter ADD COLUMN verifiedPageCount INTEGER")
        db.execSQL("ALTER TABLE chapter ADD COLUMN isFallbackSource INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chapter ADD COLUMN fallbackChapterId TEXT")
    }
}
```
+ registrace v `AppModule.kt` vedle `MIGRATION_33_34`.

Nová DAO metoda (`ChapterDao.kt`, vedle `markDownloaded`):
```kotlin
@Query("""
    UPDATE chapter SET verifiedPageCount = :count, isFallbackSource = :isFallback,
           fallbackChapterId = :fallbackChapterId WHERE id = :id
""")
suspend fun setVerifiedPageCount(id: String, count: Int, isFallback: Boolean, fallbackChapterId: String? = null)
```

## Algoritmus (`SourceResolverViewModel.selectCandidate`)

Po nalezení `bestMatch` (existující kód, řádek 240), před nastavením `_openedChapterId`:

1. Pokud `bestMatch.verifiedPageCount != null` → už dřív ověřeno:
   - Pokud navíc `bestMatch.fallbackChapterId != null` → načíst
     `repository.getChapter(bestMatch.fallbackChapterId!!)`; když existuje, nahradit jím
     `bestMatch` (tahle náhradní kapitola má z minulého běhu už vlastní `isFallbackSource = true`,
     takže hláška v čtečce pojede správně i podruhé); když byla mezitím smazána (`null`), zůstat
     u původního `bestMatch`.
   - V obou případech přeskočit rovnou na krok 6 (žádný network navíc).
2. `val pages = repository.getChapterPages(bestMatch.sourceId, bestMatch.url, candidate.manga.url)`
   (existující metoda, stejná jako v `ReaderViewModel.loadChapter` - žádná nová mapovací funkce
   ChapterEntity → SChapter není potřeba).
3. Pokud `!isSuspiciouslyShort(pages.size)` (viz čistá funkce níže) → zapsat
   `chapterDao.setVerifiedPageCount(bestMatch.id, pages.size, isFallback = false)`, pokračovat
   na krok 6 s původním `bestMatch`.
4. Jinak (podezřele krátká): vzít až 3 další kandidáty z `_candidates.value`, kteří
   `hasRequestedChapter == true` a `source.id != candidate.source.id`, seřazené stejným
   komparátorem jako `onCompletion` (extrahovat do sdílené `private fun rankedCandidates()`,
   aby se logika neduplikovala). Pro KAŽDÉHO z těch až 3 (žádné předčasné zastavení - počet je
   už tak nízký, že "zkusit všechny a vybrat nejlepší" je jednodušší a deterministické):
   - `val altMangaId = repository.openPreview(alt.manga)`
   - `val altChapters = repository.getAllChapters(altMangaId)`
   - `val altChapter = altChapters.firstOrNull { abs(it.chapterNumber - target) < 0.01f } ?: continue`
   - `val altPages = repository.getChapterPages(altChapter.sourceId, altChapter.url, alt.manga.url)`
   - zapamatovat si `(altChapter, altPages.size)` pro finální výběr
5. `pickBetterAlternative(pages.size, checked)` (čistá funkce níže) vrátí buď lepšího kandidáta,
   nebo `null`:
   - `null` → zůstává `bestMatch`, zapsat `setVerifiedPageCount(bestMatch.id, pages.size, false)`
     (i když je krátká - appka udělala, co šlo, nebude to zkoušet znovu při každém otevření;
     `fallbackChapterId` zůstává `null`).
   - jinak → zapsat na PŮVODNÍ (krátký) řádek
     `setVerifiedPageCount(bestMatch.id, pages.size, isFallback = false, fallbackChapterId = altChapter.id)`
     (označuje "tenhle je ověřený a krátký, přesměruj na altChapter.id"), zapsat na NOVÝ řádek
     `setVerifiedPageCount(altChapter.id, altPages.size, isFallback = true)`, a nahradit
     `bestMatch` nalezeným `altChapter` pro krok 6.
6. `_openedChapterId.value = bestMatch.id` (stejné jako dnes, jen případně s novým `bestMatch`).

Zbytek `selectCandidate` (propsání "přečteno" zpět na ComicK entitu) beze změny.

## Pomocné čisté funkce (JVM testovatelné, `SourceResolverViewModel.kt`, top-level)

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

`pickBetterAlternative` je generická (`<T>`), aby test nemusel stavět skutečné `ChapterEntity`/
`ResolvedCandidate` instance - stačí libovolný placeholder typ jako identifikátor.

## Notifikace v čtečce (ne v resolver obrazovce)

`SourceResolverScreen` se po výběru kandidáta typicky OKAMŽITĚ zavře (navigace na čtečku, viz
`LaunchedEffect(openedChapterId)`) - snackbar zobrazený tam by uživatel prakticky nikdy neviděl.
Místo toho:

- `ReaderViewModel.loadChapter()` (řádek 631, hned po `repository.getChapter(id)`): pokud
  `chapter.isFallbackSource`, nastaví novou `_fallbackNotice: MutableStateFlow<String?>` na
  lokalizovaný text (`R.string.reader_fallback_source_notice`, např. "Tato kapitola byla dotažena
  z jiného zdroje, protože původní verze byla neúplná.").
- `ReaderScreen` zobrazí přes jednoduchý `Toast.makeText(context, text, Toast.LENGTH_LONG).show()`
  v `LaunchedEffect(fallbackNotice)` (čtečka dnes nemá vlastní `SnackbarHost` - Toast je pro
  jednorázovou nenápadnou hlášku dostatečný a nevyžaduje novou UI komponentu) + zavolá
  `viewModel.clearFallbackNotice()`, aby se stejná hláška neopakovala při rekompozici.
- Hláška se zobrazí PŘI KAŽDÉM otevření té kapitoly (flag `isFallbackSource` je trvalý), ne jen
  poprvé - vědomé zjednodušení, žádné další "už bylo zobrazeno" persistence navíc. Je to fakt o
  kapitole ("tahle je z jiného zdroje"), ne jednorázová událost, takže opakování není zavádějící.

Nové stringy (4 soubory - `values`/`values-en`/`values-es`/`values-fr`, vedle `resolver_*`):
`reader_fallback_source_notice`.

## Chybové stavy

- `repository.getChapterPages(...)` pro `bestMatch` selže (výjimka/timeout) → chyceno stejným vnějším
  `try/catch` jako dnes celé `selectCandidate` (řádek 257) → `_error.value` jako dnes, kontrola
  fallbacku se prostě neprovede, appka otevře `bestMatch` jako dřív (bezpečný pád zpět na dnešní
  chování).
- `openPreview`/`getPageList` na alternativním kandidátovi selže → `continue` na dalšího
  kandidáta (obalené vlastním `try/catch` per-kandidát, aby jeden nedostupný alternativní zdroj
  nezablokoval kontrolu zbylých ani nezpůsobil pád celého `selectCandidate`).
- Žádný alternativní kandidát nemá `hasRequestedChapter == true` → `pickBetterAlternative`
  dostane prázdný seznam → `null` → zůstává původní `bestMatch`, zapíše se `verifiedPageCount`
  (nebude se to zkoušet znovu).

## Testování

- **Nový test `SourceResolverFallbackTest`** (JVM, vedle existujícího
  `SourceResolverEarlyExitTest`): `isSuspiciouslyShort` - pod prahem/přesně na prahu/nad prahem/
  nula. `pickBetterAlternative` - žádná alternativa (prázdný seznam) → null; jediná alternativa
  pod prahem → null; jediná alternativa nad prahem ale míň stránek než originál → null; jediná
  alternativa nad prahem a víc stránek → vybere ji; víc alternativ nad prahem → vybere tu s
  nejvíc stránkami; alternativa se stejným počtem stránek jako originál → null (nepřepínat bez
  jasného zlepšení).
- **`ChapterDaoTest`** (Robolectric, existující soubor) - nový test pro `setVerifiedPageCount`:
  vloží kapitolu, zavolá s `fallbackChapterId = null`, ověří že `getById` vrátí zapsané
  `verifiedPageCount`/`isFallbackSource`/`fallbackChapterId` a že ostatní pole
  (read/downloadStatus/...) zůstala beze změny; druhý test se skutečnou hodnotou
  `fallbackChapterId` (redirect scénář).
- **`AppDatabaseMigrationTest`** (existující soubor) - nový test pro `MIGRATION_34_35`: DB verze
  34 s řádkem v `chapter`, migrace na 35, ověřit že nové sloupce existují se správným výchozím
  stavem (`verifiedPageCount` NULL, `isFallbackSource` 0, `fallbackChapterId` NULL).
- **Orchestrace v `selectCandidate`** (síť + DB dohromady) - stejně jako u dřívějšího
  `recoverMangaLink` specu není ruční/automatizované ověření prakticky proveditelné (potřebovalo
  by reálný zdroj se skutečně rozbitou kapitolou) - spoléhá se na pokrytí čistých funkcí + DAO
  testu; orchestrace je "poskládej existující kusy (openPreview/getAllChapters/getPageList)
  dohromady", žádná vlastní netriviální logika navíc.

## Rozsah / co NENÍ součástí

- Žádná proaktivní kontrola při přidání mangy do knihovny (viz uživatelské rozhodnutí) - jen při
  otevření konkrétní kapitoly.
- Žádná re-kontrola už jednou ověřené kapitoly (`verifiedPageCount != null` = navždy hotovo,
  dokud uživatel mangu neodebere a znovu nepřidá - stejné chování jako `discoveredAt`/ostatní
  "jen při prvním vložení" pole).
- Neřeší obecně "táhni komentáře nebo libovolná jiná metadata odjinud" - to je samostatný,
  zatím neprobraný nápad (komentáře v čtečce).
- Nekontroluje se nic u zdrojů přidaných mimo ComicK agregovaný režim (appka mimo něj nemá
  žádné "alternativní kandidáty" k dispozici - `_candidates` existuje jen v tomhle flow).
- Práh 6 stránek je natvrdo v kódu (konstanta), ne uživatelské nastavení - YAGNI, lze změnit
  jedním číslem, pokud se v praxi ukáže špatně kalibrovaný.
