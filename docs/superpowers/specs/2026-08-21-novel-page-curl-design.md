# 3D efekt otáčení stránek (page curl) v novel readeru — Design

**Datum:** 2026-08-21
**Stav:** Návrh schválen uživatelem v chatu (brainstorming), čeká na zápis implementačního plánu.

## Cíl

Přidat do textové čtečky lehkých románů (light novel reader, `NovelContent.kt`)
volitelný režim, ve kterém se kapitola nezobrazuje jako nekonečný scroll (dnešní
chování), ale jako diskrétní stránky, mezi kterými se listuje 3D efektem
ohýbající se stránky — vizuálně na úrovni Google Play Books (skutečné prohnutí
papíru, ne plochá karta/fade přechod). Ovládá se novým togglem v Nastavení
čtečky: **"Použití 3D efektu při otáčení stránek"**.

## Referenční materiál

Uživatel poslal 6 screenshotů z Google Play Books (česká lokalizace):
1. Nastavení čtečky s togglem "Použití 3D efektu při otáčení stránek"
   (vedle togglu "Automaticky číst nahlas" — ten NENÍ součástí této práce).
2.–6. Sekvence snímků z čtení "Pes Baskervillský" (Sherlock Holmes) ukazující
   postupný průběh otáčení stránky — viditelné prohnutí, průsvit textu ze
   zadní strany, stín podél linie ohybu, spodní lišta s "V kapitole zbývá N
   stránek" a "%".

## Zjištění z prozkoumání kódu (výchozí stav)

`NovelContent.kt` dnes vykresluje CELOU kapitolu jako jeden `LazyColumn` se
všemi odstavci — **appka nemá koncept stránky uvnitř kapitoly vůbec**.
`hasPrev`/`hasNext`/`onPrev`/`onNext` přepínají mezi celými KAPITOLAMI (viz
`ReaderScreen.kt:218-236`, `ReaderViewModel.navigatePrev()/navigateNext()`).

Font size (16f), řádkování (1.6f) a barevné téma pozadí jsou dnes jen lokální
`remember` state v `NovelContent.kt` — nejsou perzistovány, resetují se při
každé navigaci na jinou kapitolu. Tahle mezera existuje nezávisle na téhle
práci a NENÍ součástí tohoto scope (neopravuje se).

Vzor pro nový boolean toggle v nastavení je zavedený a jednoduchý —
`SettingsKeys.kt` (`booleanPreferencesKey`), `SettingsRepository.kt`
(`Flow<Boolean>` getter + `suspend fun setX`), `SettingsViewModel.kt`
(passthrough), `ReaderSettingsScreen.kt` (`SettingsToggleRow` uvnitř
existující `SettingsSection`). Přesně tenhle vzor používá už existující
`preloadNextNovelChapter` toggle ve stejné obrazovce (`ReaderSettingsScreen.kt:235-241`).

## Rozhodnutí z brainstormingu (potvrzeno uživatelem)

1. **Vizuální věrnost**: plný page-curl jako Google Books (ne zjednodušený
   plochý 3D flip) — vyžaduje vlastní Canvas/Matrix vykreslení ohybu, ne jen
   `graphicsLayer` rotaci.
2. **Gesto**: tah prstem (roh stránky sleduje pozici prstu během tahu) I
   ťuknutí na okraj obrazovky (spustí stejnou animaci automaticky).
3. **Ukazatel postupu**: ano — "Stránka X z Y" + % dole na obrazovce, stejně
   jako na referenčních screenshotech.
4. **Izolace**: toggle VYPNUTÝ = beze změny (dnešní `LazyColumn` scroll).
   Toggle ZAPNUTÝ = nová stránkovaná komponenta. Žádný "plochý stránkovaný
   bez curlu" mezistav se nestaví — nikdo ho nepožadoval (YAGNI).

## Architektura

Tři nezávislé, samostatně testovatelné kusy:

### 1. Paginátor (čistá logika, bez Android instrumentace)

Nový soubor `NovelPaginator.kt`. Funkce se signaturou přibližně:

```kotlin
data class NovelPage(val startIndex: Int, val endIndex: Int)

fun paginate(
    text: String,
    textMeasurer: TextMeasurer,
    availableWidth: Dp,
    availableHeight: Dp,
    fontSize: Float,
    lineSpacing: Float,
): List<NovelPage>
```

Používá `androidx.compose.ui.text.TextMeasurer` (jde použít i mimo běžící
Compose hierarchii, takže testovatelné jako čistý JVM unit test) k rozměření
textu při dané šířce a spočítání, kolik řádků (a tedy znaků) se vejde do
`availableHeight`. `NovelPage` nese jen rozsah indexů do PŮVODNÍHO řetězce
kapitoly (ne kopii textu) — dělení je mezi-odstavcové stejně jako u Google
Books (věta se může přelomit uprostřed na hranici stránky).

Přepočítává se při: nové kapitole, změně `fontSize`/`lineSpacing`, změně
rozměrů obrazovky (otočení), zapnutí/vypnutí `translateMode` (přeložený text
má jinou délku).

**Zachování pozice při repaginaci**: před přepočtem se zapamatuje
`startIndex` aktuální stránky; po přepočtu se najde první nová stránka, jejíž
rozsah tenhle offset obsahuje, a na ni se skočí. Čtenář neztrácí místo v
textu při změně fontu.

### 2. Gesto + stav vrstva

Nový soubor `PageCurlNovelReader.kt` — nahrazuje `LazyColumn` větev v
`NovelContent.kt`, když je toggle zapnutý. Drží:
- `currentPageIndex: Int` (reset na 0 při nové kapitole/přepočtu, s výjimkou
  zachování pozice popsané výše)
- drag state (aktuální pozice prstu → míra ohybu 0..1)
- detekci prahu při puštění gesta (dokončit otočení vs. vrátit zpět)

**Chování na hranici kapitoly**: tah/ťuk na poslední stránce kapitoly spustí
curl animaci směrem k `onNext()` — po jejím dokončení se zavolá existující
`onNext()` z `ReaderViewModel` (načte se nová kapitola), ta se rovnou
rozseká paginátorem a zobrazí od stránky 0. Analogicky `onPrev()` skočí na
POSLEDNÍ stránku předchozí kapitoly. Když má kapitola jen 1 stránku, tah/ťuk
rovnou volá `onPrev`/`onNext` bez zbytečné prázdné curl animace.

### 3. Vykreslení ohybu

Nový soubor `PageCurlEffect.kt` — čistě vykreslovací logika oddělená od
gesture-handlingu (vstup: aktuální stránka + sousední stránka + míra ohybu
0..1 + pozice tažení → vykreslí). Technika:

1. Aktuální stránka (a příští/předchozí stránka, kterou tažením odkrýváme)
   se vykreslí do `ImageBitmap` (přes `graphicsLayer`/`drawIntoCanvas`) —
   ne živě rekreslovaný text během tahu, stejně jako to dělají reálné
   page-curl implementace na Androidu.
2. Během tahu se přes nativní `android.graphics.Canvas`
   (`drawContext.canvas.nativeCanvas`, dostupné i z Compose `Canvas`)
   spočítá zakřivená linie ohybu podle pozice prstu, bitmapa aktuální
   stránky se skrz `Path`/`clipPath` rozdělí na plochou a ohýbanou část,
   ohýbaná část se `Matrix` transformací (skew/scale) zdeformuje aby
   vypadala jako svinutý papír, přidá se lineární gradient stín podél linie
   ohybu a zrcadlený (mírně tmavší) proužek simulující rub stránky.
3. Po puštění: `Animatable`/`spring` dotáhne míru ohybu na 0 (zpět) nebo 1
   (dokončit), se stejnou vykreslovací funkcí volanou z animačního tickeru.

Tohle je nejrizikovější a nejnáročnější část práce — bude mít vlastní task(y)
v implementačním plánu s prostorem na iteraci, a manuální ověření na
emulátoru/zařízení je nutné (vizuální kvalitu ohybu nejde ověřit
automatizovaným testem).

## Nastavení (toggle)

Standardní vzor, 4 místa:
- `SettingsKeys.kt`: `val NOVEL_PAGE_CURL = booleanPreferencesKey("novel_page_curl")`
- `SettingsRepository.kt`: `val novelPageCurl: Flow<Boolean>` (default `false`)
  + `suspend fun setNovelPageCurl(enabled: Boolean)`
- `SettingsViewModel.kt`: passthrough `StateFlow`/setter stejně jako
  `preloadNextNovelChapter`
- `ReaderSettingsScreen.kt`: nový `SettingsToggleRow` v existující
  `SettingsSection(title = settings_reader_page_display_section_title)`
  nebo nová vlastní sekce — rozhodne implementátor podle toho, co vizuálně
  sedí vedle ostatních novel-specifických přepínačů.

Nové stringy (cs/en/es/fr, stejný vzor jako u `reader_on_device_warning`):
`settings_reader_novel_page_curl_title` = "Použití 3D efektu při otáčení
stránek", `settings_reader_novel_page_curl_desc` (jedna věta vysvětlující
efekt).

## Ukazatel postupu

Součást `PageCurlNovelReader.kt` — text "Stránka {currentPageIndex+1} z
{pages.size}" + procento (`(currentPageIndex+1) * 100 / pages.size`) v dolní
liště, viditelné jen když je stránkovaný režim aktivní (paginátor už počet
stránek zná, prakticky zadarmo).

## Edge cases

- Prázdný text kapitoly / kapitola kratší než 1 stránka → 1 stránka,
  ukazatel "Stránka 1 z 1", žádná curl animace uvnitř kapitoly.
- Extrémně velký font, kam se nevejde ani jedno slovo do šířky → paginátor
  musí vrátit alespoň 1 stránku na "vynucený" obsah místo nekonečné smyčky
  (ošetří se v paginátoru, ne UI vrstvou).
- Vypnutí toggle uprostřed čtení → návrat na scroll, best-effort umístění na
  odstavec odpovídající aktuální stránce (ne pixel-přesné, scroll a stránky
  nejsou 1:1 mapovatelné).

## Testování

- `NovelPaginator`: JVM unit testy (délka textu/rozměry/font → očekávaný
  počet a obsah stránek; hraniční případy z Edge cases výše).
- Zachování pozice při repaginaci: test, že po přepočtu s jiným fontem
  appka najde stránku obsahující stejný offset.
- Gesto/přechod mezi kapitolami: přes state-holder/ViewModel testy (práh
  dokončení vs. vrácení, chování na hranici kapitoly s 1 stránkou).
- `PageCurlEffect`: automatizovaně jen "nespadne to" na různých vstupech;
  vizuální správnost ohybu se ověří ručně na emulátoru — explicitní krok v
  implementačním plánu, ne automatizovaný test.

## Mimo rozsah (vědomě neřešeno)

- Toggle "Automaticky číst nahlas" ze screenshotu (text-to-speech) — jiná
  funkce, nebyla požadována.
- Persistence font size/line spacing/tématu napříč kapitolami (dnešní
  mezera, existuje nezávisle na této práci).
- "Plochý stránkovaný bez curlu" mezistav — toggle je binární (scroll vs.
  plný curl), žádná třetí varianta.
