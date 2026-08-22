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
- **KV-cache (`past_key_values`) - potvrzeno neproveditelné, definitivně mimo v1.**
  Python ověření na reálných datech (viz níže) potvrdilo, že Optimum/HF ONNX exporter
  nemá registrovaný with-past config pro tuhle architekturu (VisionEncoderDecoder +
  BERT-styl decoder) - `optimum-cli export onnx --task image-to-text-with-past` selže
  natvrdo (`ValueError: The decoder part of the encoder-decoder model is bert which
  does not need past key values.`). Ověřeno i na 3 nezávislých komunitních ONNX
  exportech - žádný nemá `past_key_values` I/O. Jediná cesta ke KV-cache by byl vlastní
  ruční `torch.onnx.export` tracing s explicitním graph I/O - výrazně větší a rizikovější
  práce. Změřená reálná latence no-cache greedy decode (~0.1-0.16s/bublinu na desktop
  CPU, encoder ~0.08s + decode ~0.03-0.08s pro typickou 7-9 token bublinu, rychlejší než
  referenční model s beam=4) ukazuje, že přínos KV-cache (rychlost) není pro krátký
  manga text potřeba - O(n²) penalta bez cache je při téhle délce sekvence zanedbatelná.
  v1 tedy používá čistě no-cache decoder (`decoder_model.onnx`, volaný opakovaně s
  CELOU dosavadní `input_ids` sekvencí každý krok). Vlastní KV-cache export zůstává
  možné budoucí vylepšení, jen pokud by se na reálných zařízeních ukázalo, že no-cache
  latence nestačí.
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
 *   `decoderSession.run(...)` s CELOU dosavadní sekvencí (no-cache, viz "Mimo rozsah" -
 *   KV-cache není proveditelné), v testu je to fake lambda.
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

`MangaOcrPipeline.recognizeCrop` pak zapojí `nextToken` na skutečný `decoderSession`:
KAŽDÝ krok volá `decoder_model.onnx` (jediný ONNX decoder, bez `_with_past` varianty)
s inputy `input_ids` (CELÁ dosavadní sekvence `soFar`, ne jen poslední token) a
`encoder_hidden_states` (výstup encoderu, spočítaný jednou na začátku), vezme `logits`
posledního pozice a udělá `argmax`. Na konci `tokenizer.decode(...)` a
`MangaOcrPostProcess.postProcess(...)` (viz níže).

### 3b. Preprocessing (`MangaOcrPipeline.recognizeCrop`, před encoder inferencí)

Přesné pořadí kroků (ověřeno na reálných datech proti referenčnímu modelu, 6/6 shoda):

1. **Grayscale round-trip** - `crop` převést na grayscale a zpátky na RGB (3 identické
   kanály). Tenhle krok NENÍ v `preprocessor_config.json`, dělá ho `MangaOcr.__call__`
   natvrdo před resize - bez něj vychází numericky odlišný (špatný) výstup.
2. Resize na `224x224`, bilineární interpolace.
3. Normalizace: `(pixel/255 - 0.5) / 0.5` pro každý kanál (mean=std=0.5).
4. Přeuspořádání na `NCHW` `FloatArray` (`[1, 3, 224, 224]`), vstup `pixel_values` do
   `encoder_model.onnx`.

### 3c. `MangaOcrPostProcess.kt` (čistá funkce, testovatelná)

Port `post_process()` z referenční Python knihovny, volá se na výstup
`tokenizer.decode(...)` PŘED vrácením textu z `recognizeCrop`:

```kotlin
internal object MangaOcrPostProcess {
    fun postProcess(text: String): String {
        // 1. odstranit VŠECHNY whitespace (join bez mezer, ne jen trim)
        // 2. "…" -> "..."
        // 3. 2+ opakování "." nebo "・" -> stejný počet teček "..."
        // 4. ASCII interpunkce/číslice -> fullwidth japonská (h2z), prostá mapovací
        //    tabulka (jaconv.h2z ascii=True digit=True nemá jinou logiku)
    }
}
```

Žádná závislost na `jaconv` knihovně - `h2z` pro ASCII+digit je jen 1:1 znaková mapa
(např. `!`->`！`, `0`->`０`, `,`->`，` atd.), zapsatelná jako `Map<Char, Char>` v Kotlinu.

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

- Reálně změřená latence (Python spike, no-cache greedy, 6 reálných bublin, desktop
  CPU): ~0.1-0.16s/bublinu (encoder ~0.08s + decode ~0.03-0.08s). I s velkorysou 10x
  penaltou za pomalejší mobilní ARM CPU vychází ~1-1.6s/bublinu - navrhovaný timeout
  níže má tedy velkou rezervu, ne jen odhad naslepo.
- **Nová konstanta** `MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS` (návrh: 8000L), obaluje
  jedno `mangaOcrPipeline.recognizeCrop(...)` volání uvnitř `OcrEngine` -
  `withTimeoutOrNull`. Timeout/výjimka -> ML Kit fallback pro tu bublinu (bod 4), ne
  pro celou stránku.
- **`TranslateRepository.PAGE_OCR_TIMEOUT_MILLIS` (40s) beze změny** - zůstává jako
  vnější pojistka přesně jako dnes; s per-bublina timeoutem už by neměla běžně být
  potřeba, ale chrání i proti sečtenému zpoždění přes víc bublin.

### 6. Assets a bundlování

Nové soubory pod `app/src/main/assets/models/` (Git LFS, stejně jako existující dva
modely) - jen dva ONNX soubory, žádná `_with_past` varianta (KV-cache mimo v1, viz
"Mimo rozsah"), dohromady ~460MB:
- `manga_ocr_encoder.onnx` (343MB)
- `manga_ocr_decoder.onnx` (117MB)
- `manga_ocr_vocab.txt`

`NOTICE.md` dostane třetí sekci - `kha-white/manga-ocr-base`, Apache-2.0, žádná GPL
komplikace jako u segmentačního modelu.

## Ověření na reálných datech (HOTOVO, 2026-08-22)

Provedeno v Python venv (`optimum`/`optimum-onnx`/`onnxruntime`/`manga_ocr` jako
referenční model), reálné výřezy bublin z Vagabonda (`C:\bml\crops\pg*.png`):

1. **Export ONNX**: `optimum-cli export onnx --task image-to-text` (no-cache) uspěl a
   dal `encoder_model.onnx` + `decoder_model.onnx`. `--task image-to-text-with-past`
   selhal natvrdo (viz "Mimo rozsah" - KV-cache config pro tuhle architekturu v
   Optimu neexistuje), potvrzeno i na 3 nezávislých komunitních ONNX exportech.
2. **Numerická správnost**: po doplnění dvou preprocessing/postprocessing detailů
   (grayscale round-trip, `post_process`, viz sekce 3b/3c) - **6/6 bajtově přesná
   shoda** ONNX no-cache greedy decode vs. PyTorch referenční `MangaOcr` (greedy,
   `num_beams=1`) na všech testovaných reálných bublinách.
3. **Latence**: ~0.1-0.16s/bublinu na desktop CPU (encoder ~0.08s + decode
   ~0.03-0.08s pro 7-9 token výstup) - rychlejší než referenční model s výchozím
   beam=4 (~0.2s). Použito jako podklad pro timeout v sekci 5.

Přesné tensor names/shapes, zjištěné preprocessing/tokenizer/decoder konstanty a plný
protokol ověření: `C:\bml\manga_ocr_spike\FACTS.md` (lokální scratch soubor, mimo git
repo appky).

## Testování

- **JVM, čisté funkce** (bez Androidu/ONNX): `MangaOcrTokenizer.decode` (fixture vocab +
  známá ID sekvence -> očekávaný text), `greedyDecode` (fake `nextToken` lambda -
  early-stop na `eosId`, ořezání na `maxTokens`, prázdný vstup), `MangaOcrPostProcess.
  postProcess` (whitespace, "…"/opakované tečky, ASCII->fullwidth mapování - případy
  ověřené proti skutečným výstupům z Python spike v `FACTS.md`), mapování
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
