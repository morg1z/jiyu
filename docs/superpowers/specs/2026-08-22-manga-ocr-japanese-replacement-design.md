# Náhrada ML Kit OCR specializovaným manga-ocr modelem pro japonštinu

Datum: 2026-08-22
Stav: schváleno uživatelem, jde se do implementace

## Problém

ML Kit japonský rozpoznávač textu (`JapaneseTextRecognizerOptions`) je obecný OCR model,
ne specializovaný na manga lettering - stylizovaná písmena, ruční lettering, tučné/šikmé
"impact" fonty a svislý sloupcový text mu dělají problémy. Konkurenční appka OpenToon má
oproti Jiyū hlavní výhodu právě v robustnosti OCR (cloudový GPU model), ne v kvalitě
překladu nebo vykreslování - vlastní pipeline Jiyū (detekce tvaru bubliny, font podle
stylu bubliny, česky specifický prompt) je jinak srovnatelná nebo lepší.

`kha-white/manga-ocr` je specializovaný model (Apache-2.0, licenčně bez problému, viz
[project_ocr_quality_initiative]) natrénovaný přímo na japonský manga text včetně
vertikálního sázení - ale na rozdíl od ML Kit čte VÝŘEZ CELÉ bubliny najednou (ne
řádek po řádku) a je to autoregresivní encoder-decoder transformer, ne jednoprůchodový
model jako oba už zabundlované YOLOv8 detektory (`BubbleBoxDetector`,
`BubbleMaskSegmenter`).

## Cíl

Nahradit ML Kit jako zdroj OCR textu pro japonštinu (jak explicitně zvolenou, tak
rozpoznanou přes `AUTO_LANGUAGE`) specializovaným `manga-ocr` modelem, běžícím čistě
on-device přes ONNX Runtime - stejný vzor jako existující `BubbleBoxDetector`/
`BubbleMaskSegmenter`. Appka nikdy nesmí zůstat bez textu jen kvůli tomuhle modelu -
každá bublina musí mít fallback na ML Kit.

## Mimo rozsah (YAGNI, možné navazující PR)

- **Ostatní jazyky** (korejština, čínština, latinka) - `manga-ocr` je natrénovaný
  výhradně na japonštinu, pro ně zůstává ML Kit beze změny.
- **Beam search dekódování** - první implementace používá greedy (1 paprsek) dekódování
  místo modelového výchozího beam=4. Jednodušší kód, nižší riziko timeoutu, dost dobrá
  přesnost pro první verzi. Beam search jako možné vylepšení kvality později, až bude
  vidět reálná přesnost greedy na uživatelských datech.
- **Ruční KV-cache logika v Kotlinu** - pokud community ONNX export dodržuje standardní
  HF Optimum seq2seq layout (`encoder_model.onnx` + `decoder_model.onnx` +
  `decoder_with_past_model.onnx`), cache logiku počítá samotný ONNX graf; Kotlin jen
  přeposílá `past_key_values` tenzory mezi kroky. Pokud se při Python ověření (viz níže)
  ukáže, že tenhle layout export nemá, je to blokující zjištění - řešení (vlastní export
  přes `optimum-cli`, nebo přijmout pomalejší no-cache varianty) je samostatné
  rozhodnutí, ne součást týhle specifikace.
- **Detekce jazyka uvnitř `MangaOcrPipeline`** - o tom, jestli je stránka japonská,
  rozhoduje beze změny stávající `resolveAutoLanguage` (ML Kit trial), viz níže.

## Architektura

### 1. `MangaOcrPipeline` (nový soubor `translate/MangaOcrPipeline.kt`)

Tenký ONNX Runtime obal, stejný vzor jako `BubbleBoxDetector`/`BubbleMaskSegmenter` -
nikdy nevyhazuje, selhání se loguje přes `report()` a appka spadne na ML Kit fallback
(viz sekce 4).

```kotlin
@Singleton
class MangaOcrPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bubbleBoxDetector: BubbleBoxDetector,
) {
    /**
     * Najde bubliny na celé stránce ([BubbleBoxDetector], znovupoužitý Apache-2.0 model -
     * žádný nový box model netřeba) a pro každou přečte text přes manga-ocr. Bubliny, kde
     * selže i ML Kit fallback (viz OcrEngine), se v seznamu vůbec neobjeví.
     */
    suspend fun detectAndRecognize(bitmap: Bitmap): List<RawTextBlock>

    /** Přečte JEDNU už oříznutou bublinu - viz per-bublina timeout v OcrEngine. */
    suspend fun recognizeCrop(crop: Bitmap): String?
}
```

`detectAndRecognize` volá `bubbleBoxDetector.detect(bitmap)`, pro každý box ořízne
bitmapu (s malým okrajem, aby se do výřezu vešel i tenký okraj bubliny kolem textu) a
zavolá `recognizeCrop`. Chybu/timeout jednotlivé bubliny neřeší tahle třída - to je
zodpovědnost volajícího (`OcrEngine`), protože jen on zná ML Kit recognizer pro fallback.

### 2. Tokenizer (`translate/MangaOcrTokenizer.kt`, čistá/testovatelná)

```kotlin
/** Načte se z assets/models/manga_ocr_vocab.txt - jeden token na řádek, index = ID. */
internal class MangaOcrTokenizer(vocab: List<String>) {
    fun decode(ids: List<Int>): String   // lookup + spojení, žádné BPE merge
    val bosId: Int   // [CLS] / start token
    val eosId: Int   // [SEP] / konec generování
}
```

Model má malý (6144) BERT-styl slovník - dekódování je prostý lookup + zřetězení, žádná
detokenizace jako u BPE. Testovatelné čistě v JVM s malým fixture vocab souborem.

### 3. Greedy decode smyčka (`translate/MangaOcrDecode.kt`, čistá/testovatelná)

Klíčová věc pro testovatelnost: samotná smyčka je čistá funkce s injektovaným "dej mi
další token" krokem - stejný vzor, jaký už `resolveAutoLanguage` používá pro testování
bez ML Kitu.

```kotlin
/**
 * @param nextToken (dosavadní ID tokeny) -> ID dalšího tokenu. V produkci volá
 *   `decoderSession.run(...)` s `past_key_values`, v testu je to fake lambda.
 */
internal suspend fun greedyDecode(
    bosId: Int,
    eosId: Int,
    maxTokens: Int = MAX_DECODE_TOKENS,
    nextToken: suspend (soFar: List<Int>) -> Int,
): List<Int>
```

`MAX_DECODE_TOKENS` bude výrazně nižší než modelový default (300, počítaný pro celé
stránky/odstavce) - jedna bublina manga textu je pár slov, navrhovaná hodnota **96**
jako bezpečný strop (early-stop přes `eosId` je normální cesta, `maxTokens` je jen
pojistka proti nekonečné smyčce).

`MangaOcrPipeline.recognizeCrop` pak jen zapojí `nextToken` na skutečný `decoderSession`
(první krok přes `decoder_model.onnx` bez cache, další kroky přes
`decoder_with_past_model.onnx` s `past_key_values` z předchozího kroku) a na konci
zavolá `tokenizer.decode(...)`.

### 4. Zapojení do `OcrEngine.recognize()`

Rozšíří stávající tři úrovně (dnes: ML Kit → `resolveAutoLanguage` pro Auto) o novou
podmíněnou větev:

```
resolveLanguage (beze změny - ML Kit trial rozhodne mezi English/Japanese/Korean/Chinese)
  -> pokud finální rozpoznaný jazyk == "Japanese":
       zahodit ML-Kit-trial "lines" výsledek (byl jen na ROZHODNUTÍ jazyka)
       -> mangaOcrPipeline.detectAndRecognize(bitmap)
       -> pro bubliny, kde manga-ocr selhal/timeoutoval: ML Kit fallback na OŘÍZNUTÝ box
       -> sortIntoReadingOrder(blocks, rightToLeft = true)   [beze změny]
  -> jinak: stávající recognizeLines/mergeNearbyLines cesta, beze změny
-> stávající sdílená kaskáda (bgSample, detectShape/edgeAwareShape/BubbleMaskSegmenter,
   logShapeCoverage, cache) - BEZE ZMĚNY, je jazykově nezávislá
```

**`RawTextBlock` pole u manga-ocr bloků:** `nativeLineHeightF` zůstává `0f` (v kódu už
dnes znamená "neznámé", fitter má pro tenhle případ připravené chování - hledá největší
velikost písma, co se vejde, viz `TranslatedBlock.nativeLineHeightF` dokumentace).
`isVertical`/`lineCount` se po `mergeNearbyLines` nikde dál nepoužívají (ověřeno grepem -
jen uvnitř `shouldMerge`, kterou pro japonštinu úplně přeskočíme) - zůstávají na
defaultu, bez ztráty kvality.

**ML Kit fallback pro jednu bublinu** (nová privátní funkce v `OcrEngine`):
oříznout bitmapu podle boxu bubliny, `InputImage.fromBitmap(crop, 0)`, pustit přes
`japaneseRecognizer.process(...)`, spojit nalezený text řádků do jednoho stringu. Stejný
recognizer, co dnes `OcrEngine` už lazy vytváří pro `recognizeLines`.

### 5. Timeout

- **Nová konstanta** `MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS` (návrh: 8000L), obaluje
  jedno `mangaOcrPipeline.recognizeCrop(...)` volání uvnitř `OcrEngine` -
  `withTimeoutOrNull`. Timeout/výjimka -> ML Kit fallback pro tu bublinu (bod 4), ne
  pro celou stránku.
- **`TranslateRepository.PAGE_OCR_TIMEOUT_MILLIS` (40s) beze změny** - zůstává jako
  vnější pojistka přesně jako dnes; s per-bublina timeoutem už by neměla běžně být
  potřeba, ale chrání i proti sečtenému zpoždění přes víc bublin.

### 6. Assets a bundlování

Nové soubory pod `app/src/main/assets/models/` (Git LFS, stejně jako existující dva
modely):
- `manga_ocr_encoder.onnx`
- `manga_ocr_decoder.onnx`
- `manga_ocr_decoder_with_past.onnx`
- `manga_ocr_vocab.txt`

`NOTICE.md` dostane třetí sekci - `kha-white/manga-ocr-base`, Apache-2.0, žádná GPL
komplikace jako u segmentačního modelu.

## Ověření na reálných datech (PŘED psaním Kotlin/Android kódu)

Stejný postup jako u YOLOv8 modelů dřív v týhle iniciativě: v Python venv (`ultralytics`/
`optimum`/`onnxruntime`, real manga screenshoty použité i pro předchozí ověření):

1. Export/stažení ONNX (`mayocream/manga-ocr-onnx` nebo vlastní export přes
   `optimum-cli export onnx`) a **potvrzení, že layout obsahuje `decoder_with_past`**
   (viz "Mimo rozsah" - pokud ne, je to blokující zjištění vyžadující nové rozhodnutí).
2. Porovnání výstupu ONNX inference (encoder + greedy decode s KV-cache) proti
   referenčnímu PyTorch `MangaOcr` volání na několika reálných manga výřezech - musí
   sedět text, ne jen "nespadne".
3. Změření skutečné latence jednoho `recognizeCrop` volání v Pythonu (orientační odhad
   pro návrh `MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS`, finální číslo se doladí až na
   reálném zařízení).

## Testování

- **JVM, čisté funkce** (bez Androidu/ONNX): `MangaOcrTokenizer.decode` (fixture vocab +
  známá ID sekvence -> očekávaný text), `greedyDecode` (fake `nextToken` lambda -
  early-stop na `eosId`, ořezání na `maxTokens`, prázdný vstup), mapování
  `DetectedBubbleBox` -> `RawTextBlock` (souřadnice, výchozí hodnoty polí popsané v
  bodě 4).
- **`MangaOcrPipeline` samotná zůstává netestovaná JVM testem** (stejně jako
  `BubbleBoxDetector`/`BubbleMaskSegmenter`) - potřebuje Android `Bitmap`/`OrtSession`.
  Ověření přes on-device probe test (stejný vzor jako `CascadingBubbleOnDeviceTest`) na
  reálném japonském manga screenshotu, kontrola, že vrácený text dává smysl a že
  fallback na ML Kit funguje, když se model uměle donutí selhat.
- **Existující testy pro `resolveAutoLanguage`, `mergeNearbyLines`, `sortIntoReadingOrder`
  atd. beze změny** - tahle práce se do nich nezasahuje, jen se přidává nová větev PŘED
  nimi (pro japonštinu) nebo úplně mimo ně (ML Kit cesta pro ostatní jazyky).
