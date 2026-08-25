# Reader Page Prefetch — Design

## Kontext a problém

Uživatel porovnal Jiyu se svým forkem Kotatsu (`morg1z/Kotatsu`, fork `KotatsuApp/Kotatsu`) a
zjistil, že Kotatsu načítá covery i stránky kapitol několikanásobně rychleji. Analýza obou
codebase (viz commit `fc08122` a jeho commit message) ukázala dva nezávislé problémy:

1. **Síťová vrstva** — Coil sdílel OkHttpClient se scraperem HTML zdrojů, včetně
   `RetryInterceptor` (3× opakování celého řetězce) a redundantního `ThrottleInterceptor`.
   **Vyřešeno samostatně** (Krok A, commit `fc08122`) — nový `@ImageHttpClient` bez těchhle
   dvou interceptorů, napojený do `Coil.setImageLoader(...)` v `JiyuApp.kt`.

2. **Chybí prefetch** (tento dokument, Krok B) — Jiyu nestahuje žádné obrázky dopředu. Ve
   stránkovacím (manga) režimu `HorizontalPager` (`ReaderPager.kt`) defaultně komponuje jen
   AKTUÁLNĚ viditelnou stránku (`beyondViewportPageCount` není nastaveno), takže Coil request na
   další stránku se nespustí, dokud uživatel fakticky nepřejde prstem. Ve webtoon režimu
   (`WebtoonReader.kt`, `LazyColumn`) je to o něco lepší (Compose má vestavěný prefetch podle
   rychlosti scrollu), ale pořád jde jen o ~1 položku dopředu, ne o řízenou frontu. Kotatsu má
   pro tohle vyhrazenou třídu `PageLoader` (`reader/domain/PageLoader.kt` v jejich repu) s
   frontou až 6 stránek dopředu na vlastní diskové cache mimo jejich Coil instanci.

Jiyu na rozdíl od Kotatsu používá Coil jako JEDINOU vrstvu pro vykreslování i cachování všech
obrázků (covery i stránky) — stavět vedle ní druhou, nezávislou cache (jako Kotatsu) by jen
duplikovalo práci. Řešení proto využívá existující Coil infrastrukturu.

**Explicitní požadavek uživatele: žádné vizuální/UI změny.** Tohle je čistě datová/síťová
vrstva na pozadí — žádný Compose soubor (Screen, obrazovka, layout) se nemění.

## Cíl

Když uživatel čte kapitolu, dalších pár stránek by mělo být v Coil cache (paměť/disk) dřív, než
na ně skutečně dojde řada — takže se pro čtenáře jeví jako "okamžité" místo aby čekal na
network round-trip při každém otočení stránky/scrollu.

## Architektura

Nový soubor `app/src/main/kotlin/com/haise/jiyu/ui/reader/ChapterPagePrefetch.kt` s jednou
čistou funkcí (testovatelnou bez Androidu):

```kotlin
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

const val PREFETCH_WINDOW = 4
```

`ReaderViewModel` dostane:
- `private val prefetchedPageIndices = mutableSetOf<Int>()` — reset na začátku `loadChapter()`
  (stejné místo, kde se resetuje `_pages`, `_translatedPages` atd.).
- `private fun prefetchPagesFrom(fromIndex: Int)` — zavolá `computePrefetchIndices(...)`, pro
  každý vrácený index sestaví Coil `ImageRequest` (stejný vzor jako `RetryableAsyncImage` v
  `ReaderImage.kt` — `data(url)`, `addHeader("Referer", ...)` pokud je `_pageReferer` nastaven)
  s `.priority(coil.request.Priority.LOW)`, zavolá `Coil.imageLoader(context).enqueue(request)`
  a přidá index do `prefetchedPageIndices`.

**Volací místa** (obě už v `ReaderViewModel.kt` existují, jen přibude 1 řádek):
1. Konec větve, která nastavuje `_pages.value = rawPages...` v `loadChapter()` (online i offline
   případ) → `prefetchPagesFrom(_initialPage.value)`. Tohle řeší i "otevřel jsem kapitolu
   uprostřed" případ (fresh position restore) — prefetch startuje od SKUTEČNÉ startovní stránky,
   ne od 0.
2. `onPageChanged(index)` → `prefetchPagesFrom(index + 1)`. Okno se posouvá s tím, jak čtenář
   postupuje.

Webtoon "nekonečné čtení" (`appendNextWebtoonSegment`) prefetch NEspouští pro nově přidaný
segment (další kapitola) — to je mimo rozsah tohoto docu (mimo aktuálně otevřenou kapitolu),
zůstává jako budoucí rozšíření.

## Data flow

```
loadChapter() dokončí _pages.value
        │
        ▼
prefetchPagesFrom(initialPage)
        │
        ▼
computePrefetchIndices(initialPage, pages.size, {}, 4)
        │
        ▼
pro každý index: Coil.imageLoader(context).enqueue(ImageRequest(url, Referer, Priority.LOW))
        │
        ▼
(uživatel prohlíží stránky) → onPageChanged(i) → prefetchPagesFrom(i+1) → další okno
```

Skutečné vykreslení stránky (`RetryableAsyncImage`) se NEMĚNÍ — pořád jde přes stejný
`AsyncImage`/`ImageRequest` mechanismus jako dnes. Pokud byla stránka prefetchnutá, Coil ji najde
v paměti/disk cache a vrátí okamžitě; pokud ne (uživatel skočil dál, než okno stihlo doběhnout),
proběhne normální network fetch jako dnes.

## Konfigurace

- `PREFETCH_WINDOW = 4` stránky dopředu, natvrdo (ne uživatelské nastavení — jde o pár obrázků,
  ne o drahou operaci jako OCR překlad další kapitoly, který má vlastní `preloadNextChapterManga`
  přepínač).
- Vždy zapnuto, nerespektuje "jen Wi-Fi" (uživatelské rozhodnutí z brainstormingu — na rozdíl od
  `preloadNextChapterWifiOnly` pro překlad, tohle je řádově menší objem dat).
- Bez vlastního concurrency limitu — `Coil.imageLoader(context).enqueue()` jde přes Coilovu
  vlastní frontu/dispatcher, který už requesty řadí; nepřidává se žádný nový semafor.

## Chybové stavy

Prefetch request, který selže (network chyba, 404, timeout), se **tiše zahodí** — žádný catch
blok navíc není potřeba, protože `enqueue()` je fire-and-forget (Coil interně chybu zaloguje/
zahodí, nic nevyhazuje volajícímu). Žádný UI stav na prefetch nereaguje. Skutečné zobrazení té
stránky pak stejně proběhne přes existující `RetryableAsyncImage` cestu s vlastním
error/retry UI (`isError` stav, tlačítko "Zkusit znovu") — beze změny.

## Testování

- **Unit test** (`ChapterPagePrefetchTest`, JVM, žádný Android/Coil mock potřeba):
  `computePrefetchIndices` — pokrýt: běžné okno uprostřed seznamu, okno u konce seznamu
  (ořezání), prázdný `alreadyPrefetched`, částečně vyplněný `alreadyPrefetched` (vynechání),
  `fromIndex` mimo rozsah (záporné, >= pageCount), `pageCount = 0`.
- **Ruční ověření** na emulátoru/zařízení: otevřít kapitolu (manga i webtoon zdroj), listovat/
  scrollovat a sledovat, že další 2-4 stránky se zobrazí bez viditelného čekání na síť. Ověřit i
  cold-start kapitolu (nikdy dřív nečtenou) i resume z historie (`positionIsFresh` větev).

## Rozsah / co NENÍ součástí

- Prefetch přes hranici kapitoly (další kapitola) — mimo rozsah, existující
  `preloadNextChapter()` (jen URL seznam) zůstává beze změny.
- Žádná nová disková cache mimo Coil.
- Žádná změna `RetryableAsyncImage`, `WebtoonReader.kt`, `ReaderPager.kt` ani jiného UI souboru.
- Žádné uživatelské nastavení pro zapnutí/vypnutí ani velikost okna.
