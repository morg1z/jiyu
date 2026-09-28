# AI překlad — audit kvality (Vagabond VIZBIG kap. 1+2) a plán oprav

## Kontext a problém

Uživatel nechal přeložit Vagabond VIZBIG kapitolu 1 (46 stran, ČB) a kapitolu 2
(~46 stran, barevná úvodní část + ČB) angličtina→čeština. Pro obě kapitoly byl
pořízen kompletní screenshotový průchod originálem (56+56 snímků) i překladem
(48+58 snímků) a shoty porovnány ručně.

Pipeline (`translate/`) je sofistikovaná: `OcrEngine` (ML Kit + manga-ocr ONNX),
`mergeNearbyLines`, `BubbleClassifier` (SFX/bubbleType), `BubbleShapeDetector`
(flood-fill obrys), `BubbleMaskSegmenter` (YOLO), `TranslateRepository`
(provider chain Gemini→OpenRouter→Groq→Mistral→BYOK, `fillUntranslatedBlocks`
retry, `recentLines` kontext, glosář, `isSuspiciousVerbatimCopy`/`isRepetitionLoop`
validace), `TranslationLayout` (expanze + 2-pass kolize), `TranslationLayer`
(clip-shape maska, záplaty, AutoFit text). Navzdory tomu audit našel **37+
konkrétních defektů** v 5 kategoriích.

## Regresní katalog defektů (verifikováno screenshotem)

### A — Vynechaný/částečný obsah

| # | Snímek | Originál | Stav | Pravděpodobný mechanismus |
|---|---|---|---|---|
| A1 | trans2 s046 | "THAT'S..." + "...TSUJIKAZE TENMA." | nepřeloženo v obou průchodech | `BubbleClassifier.detectSfx`: `!bgUniform && letters ≤ 6 && !core.contains(' ')` → "THAT'S"(5) nad kresbou = SFX → nikdy k modelu |
| A2 | trans2 s009 | "SWORDS?" | nepřeloženo | stejné pravidlo ("SWORDS" 6 písmen) |
| A3 | trans2 s006 | "LETHAL..." | nepřeloženo | stejné ("LETHAL" 6) |
| A4 | trans2 s013 | "STOP." | nepřeloženo | STOP je v `commonShortWordsNotSfx`, takže ne SFX — pravděpodobně `isUntranslated` marker z modelu, nebo OCR miss (viz F1) |
| A5 | trans2 s007 | "I SEE" | nepřeloženo | obsahuje mezeru → ne SFX; marker nebo OCR miss |
| A6 | trans1 s037–38 | "NO WAY I'M GONNA DIE!" | celá zubatá bublina EN | marker / OCR miss / jagged-shape selhání |
| A7 | trans2 s007 | "MATA-HACHI'S BEEN" + "PTÁM SE TĚ." | půl bubliny EN, půl CZ ve stejné bublině | dva OCR bloky jedné bubliny; jeden dostal marker (`isUntranslated` → render skip), druhý překlad → maska jen pod překladem |
| A8 | trans1 s036 | "I'M..." vedle "JÁ NE..." | fragment EN vedle přeloženého | fragmentovaný pár — jeden blok marker |
| A9 | trans1 s042–43 | "SOMEONE'S THERE", "...TOO.", "UGH..." | nepřeloženo | "...TOO."/"UGH" mohou být SFX (UGH je v `sfxWords`); SOMEONE'S → marker |
| A10 | trans2 s003 | "HUH?" | nepřeloženo | HUH není v `commonShortWordsNotSfx`? — ověřit; nebo marker |

### B — Layout / render

| # | Snímek | Problém |
|---|---|---|
| B1 | trans1 s003 (TOC) | **Kritické** — překladové bloky se překrývají navzájem, nečitelný shluk. `layoutHeuristic` řeší kolize jen mezi heuristic bloky; shape bloky jsou překážky co "se NIKDY nezmenšují" a shape-vs-shape kolize se neřeší. Na TOC mají pravděpodobně všechny bloky shape → žádné řešení. |
| B2 | trans1 s001 | "Vagakond" overlay přes stylizované logo "Vagabond" — OCR přečetlo art-text a model ho "přeložil" na zkomoleninu. |
| B3 | více | Maskovací box nepokryl celý originální řádek → prosvítající písmena ("duch" originálu za výplní). |
| B4 | trans1 více | Překlad vytéká za okraj bubliny (fitter nedostal dost prostoru / min font nepomohl). |
| B5 | trans2 s006 | "JEDOVATÝ..." overlay překryl SFX text "FWIE" — expanze přes OCR-nezachycený SFX (FWIE neměl blok → žádná překážka). |

### C — OCR chyby

| Snímek | OCR/výstup | Mělo být |
|---|---|---|
| trans1 s001 | "Vagakond" | Vagabond (nebo skip art text) |
| trans1 s018 | "VAL-LAGE" | VILLAGE |
| trans1 | "SHURE" | SURE |
| trans1 | "FOGET" | FORGET |
| trans1 | "SIRVIVOR" | SURVIVOR |
| trans1 | "SILNIC" | ? |
| trans2 s005 | "LIU." | HEY/LOOK? |
| trans2 s006 | "KRVAVÁ MÍSTNOST" | název rostliny (BLOOD-something, ne "místnost") |
| trans2 s005 | "I7" | "17" (či SFX-skip) |

### D — Kvalita překladu (semantika/gramatika)

| Snímek | Výstup | Problém |
|---|---|---|
| trans1 s001 | "Hlasitost" | "Volume" → má být "Svazek" (glosář/kontext) |
| trans1 | "ŠKUBNUTÍ" | "jerk" = nadávka osobě, ne pohyb → "Pitomec" |
| trans1 | "TĚŽKO" | "tough" v kontextu osoby |
| trans1 | "ZABIJ U TĚ." | nesmysl → "Zabij mě pořádně" |
| trans1 | "VY MALÉHO BASTARDA" | rozbitá deklinace → "Ty malý bastarde" (vokativ) |
| trans1 | "HUNTERY UPRCHLÍKŮ" | slovosklad → "Lovci uprchlíků" |
| trans1 | "ZATÍŽETE" | neexistující slovo |
| trans1 | "MAČKY" | "machetes" → "mačety" (OCR i překlad chyba) |
| trans1 | "PROSLAVÍME SE" | "proslavit se" = stát se slavným — špatná sémantika → "Oslavíme to" |
| trans1 | "JEDINÉ CO JSME MUSELI" | chybí Ě → "MUSĚLI" |
| trans1 | "POJĎME JÍT." | redundantní → "Pojďme." |
| trans1 | "NĚCO JE SE MNOU" | slovosklad → "Něco není v pořádku" |
| trans1 | "UDĚLAL JSTE" | "jste" vs "jste" — formální/množné číslo |
| trans2 s005 | "TO JSEM NEMYSL." | useknuté slovo → "nemyslel/jsem to nemyslel" |
| trans2 s007 | "MŮJ, JAK JSI SEBEJISTÝ." | → "Jak sebevědomý" |
| trans2 s009 | "KTERÝ MÁŠ RADĚJI, AKEMI?" | "který" za osobu → "Koho máš raději" |
| trans2 s009 | "CO JSOU TYHLE VŠECHNY MEČE" | slovosklad → "Co je tohle za všechny meče" |
| trans2 s009 | "UPS!" | germanismus → "Jejda!" |
| trans2 | "TAKEZO" | jinde "TAKEZŌ" — nekonzistentní diakritika jmen |

### E — UX

| # | Snímek | Problém |
|---|---|---|
| E1 | trans2 s015–s030 | **Scroll-skoky**: při postupném dolaďování překladu scroll narazil na "konec" v s015, pak se pozice posunula zpět ~15+ viewportů (s030 = obsah z s005, s034 = titulní strana). Mechanismus: LazyColumn výšky položek (`WebtoonPage` placeholder `aspectRatio(0.7f)` → skutečná výška po loadu; při překladu bitmapy dorážejí později kvůli OCR zátěži → rychlý scroll doběhne dřív, než se položky "nafouknou"). |

### Co funguje (neměnit)

- SFX/kanji správně ponechané (FWOOP, GAK, štětce kanji, hexagon "HUF").
- Titulní strany "#2 Akemi" nedotčené.
- Jména s diakritikou většinou OK (TAKEZŌ, MATAHACHI, AKEMI, TSUJIKAZE TENMA).
- Jednoduché kulaté bubliny ~60–70 % korektně — v kap. 2 i dlouhé věty perfektně
  ("MUŽ, KTERÝ ZABIL MÉHO OTCE.", "NEJENŽE NÁS ZACHRÁNILI, ALE POSKYTLI NÁM
  ÚKRYT NA DEN.", "PO KAŽDÉ BITVĚ JDEME NA POLE...").
- Kap. 2 překládá výrazně lépe než kap. 1 (větší/menší korpus kontextu? model?).

## Work packages

### WP1 — Scroll stabilita webtoon čtečky (P0, UX)

**Soubory:** `ui/reader/WebtoonReader.kt`, `ui/reader/ReaderViewModel.kt`

**Problém:** pozice scrollu se při postupném překladu/načítání vrací zpět.

**Podezřelé mechanismy (ověřit v uvedeném pořadí):**
1. Položky LazyColumn mění výšku po načtení bitmapy (placeholder 0.7 vs reálný
   ~0.66–0.7) — u rychlého scrollu pod OCR zátěží se vyhrazená výška vs skutečná
   liší a LazyColumn pozici re-anchoruje. Diagnóza: `adb shell` + log `size` změn
   položek; repro: překlad zapnutý + rychlý scroll.
2. `LaunchedEffect(segments.firstOrNull()?.chapterId)` → `scrollToItem(initialPage)`
   — pokud `_webtoonSegments` přestaví (nekonečné čtení přidá segment, ViewModel
   může dropnout starý/změnit first), restore-scroll skočí na uloženou pozici.
3. `onVisibleChapterChanged` → ViewModel zapisuje pozici; při rebuildu seznamu by
   `initialPage`/`initialScrollOffset` mohly živit restore-effect zastaralými daty.

**Fix:** restore-effect spouštět JEN při skutečné změně otevřené kapitoly
(klíč na `chapterId` + flag "user navigated", ne na rebuild seznamu); položky
ukotvit `key` (už je `"$chapterId:$i"`); u placeholderu zvážit přesnější poměr
(z `PageBitmapLoader` známé rozměry po prvním decodu — může cacheovat per chapter).

**Test:** manuální repro na telefonu (translate ON + rychlý scroll), pozice nesmí
poskočit; JVM unit nerelevantní (UI chování).

### WP2 — Kolize overlay bloků (P0, vizuální)

**Soubory:** `translate/TranslationLayout.kt`, `ui/reader/TranslationLayer.kt`

**Problém:** shape-vs-shape kolize se neřeší (TOC chaos); SFX bez OCR bloku
nemá překážku → expanze přes něj.

**Fix:**
1. Po `layoutTranslationBlocks` přidat pass `resolveShapeCollisions`: bounding-rect
   překryv dvou shape bloků → zmenšit/posunout ten s menším OCR obsahem (nikdy pod
   `block.bottomF`), případně z-order: menší blok vykreslit později (navrch).
2. TOC případ: pokud stránka má > N bloků na jednom panelu, zvážit režim "řádková
   sazba" — žádná expanze, jen fit do vlastního boxu.
3. B5 (JEDOVATÝ×FWIE): bez OCR bloku nelze překážku znát — zmírnění: expanze
   `bgUniform=false` bloků už je 1.15f; ověřit, že se uplatnila (JEDOVATÝ je
   pravděpodobně shape blok → expanze neprobíhá → kolize je pak mezi maskou a
   nakresleným SFX → řeší WP2#1 jako shape-vs-rendered-text zón).

**Test:** `TranslationLayoutTest` — syntetické překrývající shape bloky → žádný
překryv v `positioned`; fixture "TOC" (12 bloků v jednom sloupci).

### WP3 — SFX misklasifikace krátkých replik (P1)

**Soubor:** `translate/BubbleClassifier.kt`

**Problém:** pravidlo `!bgUniform && letters ≤ 6 && !core.contains(' ') &&
!commonShortWordsNotSfx` → krátká replika nad kresbou = SFX = nikdy nepřeložená.
Ověřené oběti: THAT'S, SWORDS?, LETHAL.

**Fix (konzervativní, ne obecné):**
- Has-vowel tokeny: SFX jen když `letters ≤ 4` NEBO v `sfxWords`/`collapsedSfxWords`
  ("PLOP", "THUD" zůstane; "SWORDS"/"LETHAL"/"THAT'S" přejde k překladu).
  Slovník `sfxWords` si ohlídá všechny známé zvuky s samohláskou.
- `commonShortWordsNotSfx` rozšířit o: THAT'S, IT'S, DON'T, CAN'T, WHAT'S, SWORDS?,
  LETHAL, SURE, WAIT, LISTEN apod. — i přesto, že je otevřená množina (levná pojistka).
- Bonus signál: `nativeLineHeightF` — SFX lettering nad kresbou je typicky ≥1.5×
  průměrná výška řádku stránky; pod tím = replika. (Ověřit na fixture datech,
  ne naslepo.)

**Test:** `BubbleClassifierTest` — "SWORDS?" over-art → ne-SFX; "FWOOSH" over-art →
SFX; "THAT'S..." → ne-SFX; regrese na existující SFX.

### WP4 — Fragmentované páry bublin (P1)

**Soubory:** `translate/BubbleMerge.kt`, `translate/BubbleContinuation.kt`,
`translate/TranslationMerge.kt`, `translate/TranslateRepository.kt`,
`translate/OcrEngine.kt` (`mergeNearbyLines`)

**Problém:** věta rozdělená do dvou bloků/bublin ("I'M..."+"JÁ NE...",
"MATA-HACHI'S BEEN"+"ASKING YOU") se překládá separátně — jeden dostane marker →
půl bubliny EN + půl CZ, nebo rozbitý překlad.

**Fix:**
1. Pre-merge: před klasifikací spárovat bloky ve STEJNÉ detekované bublině
   (obsažené v jednom `BubbleBoxDetector`/`BubbleShapeDetector` tvaru) — dnes
   `mergeNearbyLines` slučuje jen geometricky blízké řádky, ne bloky pod jedním
   obrysem.
2. Pair-aware prompt: bloky kde jeden končí "..." a následný začíná "..." nebo
   malým písmenem → odeslat modelu s flag "continuation" (prompt: přeložit jako
   jednu větu, vrátit split).
3. Fallback renderu: blok `isUntranslated` sousedící (overlap/stejná bublina) s
   přeloženým blokem → sloučit jejich vizuální reprezentace (maska se kreslí
   pro oba, text se sází do sloučeného prostoru) — než aby půl bubliny zůstala
   anglicky. Implementačně: v `layoutTranslationBlocks` předstupňový pass
   "merge sibling untranslated blocks into translated parent".

**Test:** fixture dvě OCR bloky v jedné bublině → jeden TranslatedBlock /
správný render.

### WP5 — OCR kvalita (P1)

**Soubory:** `translate/OcrTextCleanup.kt`, `translate/OcrEngine.kt`

**Fix:**
1. `OCR_WORD_FIX_TABLE` rozšířit o verifikované zkomoleniny:
   FOGET→FORGET, SIRVIVOR→SURVIVOR, SHURE→SURE, LIU→HEY/LOOK (ověřit z originálu),
   NEMYSL není OCR (viz WP6).
2. `fixNoisyUpperToken`: nový vzor — osamělé `I` uvnitř slova kde má být `U`
   (SIRVIVOR), koncové `-T` vs `-R`? (FOGET) — jen přes tabulku, ne pravidlem.
3. Hyphen-artifact "VAL-LAGE"/"VIL-LAGE": `joinHyphenatedLineBreaks` existuje
   pro `\n`; rozšířit na "slovo rozdělené pomlčkou UVNITŘ řádku tam, kde
   spojení tvoří běžné slovo" — bezpečné jen přes wordlist lookup.
4. Cheap lint: slovník EN top-30k (asset ~300 KB) — token mimo slovník +
   Levenshtein ≤1 do slovníku → oprav. Omezené na ALL-CAPS tokeny ≥4 písmen.
   (Konzervativní, pouze log+fix tabulkou? — rozhodnout při implementaci;
   slovníkový lint je hlavní hodnota WP5.)

**Test:** `OcrTextCleanupTest` — nové případy z katalogu.

### WP6 — Kvalita překladu (P2)

**Soubory:** `translate/GeminiUltraPrompt.kt`, `translate/TranslateRepository.kt`,
`data/` (GlossaryRepository), `assets/`

**Fix:**
1. Prompt: CS-specifická pravidla — vokativ při oslovení ("ty malý bastarde"),
   slovosklad "X Y" genitivu ("Lovci uprchlíků"), "Volume"→"Svazek", povinná
   diakritika (MUSĚLI), slovesný způsob (Pojďme. ne Pojďme jít.), názvy jmen
   s makrony konzistentně (TAKEZŌ všude).
2. Seed glosáře CS: Volume→Svazek, Chapter→Kapitola, obvyklé termíny.
3. Output lint: CS frekvenční wordlist (asset ~30k slov) — přeložený token mimo
   slovník + není jméno/vlastní → flag; flagovaný blok → repair retry (jiný
   provider/model z chain) nebo `isUntranslated` (radši originál než "ZATÍŽETE").
   Chytí: MUSELI, NEMYSL, ZATÍŽETE, VAL-LAGE, germanismy (UPS!).
4. Kontext: `recentLines` už jde do promptu — rozšířit `mangaContext` o typ
   (manhwa→CZ slang ok, seinen→formálnější)? (Nice-to-have, až po 1–3.)

**Test:** wordlist unit test (MUSĚLI in, MUSELI out); prompt snapshot diff.

### WP7 — Maska/overflow renderu (P1–P2)

**Soubory:** `ui/reader/TranslationLayer.kt`, `translate/BubbleTextFit.kt`,
`translate/BubbleShapeDetector.kt`

**Fix:**
1. Maska musí pokrýt CELÝ union OCR řádků bloku — ověřit, že `RawTextBlock`
   bounds po `mergeNearbyLines` jsou union všech zdrojových řádků (případ
   "MATA-HACHI'S BEEN" viditelný nad překladem = sibling blok, řeší WP4; ale
   prosvítající "duch" = maska menší než glyfy — zvětšit bleed pro víceřádkové
   bloky o ±1 řádek podle `nativeLineHeightF`).
2. Overflow za bublinu: když `AutoFitTranslatedText` ani na `minTranslationFontSp`
   nesedí do vepsaného obdélníku → clip konturou bubliny (`BubbleClipShape`
   existuje) + preferovat zkrácení `displayText` (model už dává syllable_breaks;
   přidat prompt-side "max_chars" enforcement — `BubbleClassifier.sizeTag` už
   posílá limit do promptu — ověřit dodržování, případně tvrdý post-řez).
3. Art-text skip: titulní/logo text ("Vagabond"→"Vagakond") — bloky s
   `bgUniform=false` + nepravidelný font naznačuje heuristika; bezpečnější:
   pokud OCR text ≠ přeložený text se liší jen diakritikou/poškozením
   (Levenshtein ≤2 na stejné délce) → považovat za art-text → `isSfx`/skip.
   Případ "Vagakond" přesně matchuje (Vagabond→Vagakond).

**Test:** `BubbleTextFit`/layout testy; screenshot regrese kap. 1 s001.

### WP8 — Coverage diagnostika (P1, observabilita)

**Soubory:** `translate/TranslationDiagnostics.kt`, `TranslateRepository.kt`,
`ui/reader/ReaderControls.kt` (nebo progress UI)

**Problém:** vynechané bubliny se dnes poznají jen vizuálně — `BubbleSkip` log +
`TranslationDiagnostics.recordPage` existují, ale není srozumitelný souhrn.

**Fix:** per-stránka metrika `detected / translated / skipped(isSfx) /
untranslated(marker)` → log + persist do diagnostics; v readeru po dokončení
překladu toast/snack "kapitola: 214/240 bublin přeloženo"; při >X % untranslated
na stránce → varování. Levné, zpřístupní data pro WP3/4/5 rozhodování.

**Test:** diagnostics unit; manuálně logcat při překladu.

## Pořadí implementace

1. **WP1** scroll stabilita — největší UX dopad, pravděpodobně malý diff.
2. **WP2** kolize — P0 vizuální (TOC nečitelné).
3. **WP8** diagnostika — získá data pro rozhodnutí WP3/4/5 (kolik je marker vs
   SFX vs OCR miss) — levné, dělat brzy.
4. **WP3** SFX misclass — malý diff, velký dopad na vynechané krátké repliky.
5. **WP4** fragmenty/půl-bubliny — střední obtížnost, velký vizuální dopad.
6. **WP5** OCR lint/tabulka.
7. **WP7** maska/overflow.
8. **WP6** kvalita překladu — největší scope, iterativní.

## Verifikace

- JVM unit testy za každý WP (classifier/layout/cleanup/wordlist) — existující
  test třídy rozšířit.
- On-device screenshot re-pass (stejný protokol: fotka→scroll→fotka) na Vagabond
  ch1+ch2 po každém milníku; porovnat proti `scratch/shots/orig*`/`scratch/shots/trans*`.
- `adb logcat -s BubbleSkip TranslateRepository` při překladu — počty skipů.
- Gate: žádná nová regrese v existujících testech (`./gradlew testDebugUnitTest`).

## Rizika / poznámky

- WP3 restrikce `≤4` může propustit skutečné krátké zvuky s samohláskou mimo
  slovník ("PAK" je bez samohlásky → bez dopadu; "BAM" je v `sfxWords`) — vliv
  malý, `sfxWords` pokrývá hlavní inventář.
- WP6 slovníkový lint může flagovat jména/postavy (TAKEZŌ mimo CS slovník) →
  pravidlo musí vyjímat CAPS-only tokeny a glosářové pojmy.
- WP4 geometrický pre-merge mění blokovou mapu → `positioned` indexy v
  `TextPatchProvider`/`ManualTranslationEntity` jsou klíčované indexem v seznamu
  — merge změní indexace → ruční edity starých cache záznamů se mohou rozjíždět;
  řešit klíčem (pageIndex+bubbleIndex je už poziční — dokumentované omezení).
- PIPELINE_VERSION: změny ovlivňující `TranslatedBlock` obsah (WP4/6) → bump
  verze v cacheId, jinak staré cache záznamy přetrvají.
