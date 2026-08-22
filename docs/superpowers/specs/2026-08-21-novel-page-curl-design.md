# 3D efekt otáčení stránek (page curl) — novel i manga/manhwa čtečka — Design

**Datum:** 2026-08-21 (rozšířeno 2026-08-22)
**Stav:** Návrh schválen uživatelem v chatu (brainstorming), čeká na zápis rozšířeného
implementačního plánu.

## Cíl

Přidat appce Jiyu volitelný 3D efekt otáčení stránek (page curl) na úrovni Google Play
Books (skutečné prohnutí papíru, ne plochá karta/fade přechod), ovládaný JEDNÍM togglem
v Nastavení čtečky: **"Použití 3D efektu při otáčení stránek"**. Efekt se týká DVOU
čteček:

1. **Novel reader** (`NovelContent.kt`) — dnes nekonečný scroll bez konceptu stránky.
   Zapnutí togglu přepne na stránkovaný režim s curl přechodem mezi stránkami.
2. **Manga/manhwa reader v režimu `ReadingMode.MANGA`** (`MangaReader` v
   `ReaderPager.kt`) — dnes už MÁ diskrétní stránky (`HorizontalPager`), jen přechod mezi
   nimi je plynulý swipe. Zapnutí togglu nahradí tenhle swipe curl přechodem.

**`ReadingMode.WEBTOON`** (vertikální nekonečný scroll u manhwy/webtoonu,
`WebtoonReader.kt`) je z efektu VĚDOMĚ VYNECHÁN — vertikální scroll nemá diskrétní
"stránky" k otáčení, uživatel o to nežádal (viz zadání: "kdyz bude mit nekdo cteni ne
webtoon ale to druhé").

## Referenční materiál

Uživatel poslal 6 screenshotů z Google Play Books (česká lokalizace): nastavení čtečky s
togglem "Použití 3D efektu při otáčení stránek" a sekvenci snímků čtení ukazující
postupné otáčení stránky — viditelné prohnutí, průsvit textu ze zadní strany, stín podél
linie ohybu, spodní lišta s "V kapitole zbývá N stránek" a "%".

## Zjištění z prozkoumání kódu

### Novel reader (beze změny oproti původnímu zkoumání)

`NovelContent.kt` vykresluje CELOU kapitolu jako jeden `LazyColumn` — appka nemá koncept
stránky uvnitř kapitoly vůbec. `hasPrev`/`hasNext`/`onPrev`/`onNext` přepínají mezi
celými KAPITOLAMI. Font size/řádkování/téma jsou lokální `remember` state, nepersistují
se — mezera nezávislá na téhle práci, mimo rozsah.

### Manga reader (`MangaReader` v `ReaderPager.kt`) — nově prozkoumáno

Na rozdíl od novel readeru manga reader **už dnes stránkuje diskrétně**:

- `HorizontalPager` nad `groups: List<List<Int>>` — každá skupina je buď 1 stránka,
  nebo (při `doublePageSpread && isLandscape`) pár 2 stránek vedle sebe (`spreadPageIndices`
  vylučuje stránky, co se do páru nemají slučovat — širší-než-vyšší obrázky, #29 fix).
- Každá skupina se vykresluje jako `Box`/`Row` s `RetryableAsyncImage` (obrázek) +
  volitelně `BubbleOverlayLayer` (přeložené bubliny), obojí uvnitř společného
  `graphicsLayer(scaleX/scaleY/translationX/translationY)` pro pinch-to-zoom.
- Pinch-zoom stav (`scale`, `panOffset`) žije v `MangaReader`, `rememberSaveable`.
  `userScrollEnabled = scale <= 1f` — swipe mezi stránkami je vypnutý, dokud je uživatel
  zoomnutý (musí se nejdřív vrátit na 1×, jinak by zoom a swipe gesto kolidovaly).
- Tap zóny (3×3 grid → `TapZoneAction`), volume klávesy, dlouhý stisk → sdílení stránky,
  `jumpToPage`, `autoNextChapter` — všechno řízeno skrz `pagerState`/callbacky, ne
  nezávisle na Pageru.

**Klíčový architektonický rozdíl oproti novel readeru**: manga reader NEPOTŘEBUJE
paginátor (Task 2 z původního plánu) — stránky/skupiny už existují. Co ale chybí, je
"odkrytí zadní strany" při otáčení — `HorizontalPager` dělá plynulý translate, ne
prohnutí. Skutečný curl (s klipováním na plochou/ohýbanou část a zrcadlovou maticí)
nejde "nalepit" na `HorizontalPager` beze změny chování — vyžaduje vlastní
gesto+kreslení stejně jako u novel readeru (`HorizontalPager` sám měří a umisťuje
sousední stránky nezávisle, nedovolí jedné stránce vykreslit přes sebe prohnutou vrstvu
se zrcadlenou bitmapou druhé).

## Rozhodnutí z brainstormingu (potvrzeno uživatelem)

1. **Vizuální věrnost**: plný page-curl jako Google Books (ne zjednodušený plochý 3D
   flip) — pro OBĚ čtečky, stejná `PageCurlGeometry`/`PageCurlEffect` vrstva.
2. **Gesto**: tah prstem (roh stránky sleduje pozici prstu během tahu) I ťuknutí na
   okraj obrazovky (spustí stejnou animaci automaticky) — pro OBĚ čtečky.
3. **Ukazatel postupu**: ano — "Stránka X z Y" + % dole na obrazovce (novel reader má
   vlastní ukazatel; manga reader už dnes ukazuje stránku/počet v `ReaderTopBar`, curl
   variant tenhle existující ukazatel nemění).
4. **Izolace**: toggle VYPNUTÝ = beze změny (dnešní `LazyColumn` scroll / dnešní
   `HorizontalPager` swipe). Toggle ZAPNUTÝ = curl přechod v OBOU čtečkách současně
   (jeden toggle, ne dva) — mimo `ReadingMode.WEBTOON`, který togglem není ovlivněn.
5. **Parita s existujícím chováním mangy** (rozhodnuto 2026-08-22): curl v manga readeru
   MUSÍ zachovat všechno, co dnešní `MangaReader` umí zároveň — pinch-zoom,
   dvoustránkový spread, tap zóny, volume klávesy, sdílení stránky, `autoNextChapter`,
   bublinový překlad. Řešení viz sekce "Manga reader — reálné souběhy" níže; nejde o
   zjednodušenou variantu, kde by se curl při zoomu/spreadu vypínal.

## Architektura

Sdílené vrstvy (beze změny návrhu, jen se teď použijí ze DVOU míst):

### 1. `PageCurlState` (dřív navrženo jako `NovelPageCurlState` — přejmenováno, protože
   je to generická logika bez vztahu k textu)

Čistá, testovatelná immutable třída: `currentPageIndex`, `pageCount`, `dragProgress`.
Funkce `withDrag`/`onDragEnd`/`onEdgeTap`, `TurnDirection`, `PageTurnResult`
(`WithinChapter`/`ChapterBoundary`/`Cancelled`). Novel reader ji používá nad
"stránkami textu" (z paginátoru), manga reader nad "skupinami stránek" (`groups.size`).
Žádná změna chování je-li přejmenovaná — je to čistě kosmetická oprava názvu PŘED
implementací (nic z Tasku 3 ještě není napsáno v kódu).

### 2. `PageCurlGeometry` + `PageCurlEffect` (beze změny)

Geometrie ohybu (fold linie, polygon clip, zrcadlová matice) a Canvas vykreslení —
funguje nad libovolnou dvojicí `ImageBitmap` (aktuální + odkrývaná stránka), nezávisle
na tom, jestli bitmapa vznikla rasterizací textu nebo rasterizací obrázku+bublin.

**Oprava oproti původnímu návrhu**: původní `PageCurlNovelReader` (Task 6) volá
`drawPageCurl(..., revealedPageBitmap = null)` — VŽDY, i uprostřed tahu. To znamená, že
se pod ohýbanou částí nikdy nic nezobrazí (jen pozadí), což u referenčních screenshotů
(Google Books) evidentně NENÍ pravda — vidíme prosvítat/objevovat se OBSAH další
stránky. Tohle je nutné opravit pro OBĚ čtečky (viz upravený Task 6 a nový Task 8 v
plánu): rasterizovat i sousední (odkrývanou) stránku a předat ji jako
`revealedPageBitmap`, ne `null`. U mangy je to obzvlášť viditelné — prázdné pozadí
místo příští stránky by při čtení komiksu vypadalo jako vizuální chyba, ne jako efekt.

### 3. Novel: `NovelPaginator` + `PageCurlNovelReader` (beze změny)

Jak v původním návrhu — `NovelPaginator.kt` dělí text na stránky, `PageCurlNovelReader`
propojuje gesto/rasterizaci/vykreslení. Jediná změna: používá přejmenovaný `PageCurlState`
a nově rasterizuje i sousední stránku (viz oprava výše).

### 4. Manga: sdílená extrakce ze `MangaReader` + nový `MangaPageCurlReader`

Aby se logika skupin stránek (spread pairing) a vykreslení jedné skupiny
(obrázek+bubliny+zoom) nepsala dvakrát, extrahují se ze `ReaderPager.kt` dva kusy, které
používá jak dnešní `MangaReader` (beze změny chování), tak nový `MangaPageCurlReader`:

- **`computePageGroups(pageCount: Int, useSpread: Boolean, spreadPageIndices: Set<Int>): List<List<Int>>`**
  — čistá, testovatelná funkce extrahovaná z dnešního `remember(pages.size, useSpread,
  spreadPageIndices) { ... }` bloku (`ReaderPager.kt:127-144`). Beze změny logiky, jen
  přesunuto mimo `@Composable` aby šlo JVM-testovat.
- **`MangaGroupContent`** — `@Composable`, obsah jedné skupiny (1 nebo 2 stránky vedle
  sebe s `RetryableAsyncImage` + volitelný `BubbleOverlayLayer`), extrahovaný z
  `ReaderPager.kt:336-431` BEZ pinch-zoom `graphicsLayer` obálky (tu si přidává volající
  zvlášť — `MangaReader` kolem celé `HorizontalPager` stránky, `MangaPageCurlReader`
  kolem "klidové" (`dragProgress == 0f`) vrstvy, viz níže).

Nový soubor `MangaPageCurlReader.kt` — strukturálně analogický `PageCurlNovelReader.kt`:
- Stav: `PageCurlState(currentGroupIndex, groups.size)` místo stránek textu.
- Klidový stav (`dragProgress == 0f`): vykresluje aktuální skupinu ŽIVĚ přes
  `MangaGroupContent` (ne z bitmapy) — zachovává dnešní ostré/interaktivní pinch-zoom
  (`detectTransformGestures` + `graphicsLayer(scale, panOffset)`), přesně jako dnešní
  `MangaReader`. Bitmapa se renderuje na pozadí přes `rememberGraphicsLayer` pro
  následující použití, ale nekreslí se, dokud `dragProgress != 0f`.
- Aktivní tah (`dragProgress != 0f`): přepne na `Canvas`+`drawPageCurl` nad
  rasterizovanými bitmapami aktuální i odkrývané skupiny (druhá se rasterizuje
  lazily/`LaunchedEffect` při vstupu do sousedství, stejně jako aktuální).
- Gesta: `detectDragGestures`/`detectTapGestures` — STEJNÁ rozhodovací logika jako
  novel reader (`PageCurlState.withDrag`/`onDragEnd`/`onEdgeTap`), NE `HorizontalPager`.
  Volitelné okrajové ťuknutí respektuje `tapZoneGrid`/`tapZonesEnabled` stejně jako dnes
  (viz "Manga reader — reálné souběhy" níže za přesné řešení tap zón).
- Hranice kapitoly: `onChapterBoundary(TurnDirection)` → volá existující
  `onNavigatePrevChapter`/`onNavigateNextChapter` (stejné jako dnešní
  `PREV_CHAPTER`/`NEXT_CHAPTER` tap zóny).

## Manga reader — reálné souběhy (parita, rozhodnutí 2026-08-22)

Uživatel explicitně chce PLNOU paritu, ne zjednodušenou variantu s vypínáním curlu při
zoomu/spreadu. Řešení pro každý souběh:

**Pinch-zoom vs. curl-drag** — dnešní pravidlo `userScrollEnabled = scale <= 1f` (swipe
vypnutý, dokud je scale > 1) se PŘENÁŠÍ 1:1 na curl: dokud je `scale > 1f`, gesto
otáčení stránky (drag/tap) se vůbec nespustí — modifier řetězec pro
`detectDragGestures`/`detectTapGestures` (otáčení) se podmíní `if (scale <= 1f)` (stejná
sémantika jako `userScrollEnabled`, jen implementovaná ručně, protože tu není Pager,
který by to řešil za nás). Zoom samotný (`detectTransformGestures`) běží VŽDY, nezávisle
na curlu — to je nezměněné dnešní chování. Tohle NENÍ "curl se vypíná při zoomu" ve
smyslu zjednodušené varianty — je to STEJNÉ pravidlo, jaké platí dnes pro swipe, teď
jen platí i pro curl. Uživatel může zoomovat stránku v klidu stejně jako dnes; teprve
po návratu na 1× může otočit (tahem nebo curl-tapem).

**Dvoustránkový spread vs. curl** — spread pár (2 stránky vedle sebe na šířku) se
rasterizuje jako JEDNA bitmapa (celý `Row` s oběma obrázky+bublinami) a otáčí se jako
jedna curl jednotka — přesně jako dnes funguje jako jedna `HorizontalPager` stránka.
Žádná speciální "dvojitá" curl geometrie není potřeba — `computePageGroups`/`groups`
odevzdává tenhle pár jako jeden prvek seznamu, `PageCurlState`/`PageCurlGeometry` o
tom, že je to spread, vůbec neví (transparentní).

**Otočení obrazovky (spread on/off za běhu)** — dnešní `key(useSpread) { ... }` vzor
(znovuvytvoří `HorizontalPager` s novým `initialGroupIndex` při přepnutí orientace) se
přenáší i do `MangaPageCurlReader`: `key(useSpread) { ... currentGroupIndex = ... }`.

**Tap zóny** — okrajové ťuknutí (curl-tap na levý/pravý okraj) a `tapZoneGrid`
(3×3 akce včetně `SHOW_PANEL`/`PREV_CHAPTER`/`NEXT_CHAPTER`) koexistují: ťuknutí NEJDŘÍV
projde přes `tapZoneGrid[row, col]` stejně jako dnes; jen když akce vyjde jako
`PREV_PAGE`/`NEXT_PAGE`, spustí se curl (`onEdgeTap`) místo přímého `animateScrollToPage`
u dnešního Pageru. Ostatní akce (`SHOW_PANEL`, `PREV_CHAPTER`, `NEXT_CHAPTER`, `NONE`)
se chovají identicky beze změny.

**Volume klávesy** — `Key.VolumeDown`/`Key.VolumeUp` volají stejné
`onEdgeTap(TurnDirection...)` misto `animateScrollToPage`, se stejnou podmínkou
`volumeKeysNav`.

**Sdílení stránky (dlouhý stisk)** — `onLongPress` beze změny, sdílí URL aktuální
(klidové) stránky ze skupiny — funguje identicky, protože klidový stav pořád vykresluje
živě přes `MangaGroupContent`.

**`autoNextChapter`** — stejná `LaunchedEffect(currentSingleIndex/currentGroupIndex,
pages.size)` logika, jen `currentSingleIndex` nahrazeno odvozením z
`groups[currentGroupIndex]`.

**Bublinový překlad + flip/edit bubliny** — `MangaGroupContent` přebírá
`translateMode`/`translatedPages`/`flippedBubbles`/`onToggleBubbleFlip`/`onEditBubble`
beze změny, protože je to stejný `BubbleOverlayLayer` volaný se stejnými parametry.

## Nastavení (toggle) — sjednoceno pro obě čtečky

Jeden toggle, ne dva — uživatel: "chci aby ten curl efekt sel v nastavení zapnout a
vypnout a fungovalo to i u mangy a manhwy". Klíč/název se GENERALIZUJE (nic z toho ještě
není v kódu, takže žádné přejmenování za běhu):

- `SettingsKeys.PAGE_CURL_ENABLED = booleanPreferencesKey("page_curl_enabled")`
- `SettingsRepository.pageCurlEnabled: Flow<Boolean>` (default `false`) +
  `suspend fun setPageCurlEnabled(enabled: Boolean)`
- `SettingsViewModel.pageCurlEnabled: StateFlow<Boolean>` + setter — stejný passthrough
  vzor jako `preloadNextNovelChapter`
- `ReaderViewModel.pageCurlEnabled: StateFlow<Boolean>` — čte ho JAK `NovelContent`
  (přes `ReaderScreen.kt`), TAK `ReaderContent.kt` (nový parametr, viz níže)
- `ReaderSettingsScreen.kt`: sekce se PŘEJMENOVÁVÁ z "Light novel čtečka" na obecnou
  "Otáčení stránek" (`settings_reader_page_curl_section_title`), popisek togglu se
  upravuje aby zmínil obě čtečky: "Kapitola/stránka se otáčí ohybovou animací jako u
  knihy, místo plynulého scrollu/swipu. Platí pro light novel čtečku i pro manga/manhwa
  čtení po stránkách (ne pro webtoon plynulý scroll)."

`ReaderContent.kt` (`readingMode` větev, `ReaderContent.kt:178-204`) dostává nový
parametr `pageCurlEnabled: Boolean = false`; když je `true` A `readingMode !=
ReadingMode.WEBTOON`, volá se `MangaPageCurlReader` místo `MangaReader`. `NovelContent.kt`
používá stejný parametr (přejmenovaný z `novelPageCurl`) pro svou vlastní podmínku.

## Ukazatel postupu

Novel reader: "Stránka X z Y" + % (beze změny z původního návrhu). Manga reader: dnešní
`ReaderTopBar` už ukazuje `currentPage`/`pageCount` — curl variant tenhle mechanismus
nemění, jen `onPageChanged` volá se stejně jako dnes (z `PageTurnResult.WithinChapter`
místo z `pagerState.currentPage` snapshotu).

## Edge cases

Novel (beze změny): prázdný text → 1 stránka; extrémně velký font → 1 vynucená stránka;
vypnutí togglu uprostřed čtení → návrat na scroll.

Manga (nové):
- Kapitola s 1 stránkou (`groups.size == 1`) → `onEdgeTap`/dokončený drag vrátí rovnou
  `ChapterBoundary`, žádná zbytečná curl animace uvnitř (stejná logika jako
  `PageCurlState` už řeší pro novel).
- Zoom aktivní (`scale > 1f`) při pokusu o curl-drag → gesto se nespustí (viz sekce
  "reálné souběhy" výše), žádný crash/zaseknutý stav.
- Otočení obrazovky uprostřed curl tahu (spread se zapne/vypne) → `key(useSpread)`
  resetuje `MangaPageCurlReader` na plochý stav (`dragProgress = 0f`) na nové skupině
  odpovídající `currentSingleIndex`, žádná zamrzlá rozpůlená animace.
- Vypnutí togglu uprostřed tahu → příští recompozice vykreslí `MangaReader` na aktuální
  stránce, `dragProgress` se zahodí (žádný "zamrzlý ohyb").

## Testování

Beze změny pro novel vrstvu (viz původní spec). Nově pro manga vrstvu:
- `computePageGroups`: JVM unit testy — bez spreadu (1:1 skupiny), se spreadem (páry),
  se `spreadPageIndices` (vynucené samostatné stránky), lichý počet stránek (poslední
  skupina o 1), prázdný seznam stránek.
- `PageCurlState`/`PageCurlGeometry`/`PageCurlEffect`: žádné nové testy nad rámec Tasku
  3/4/5 (sdíleno, už otestováno) — manga vrstva je jen další VOLAJÍCÍ, ne nová logika.
- `MangaPageCurlReader`/`MangaGroupContent`: bez automatizovaného testu (Compose
  vizuální vrstva) — ověření ručně na emulátoru, viz kombinovaný manuální test v plánu.

## Mimo rozsah (vědomě neřešeno)

- `ReadingMode.WEBTOON` (vertikální scroll) — curl se ho netýká, nemá diskrétní stránky.
- Toggle "Automaticky číst nahlas" (TTS) ze screenshotu — jiná funkce.
- Persistence font size/line spacing/tématu napříč kapitolami novel readeru (dnešní
  mezera, existuje nezávisle na této práci).
- "Plochý stránkovaný bez curlu" mezistav — toggle je binární v OBOU čtečkách.
- Zvětšení zoomu BĚHEM curl tahu (tj. pinch a otočení stránky současně jedním gestem) —
  stejně jako dnes nejde swipe+zoom najednou, nepůjde ani curl+zoom najednou.
