# Manga-OCR náhrada ML Kit OCR pro japonštinu — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nahradit ML Kit japonský OCR rozpoznávač specializovaným on-device modelem
`kha-white/manga-ocr` (ONNX, Apache-2.0) jako hlavní zdroj rozpoznaného textu pro
japonštinu (explicitní i přes `AUTO_LANGUAGE`), se zachovaným ML Kit fallbackem pro
jednotlivé bubliny, kde manga-ocr selže nebo vyprší timeout.

**Architecture:** Nová `MangaOcrPipeline` (tenký ONNX Runtime obal, stejný vzor jako
`BubbleBoxDetector`/`BubbleMaskSegmenter`) skládá dohromady čtyři čisté, JVM-testovatelné
kusy: `MangaOcrPreprocessing` (grayscale round-trip + resize + normalize), `MangaOcrTokenizer`
(vocab lookup dekódování), `greedyDecode` (no-cache autoregresivní smyčka s injektovaným
"další token" krokem) a `MangaOcrPostProcess` (whitespace/tečky/fullwidth). `OcrEngine`
dostane novou japonskou větev: `BubbleBoxDetector.detect()` najde bubliny nezávisle na
jazyce, `mangaOcrPipeline.recognizeCrop(...)` se volá PŘÍMO uvnitř `OcrEngine` s
per-bublina timeoutem (`withTimeoutOrNull`), a při `null`/timeoutu spadne na ML Kit
japonský recognizer na tom samém oříznutém výřezu — appka proto nikdy nezůstane bez textu
jen kvůli tomuhle modelu. `OcrEngine` proto dostává nové konstruktorové závislosti
`BubbleBoxDetector` (přímo, ne jen skrz `MangaOcrPipeline`) a `MangaOcrPipeline` — to je
jediné odchýlení od spec doslovného kódu v sekci 1: spec ukazuje jen konstruktor
`MangaOcrPipeline`, ne `OcrEngine`, ale timeout musí sedět přímo na `recognizeCrop`
volání uvnitř `OcrEngine` (spec sekce 5), takže `OcrEngine` potřebuje vlastní přístup k
bublinám nezávisle na `MangaOcrPipeline.detectAndRecognize` (ten zůstává jako
bez-fallbacku "happy path" metoda, použitá on-device probe testem).

**Tech Stack:** Kotlin, ONNX Runtime Android (`ai.onnxruntime`, už závislost projektu —
viz `BubbleBoxDetector`/`BubbleMaskSegmenter`), ML Kit Text Recognition (japonský
recognizer, už závislost), Hilt (`@Singleton`/`@Inject`), JUnit4 + `kotlinx-coroutines-test`
pro JVM testy, `androidx.test`/`AndroidJUnit4` pro on-device testy.

**Spec:** `docs/superpowers/specs/2026-08-22-manga-ocr-japanese-replacement-design.md`

## Global Constraints

- Práce se dělá přímo na `master`, žádná feature branch (zavedená konvence projektu).
- `JAVA_HOME` musí být nastaven na `C:\Program Files\Android\Android Studio\jbr` před
  každým `./gradlew` voláním (nepřežívá mezi Bash voláními).
- Po každém tasku, co mění Kotlin zdrojáky: `./gradlew compileDebugKotlin testDebugUnitTest`,
  teprve pak commit.
- Vše musí být zdarma (CLAUDE.md pravidlo 1) — model i ONNX Runtime běží čistě on-device,
  žádné nové placené závislosti. Appka nikdy neotevírá browser (pravidlo 4) — nedotčeno,
  tahle práce je čistě OCR pipeline.
- **Beam search, KV-cache a detekce jazyka uvnitř `MangaOcrPipeline` jsou mimo rozsah**
  (viz spec "Mimo rozsah") — žádný task je nesmí implementovat. `MAX_DECODE_TOKENS = 96`,
  greedy (beam=1), no-cache decoder volaný s CELOU dosavadní `input_ids` sekvencí každý krok.
- Ostatní jazyky (korejština, čínština, latinka) zůstávají na ML Kitu beze změny — žádný
  task se jejich cesty v `OcrEngine.recognize()` nedotýká.
- Existující testy (`AutoLanguageDetectionTest`, `mergeNearbyLines`/`sortIntoReadingOrder`
  testy atd.) zůstávají beze změny — nová japonská větev se jen PŘIDÁVÁ před/mimo ně.
- Nové `.onnx` assety jdou přes Git LFS — `.gitattributes` už pokrývá
  `app/src/main/assets/models/*.onnx`, žádná změna configu potřeba.

---

### Task 1: Assety a NOTICE.md

**Files:**
- Create: `app/src/main/assets/models/manga_ocr_encoder.onnx` (zkopírovat z
  `C:\bml\manga_ocr_spike\onnx_export_nocache\encoder_model.onnx`, 343 377 067 bajtů)
- Create: `app/src/main/assets/models/manga_ocr_decoder.onnx` (zkopírovat z
  `C:\bml\manga_ocr_spike\onnx_export_nocache\decoder_model.onnx`, 117 445 718 bajtů)
- Create: `app/src/main/assets/models/manga_ocr_vocab.txt` (zkopírovat z
  `C:\bml\manga_ocr_spike\onnx_export_nocache\vocab.txt`, 6144 řádků)
- Modify: `app/src/main/assets/models/NOTICE.md` (přidat třetí sekci na konec souboru)

**Interfaces:**
- Produces: `assets/models/manga_ocr_encoder.onnx` (input `pixel_values`
  `[1,3,224,224]`, output `last_hidden_state` `[1,197,768]`), `manga_ocr_decoder.onnx`
  (inputs `input_ids` `[1,seq_len]` + `encoder_hidden_states` `[1,197,768]`, output
  `logits` `[1,seq_len,6144]`), `manga_ocr_vocab.txt` (6144 řádků, index řádku = ID
  tokenu) — Task 3 (tokenizer) a Task 6 (`MangaOcrPipeline`) na tyhle assety navazují
  přesně těmito názvy souborů a tenzorů.

- [ ] **Step 1: Zkopírovat tři soubory do assets**

```bash
cp "C:/bml/manga_ocr_spike/onnx_export_nocache/encoder_model.onnx" \
   "C:/Users/ilekr/Desktop/jiyu/app/src/main/assets/models/manga_ocr_encoder.onnx"
cp "C:/bml/manga_ocr_spike/onnx_export_nocache/decoder_model.onnx" \
   "C:/Users/ilekr/Desktop/jiyu/app/src/main/assets/models/manga_ocr_decoder.onnx"
cp "C:/bml/manga_ocr_spike/onnx_export_nocache/vocab.txt" \
   "C:/Users/ilekr/Desktop/jiyu/app/src/main/assets/models/manga_ocr_vocab.txt"
```

- [ ] **Step 2: Ověřit, že Git LFS soubory zachytí**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" check-attr filter -- app/src/main/assets/models/manga_ocr_encoder.onnx app/src/main/assets/models/manga_ocr_decoder.onnx
```

Expected: oba řádky končí `filter: lfs` (existující `.gitattributes` glob
`app/src/main/assets/models/*.onnx` je pokrývá beze změny configu).

- [ ] **Step 3: Přidat sekci do `NOTICE.md`**

Na konec `app/src/main/assets/models/NOTICE.md` (za poslední řádek existující GPL sekce):

```markdown

## manga_ocr_encoder.onnx + manga_ocr_decoder.onnx + manga_ocr_vocab.txt

- Zdroj: https://huggingface.co/kha-white/manga-ocr-base
- Autor: kha-white
- Licence: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Architektura: Vision Encoder-Decoder (ViT encoder + BERT-styl decoder, 2 vrstvy,
  12 hlav, hidden_size 768) natrénovaný speciálně na japonském textu v manze (včetně
  svislého a stylizovaného písma) - na rozdíl od obecného OCR čte celý oříznutý obrázek
  bubliny najednou, ne řádek po řádku.
- Do ONNX (bez KV-cache - `image-to-text-with-past` export selhává, protože dekodér je
  BERT-styl a `past_key_values` nepotřebuje) exportováno lokálně přes
  `optimum-cli export onnx --task image-to-text` z originálních vah - repo samo ONNX
  export neposkytuje.
- `manga_ocr_vocab.txt` - slovník tokenizeru (6144 řádků, index řádku = ID tokenu,
  `subword_tokenizer_type="character"` - čistý znakový lookup, žádné BPE slučování).
- Žádná modifikace vah, jen formát exportu.

Používá se v [com.haise.jiyu.translate.MangaOcrPipeline] jako hlavní zdroj rozpoznaného
textu pro japonštinu (nahrazuje ML Kit Japanese recognizer) - viz
[com.haise.jiyu.translate.OcrEngine.recognize]. ML Kit zůstává záložním zdrojem pro
jednotlivé bubliny, kde tenhle model selže nebo vyprší timeout.
```

- [ ] **Step 4: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/assets/models/manga_ocr_encoder.onnx app/src/main/assets/models/manga_ocr_decoder.onnx app/src/main/assets/models/manga_ocr_vocab.txt app/src/main/assets/models/NOTICE.md
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: pridat manga-ocr ONNX assety (encoder/decoder/vocab) + NOTICE"
```

---

### Task 2: `MangaOcrPostProcess` (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrPostProcess.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/translate/MangaOcrPostProcessTest.kt`

**Interfaces:**
- Consumes: nic (čistá funkce, žádná závislost na jiném tasku).
- Produces: `MangaOcrPostProcess.postProcess(text: String): String` — Task 6
  (`MangaOcrPipeline.recognizeCrop`) volá na výstup `tokenizer.decode(...)` před vrácením
  textu.

- [ ] **Step 1: Napsat padající testy**

```kotlin
package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrPostProcessTest {

    @Test
    fun `removes whitespace between characters`() {
        assertEquals("こんにちは", MangaOcrPostProcess.postProcess("こん にちは"))
    }

    @Test
    fun `replaces ellipsis character with three fullwidth dots`() {
        assertEquals("．．．", MangaOcrPostProcess.postProcess("…"))
    }

    @Test
    fun `collapses four dot run to four fullwidth dots`() {
        assertEquals("．．．．", MangaOcrPostProcess.postProcess("...."))
    }

    @Test
    fun `single dot is not collapsed but still converted to fullwidth`() {
        assertEquals("か．", MangaOcrPostProcess.postProcess("か."))
    }

    @Test
    fun `mixed dot and nakaguro run collapses to fullwidth dots`() {
        assertEquals("．．．", MangaOcrPostProcess.postProcess("・.・"))
    }

    @Test
    fun `ascii letters and digits convert to fullwidth`() {
        assertEquals("ＡＢＣ１２３", MangaOcrPostProcess.postProcess("ABC123"))
    }

    @Test
    fun `japanese text without ascii is unchanged`() {
        assertEquals("こんにちは", MangaOcrPostProcess.postProcess("こんにちは"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", MangaOcrPostProcess.postProcess(""))
    }
}
```

- [ ] **Step 2: Spustit testy, ověřit pád**

Run: `./gradlew testDebugUnitTest --tests "com.haise.jiyu.translate.MangaOcrPostProcessTest"`
Expected: FAIL (kompilace selže — `MangaOcrPostProcess` neexistuje).

- [ ] **Step 3: Implementovat**

Všechny testové případy ověřeny 6/6 proti referenčnímu Python `post_process()` (viz spec
sekce 3c a "Ověření na reálných datech") — přesná shoda formulí:

```kotlin
package com.haise.jiyu.translate

/**
 * Čisté (bez Androidu/ONNX) dorovnání textu z [MangaOcrPipeline] do stejného tvaru,
 * jaký produkuje referenční Python `manga_ocr.ocr.post_process` - beze změny by
 * dekódovaný text obsahoval mezery mezi znaky (tokenizer je znakový, ne slovní) a
 * půlšířkovou interpunkci/číslice místo japonsky obvyklé plnošířkové.
 */
internal object MangaOcrPostProcess {

    fun postProcess(text: String): String {
        val noWhitespace = text.filterNot { it.isWhitespace() }
        val noEllipsis = noWhitespace.replace("…", "...")
        val collapsedDots = DOT_RUN_REGEX.replace(noEllipsis) { match -> ".".repeat(match.value.length) }
        return collapsedDots.map { c -> if (c.code in 0x21..0x7E) (c.code + 0xFEE0).toChar() else c }.joinToString("")
    }

    /** `jaconv.h2z(..., ascii=True, digit=True)` na ASCII rozsahu je 1:1 offset +0xFEE0 (např. 'A'=0x41 -> U+FF21 'Ａ'). */
    private val DOT_RUN_REGEX = Regex("[・.]{2,}")
}
```

- [ ] **Step 4: Spustit testy, ověřit průchod**

Run: `./gradlew testDebugUnitTest --tests "com.haise.jiyu.translate.MangaOcrPostProcessTest"`
Expected: PASS (8/8).

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrPostProcess.kt app/src/test/kotlin/com/haise/jiyu/translate/MangaOcrPostProcessTest.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: pridat MangaOcrPostProcess (port Python post_process)"
```

---

### Task 3: `MangaOcrTokenizer` (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrTokenizer.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/translate/MangaOcrTokenizerTest.kt`

**Interfaces:**
- Consumes: nic (čistá třída, konstruktor bere prostý `List<String>`, ne přímo asset
  soubor — Task 6 načte `manga_ocr_vocab.txt` z Tasku 1 přes `context.assets` a předá
  jako `List<String>`).
- Produces: `MangaOcrTokenizer(vocab: List<String>)` s `decode(ids: List<Int>): String`,
  `bosId: Int`, `eosId: Int` — Task 4 (`greedyDecode`) bere `bosId`/`eosId` jako
  parametry, Task 6 volá `decode(...)` na výstup `greedyDecode`.

- [ ] **Step 1: Napsat padající testy**

```kotlin
package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrTokenizerTest {

    private val fixtureVocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "こ", "ん", "に", "ち", "は",
    )

    @Test
    fun `decode joins character tokens without separator`() {
        val tokenizer = MangaOcrTokenizer(fixtureVocab)
        assertEquals("こんにちは", tokenizer.decode(listOf(2, 5, 6, 7, 8, 9, 3)))
    }

    @Test
    fun `decode skips special tokens anywhere in the sequence`() {
        val tokenizer = MangaOcrTokenizer(fixtureVocab)
        assertEquals("こん", tokenizer.decode(listOf(2, 0, 5, 1, 6, 4, 3)))
    }

    @Test
    fun `decode of empty id list returns empty string`() {
        val tokenizer = MangaOcrTokenizer(fixtureVocab)
        assertEquals("", tokenizer.decode(emptyList()))
    }

    @Test
    fun `bosId is CLS and eosId is SEP`() {
        val tokenizer = MangaOcrTokenizer(fixtureVocab)
        assertEquals(2, tokenizer.bosId)
        assertEquals(3, tokenizer.eosId)
    }
}
```

- [ ] **Step 2: Spustit testy, ověřit pád**

Run: `./gradlew testDebugUnitTest --tests "com.haise.jiyu.translate.MangaOcrTokenizerTest"`
Expected: FAIL (kompilace selže — `MangaOcrTokenizer` neexistuje).

- [ ] **Step 3: Implementovat**

ID `[PAD]`=0, `[UNK]`=1, `[CLS]`=2, `[SEP]`=3, `[MASK]`=4 jsou fixní pozice v HuggingFace
BERT-styl vocabu (ověřeno v `C:\bml\manga_ocr_spike\FACTS.md`), `decode` s
`skip_special_tokens=True` chování je přesně tohle:

```kotlin
package com.haise.jiyu.translate

/**
 * Načte se z assets/models/manga_ocr_vocab.txt (jeden token na řádek, index = ID) - viz
 * [MangaOcrPipeline]. Model má malý (6144) BERT-styl slovník bez BPE slučování - dekódování
 * je prostý lookup + zřetězení, na rozdíl od subword tokenizerů u ostatních modelů v appce.
 */
internal class MangaOcrTokenizer(private val vocab: List<String>) {

    val bosId: Int = CLS_TOKEN_ID
    val eosId: Int = SEP_TOKEN_ID

    /** Odpovídá HuggingFace `tokenizer.decode(ids, skip_special_tokens=True)`. */
    fun decode(ids: List<Int>): String =
        ids.filterNot { it in SPECIAL_TOKEN_IDS }
            .mapNotNull { id -> vocab.getOrNull(id) }
            .joinToString("")

    private companion object {
        const val PAD_TOKEN_ID = 0
        const val UNK_TOKEN_ID = 1
        const val CLS_TOKEN_ID = 2
        const val SEP_TOKEN_ID = 3
        const val MASK_TOKEN_ID = 4
        val SPECIAL_TOKEN_IDS = setOf(PAD_TOKEN_ID, UNK_TOKEN_ID, CLS_TOKEN_ID, SEP_TOKEN_ID, MASK_TOKEN_ID)
    }
}
```

- [ ] **Step 4: Spustit testy, ověřit průchod**

Run: `./gradlew testDebugUnitTest --tests "com.haise.jiyu.translate.MangaOcrTokenizerTest"`
Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrTokenizer.kt app/src/test/kotlin/com/haise/jiyu/translate/MangaOcrTokenizerTest.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: pridat MangaOcrTokenizer (vocab lookup decode)"
```

---

### Task 4: `greedyDecode` (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrDecode.kt`
- Test: `app/src/test/kotlin/com/haise/jiyu/translate/MangaOcrDecodeTest.kt`

**Interfaces:**
- Consumes: nic přímo (čistá suspend funkce s injektovanou `nextToken` lambdou — stejný
  vzor jako `resolveAutoLanguage` v `OcrEngine.kt`).
- Produces: `internal const val MANGA_OCR_MAX_DECODE_TOKENS = 96`,
  `internal suspend fun greedyDecode(bosId: Int, eosId: Int, maxTokens: Int = MANGA_OCR_MAX_DECODE_TOKENS, nextToken: suspend (soFar: List<Int>) -> Int): List<Int>`
  — Task 6 zapojí `nextToken` na skutečnou `decoder_model.onnx` inferenci a výsledek
  předá do `MangaOcrTokenizer.decode(...)` (Task 3).

- [ ] **Step 1: Napsat padající testy**

```kotlin
package com.haise.jiyu.translate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrDecodeTest {

    @Test
    fun `stops early when nextToken returns eosId`() = runTest {
        val script = listOf(10, 11, 3) // 3 = eos
        var call = 0
        val result = greedyDecode(bosId = 2, eosId = 3, maxTokens = 96) { _ ->
            script[call++]
        }
        assertEquals(listOf(10, 11), result)
    }

    @Test
    fun `truncates at maxTokens when eosId never produced`() = runTest {
        val result = greedyDecode(bosId = 2, eosId = 3, maxTokens = 5) { _ -> 42 }
        assertEquals(listOf(42, 42, 42, 42, 42), result)
    }

    @Test
    fun `returns empty list when eosId is produced immediately`() = runTest {
        val result = greedyDecode(bosId = 2, eosId = 3, maxTokens = 96) { _ -> 3 }
        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun `nextToken receives full sequence so far including bos`() = runTest {
        val seen = mutableListOf<List<Int>>()
        greedyDecode(bosId = 2, eosId = 3, maxTokens = 3) { soFar ->
            seen += soFar
            if (soFar.size >= 3) 3 else 10 + soFar.size
        }
        assertEquals(listOf(2), seen[0])
        assertEquals(listOf(2, 11), seen[1])
        assertEquals(listOf(2, 11, 12), seen[2])
    }
}
```

- [ ] **Step 2: Spustit testy, ověřit pád**

Run: `./gradlew testDebugUnitTest --tests "com.haise.jiyu.translate.MangaOcrDecodeTest"`
Expected: FAIL (kompilace selže — `greedyDecode` neexistuje).

- [ ] **Step 3: Implementovat**

```kotlin
package com.haise.jiyu.translate

/**
 * Čisté (bez ONNX Runtime) řízení greedy autoregresivního dekódování - viz
 * [MangaOcrPipeline], které sem injektuje [nextToken] navázané na skutečnou inferenci
 * `manga_ocr_decoder.onnx`. Odděleno schválně, aby šlo otestovat JVM testem na
 * falešném [nextToken], bez nutnosti mít na stroji reálný model nebo Android - stejný
 * vzor jako [resolveAutoLanguage] v OcrEngine.kt.
 *
 * Model volaný bez KV-cache (viz spec "Mimo rozsah" - KV-cache pro tuhle architekturu
 * neproveditelné) - [nextToken] proto v produkci pokaždé posílá CELOU dosavadní `soFar`
 * sekvenci do dekodéru, ne jen poslední token.
 */
internal const val MANGA_OCR_MAX_DECODE_TOKENS = 96

/**
 * @param maxTokens bezpečnostní strop proti nekonečné smyčce - jedna bublina manga textu
 *   je pár slov, early-stop přes [eosId] je normální cesta.
 * @param nextToken (dosavadní ID tokeny, VČETNĚ [bosId] na začátku) -> ID dalšího tokenu.
 */
internal suspend fun greedyDecode(
    bosId: Int,
    eosId: Int,
    maxTokens: Int = MANGA_OCR_MAX_DECODE_TOKENS,
    nextToken: suspend (soFar: List<Int>) -> Int,
): List<Int> {
    val ids = mutableListOf(bosId)
    repeat(maxTokens) {
        val next = nextToken(ids.toList())
        if (next == eosId) return ids.drop(1)
        ids += next
    }
    return ids.drop(1)
}
```

- [ ] **Step 4: Spustit testy, ověřit průchod**

Run: `./gradlew testDebugUnitTest --tests "com.haise.jiyu.translate.MangaOcrDecodeTest"`
Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrDecode.kt app/src/test/kotlin/com/haise/jiyu/translate/MangaOcrDecodeTest.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: pridat greedyDecode (no-cache autoregresivni smycka)"
```

---

### Task 5: `MangaOcrPreprocessing`

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrPreprocessing.kt`

**Interfaces:**
- Consumes: nic (Android `Bitmap` vstup, žádná závislost na jiném tasku).
- Produces: `internal object MangaOcrPreprocessing { const val INPUT_SIZE = 224; fun toEncoderInput(crop: Bitmap): FloatBuffer }`
  — Task 6 (`MangaOcrPipeline.recognizeCrop`) volá `toEncoderInput(crop)` a výsledný
  buffer předá do `OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))`.

Žádný JVM test — potřebuje Android `Bitmap`/`Canvas`, stejný precedent jako
`YoloPreprocessing.kt` (taky bez JVM testu, ověřeno grepem `app/src/test`).

- [ ] **Step 1: Implementovat preprocessing**

Přesné pořadí kroků a formule ověřené na reálných datech proti referenčnímu Python modelu
(6/6 bajtová shoda, viz spec sekce 3b a "Ověření na reálných datech"). Grayscale formule
je stejná, jakou používá Pillow `Image.convert("L")`
(`L = (R*19595 + G*38470 + B*7471 + 0x8000) >> 16`), normalizace `(pixel/255 - 0.5) / 0.5`
odpovídá `image_mean=image_std=[0.5,0.5,0.5]` z `preprocessor_config.json`. Struktura
mirroruje existující `YoloPreprocessing.letterboxToFloatBuffer` (stejný projekt, stejný
Bitmap→FloatBuffer NCHW vzor, viz `app/src/main/kotlin/com/haise/jiyu/translate/YoloPreprocessing.kt`):

```kotlin
package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer

/**
 * Preprocessing vstupu pro `manga_ocr_encoder.onnx` - viz [MangaOcrPipeline]. Kromě
 * standardního resize+normalize (shodně s referenčním `preprocessor_config.json`
 * v C:/bml/manga_ocr_spike/onnx_export_nocache/) navíc dělá odbarvení do šedi a zpět do
 * RGB - to referenční `preprocessor_config.json` NEobsahuje, dělá ho přímo
 * `MangaOcr.__call__` v Pythonu před předáním do image processoru. Bez tohohle kroku
 * vychází výstup číselně jinak, než referenční model (ověřeno na reálných datech -
 * viz spec sekce "Ověření na reálných datech").
 */
internal object MangaOcrPreprocessing {

    const val INPUT_SIZE = 224

    fun toEncoderInput(crop: Bitmap): FloatBuffer {
        val grayscaled = toGrayscaleRgb(crop)
        try {
            val scaled = Bitmap.createScaledBitmap(grayscaled, INPUT_SIZE, INPUT_SIZE, true)
            try {
                val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
                scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

                val buffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
                for (i in pixels.indices) buffer.put(normalize((pixels[i] shr 16) and 0xFF))
                for (i in pixels.indices) buffer.put(normalize((pixels[i] shr 8) and 0xFF))
                for (i in pixels.indices) buffer.put(normalize(pixels[i] and 0xFF))
                buffer.rewind()
                return buffer
            } finally {
                if (scaled !== grayscaled) scaled.recycle()
            }
        } finally {
            if (grayscaled !== crop) grayscaled.recycle()
        }
    }

    /** `(pixel/255 - 0.5) / 0.5`, tj. `image_mean=image_std=0.5` z preprocessor_config.json. */
    private fun normalize(channel: Int): Float = (channel / 255f - 0.5f) / 0.5f

    /**
     * `img.convert("L").convert("RGB")` z Pythonu - PIL vzorec pro šedotón
     * (`L = (R*19595 + G*38470 + B*7471 + 0x8000) >> 16`), pak stejná hodnota do
     * všech tří kanálů zpátky.
     */
    private fun toGrayscaleRgb(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = ((r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16).coerceIn(0, 255)
            pixels[i] = Color.rgb(l, l, l)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
```

- [ ] **Step 2: Zkompilovat**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (žádný test — čistě ověření, že soubor sedí typově).

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrPreprocessing.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: pridat MangaOcrPreprocessing (grayscale round-trip + resize + normalize)"
```

---

### Task 6: `MangaOcrPipeline`

**Files:**
- Create: `app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrPipeline.kt`

**Interfaces:**
- Consumes: `MangaOcrPreprocessing.toEncoderInput` (Task 5), `MangaOcrTokenizer` (Task 3),
  `greedyDecode`/`MANGA_OCR_MAX_DECODE_TOKENS` (Task 4), `MangaOcrPostProcess.postProcess`
  (Task 2), `BubbleBoxDetector.detect(bitmap): List<DetectedBubbleBox>` (existující),
  `Throwable.report(context: String)` (existující, `com.haise.jiyu.util.ErrorReporter`).
- Produces: `@Singleton class MangaOcrPipeline @Inject constructor(@ApplicationContext context: Context, bubbleBoxDetector: BubbleBoxDetector)`
  s `suspend fun recognizeCrop(crop: Bitmap): String?` a
  `suspend fun detectAndRecognize(bitmap: Bitmap): List<RawTextBlock>`, plus top-level
  `internal fun cropBubbleBoxWithMargin(bitmap: Bitmap, box: DetectedBubbleBox, marginFraction: Float = 0.08f): Bitmap`
  — Task 7 (`OcrEngine`) volá `mangaOcrPipeline.recognizeCrop(...)` přímo (kvůli
  per-bublina timeoutu, viz spec sekce 5) a znovupoužívá `cropBubbleBoxWithMargin` pro
  vlastní bublina-po-bublině smyčku. Task 8/9 (on-device testy) konstruují
  `MangaOcrPipeline(context, bubbleBoxDetector)` přímo (žádné Hilt DI v `androidTest`).

- [ ] **Step 1: Implementovat**

Stejný "nikdy nevyhazuje, loguje přes `report()`" vzor jako `BubbleBoxDetector`/
`BubbleMaskSegmenter`. Pojmenované ONNX vstupy/výstupy (`ENCODER_INPUT_NAME` atd.) jsou
přesné tenzor names ověřené v `C:\bml\manga_ocr_spike\FACTS.md` — jediný output každého
modelu, ale jmenný přístup (stejný vzor jako `BubbleMaskSegmenter`'s
`result.get(NAME).orElse(null)?.value`) je nutný pro dekodér, který má DVA pojmenované
vstupy (`input_ids`, `encoder_hidden_states`), na rozdíl od `BubbleBoxDetector`'s
jednoho vstupu (`session.inputNames.first()`):

```kotlin
package com.haise.jiyu.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.haise.jiyu.util.report
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ořízne bitmapu podle detekovaného boxu bubliny s malým okrajem (aby se do výřezu vešel
 * i tenký okraj bubliny kolem textu) - viz [MangaOcrPipeline.detectAndRecognize] a
 * [OcrEngine], které obě potřebují stejné oříznutí (jednou uvnitř téhle třídy pro
 * bez-fallbacku happy path, jednou v OcrEngine pro per-bublina timeout+ML Kit fallback).
 */
internal fun cropBubbleBoxWithMargin(bitmap: Bitmap, box: DetectedBubbleBox, marginFraction: Float = 0.08f): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val boxW = (box.rightF - box.leftF) * w
    val boxH = (box.bottomF - box.topF) * h
    val marginX = boxW * marginFraction
    val marginY = boxH * marginFraction
    val left = ((box.leftF * w) - marginX).toInt().coerceIn(0, w - 1)
    val top = ((box.topF * h) - marginY).toInt().coerceIn(0, h - 1)
    val right = ((box.rightF * w) + marginX).toInt().coerceIn(left + 1, w)
    val bottom = ((box.bottomF * h) + marginY).toInt().coerceIn(top + 1, h)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

/**
 * Tenký ONNX Runtime obal kolem `manga_ocr_encoder.onnx` + `manga_ocr_decoder.onnx` - viz
 * assets/models/NOTICE.md. Stejný vzor jako [BubbleBoxDetector]/[BubbleMaskSegmenter]:
 * nikdy nevyhazuje, selhání se loguje přes [report] a appka spadne na ML Kit fallback
 * (viz [OcrEngine], které tenhle fallback zajišťuje - tahle třída o ML Kitu vůbec neví).
 */
@Singleton
class MangaOcrPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bubbleBoxDetector: BubbleBoxDetector,
) {
    private val encoderSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(ENCODER_ASSET_PATH).use { it.readBytes() }
        env.createSession(modelBytes, OrtSession.SessionOptions())
    }
    private val decoderSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(DECODER_ASSET_PATH).use { it.readBytes() }
        env.createSession(modelBytes, OrtSession.SessionOptions())
    }
    private val tokenizer: MangaOcrTokenizer by lazy {
        val lines = context.assets.open(VOCAB_ASSET_PATH).bufferedReader().use { it.readLines() }
        MangaOcrTokenizer(lines)
    }

    /**
     * Najde bubliny na celé stránce ([BubbleBoxDetector], znovupoužitý Apache-2.0 model -
     * žádný nový box model netřeba) a pro každou přečte text přes manga-ocr. Bubliny, kde
     * [recognizeCrop] vrátí `null`/prázdný text, se v seznamu vůbec neobjeví - ML Kit
     * fallback pro ně zajišťuje [OcrEngine], ne tahle metoda (viz její vlastní smyčka).
     */
    suspend fun detectAndRecognize(bitmap: Bitmap): List<RawTextBlock> {
        val boxes = bubbleBoxDetector.detect(bitmap)
        return boxes.mapNotNull { box ->
            val crop = cropBubbleBoxWithMargin(bitmap, box)
            try {
                val text = recognizeCrop(crop)
                if (text.isNullOrBlank()) {
                    null
                } else {
                    RawTextBlock(text = text, leftF = box.leftF, topF = box.topF, rightF = box.rightF, bottomF = box.bottomF)
                }
            } finally {
                crop.recycle()
            }
        }
    }

    /** Přečte JEDNU už oříznutou bublinu - viz per-bublina timeout v [OcrEngine]. */
    suspend fun recognizeCrop(crop: Bitmap): String? = withContext(Dispatchers.Default) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val inputBuffer = MangaOcrPreprocessing.toEncoderInput(crop)
            val encoderHiddenStates = OnnxTensor.createTensor(
                env, inputBuffer, longArrayOf(1, 3, MangaOcrPreprocessing.INPUT_SIZE.toLong(), MangaOcrPreprocessing.INPUT_SIZE.toLong()),
            ).use { pixelValues ->
                encoderSession.run(mapOf(ENCODER_INPUT_NAME to pixelValues)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result.get(ENCODER_OUTPUT_NAME).orElse(null)?.value
                        ?: return@withContext null) as Array<Array<FloatArray>>
                }
            }

            val encoderSeqLen = encoderHiddenStates[0].size
            val hiddenSize = encoderHiddenStates[0][0].size
            val encoderBuffer = FloatBuffer.allocate(encoderSeqLen * hiddenSize)
            for (token in encoderHiddenStates[0]) for (v in token) encoderBuffer.put(v)
            encoderBuffer.rewind()

            val decodedIds = OnnxTensor.createTensor(env, encoderBuffer, longArrayOf(1, encoderSeqLen.toLong(), hiddenSize.toLong())).use { hiddenTensor ->
                greedyDecode(bosId = tokenizer.bosId, eosId = tokenizer.eosId) { soFar ->
                    val idsBuffer = LongBuffer.allocate(soFar.size)
                    soFar.forEach { idsBuffer.put(it.toLong()) }
                    idsBuffer.rewind()
                    OnnxTensor.createTensor(env, idsBuffer, longArrayOf(1, soFar.size.toLong())).use { idsTensor ->
                        decoderSession.run(mapOf(DECODER_INPUT_IDS_NAME to idsTensor, DECODER_ENCODER_STATES_NAME to hiddenTensor)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = result.get(DECODER_OUTPUT_NAME).orElse(null)?.value as? Array<Array<FloatArray>>
                            if (logits == null) {
                                tokenizer.eosId
                            } else {
                                val lastStepLogits = logits[0].last()
                                var bestId = 0
                                var bestScore = Float.NEGATIVE_INFINITY
                                for (id in lastStepLogits.indices) {
                                    if (lastStepLogits[id] > bestScore) {
                                        bestScore = lastStepLogits[id]
                                        bestId = id
                                    }
                                }
                                bestId
                            }
                        }
                    }
                }
            }

            MangaOcrPostProcess.postProcess(tokenizer.decode(decodedIds))
        } catch (e: CancellationException) {
            // Zrušení uživatelem nebo systémem není chyba - hlásit by se nemělo.
            throw e
        } catch (e: Exception) {
            e.report("translate:mangaOcrPipeline:recognizeCrop")
            null
        }
    }

    private companion object {
        const val ENCODER_ASSET_PATH = "models/manga_ocr_encoder.onnx"
        const val DECODER_ASSET_PATH = "models/manga_ocr_decoder.onnx"
        const val VOCAB_ASSET_PATH = "models/manga_ocr_vocab.txt"
        const val ENCODER_INPUT_NAME = "pixel_values"
        const val ENCODER_OUTPUT_NAME = "last_hidden_state"
        const val DECODER_INPUT_IDS_NAME = "input_ids"
        const val DECODER_ENCODER_STATES_NAME = "encoder_hidden_states"
        const val DECODER_OUTPUT_NAME = "logits"
    }
}
```

- [ ] **Step 2: Zkompilovat**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/kotlin/com/haise/jiyu/translate/MangaOcrPipeline.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: pridat MangaOcrPipeline (ONNX encoder+decoder obal)"
```

---

### Task 7: Zapojení do `OcrEngine` + oprava 7 androidTest call sites

**Files:**
- Modify: `app/src/main/kotlin/com/haise/jiyu/translate/OcrEngine.kt` (import blok
  řádky 3-17, konstruktor řádky 219-222, `recognize()` řádky 236-272, nové privátní
  metody + konstanta)
- Modify: `app/src/androidTest/kotlin/com/haise/jiyu/translate/CascadingBubbleOnDeviceTest.kt:77`
- Modify: `app/src/androidTest/kotlin/com/haise/jiyu/translate/CascadeOnLightBackgroundTest.kt:88`
- Modify: `app/src/androidTest/kotlin/com/haise/jiyu/translate/NativeFontSizeOnDeviceTest.kt:48`
- Modify: `app/src/androidTest/kotlin/com/haise/jiyu/translate/LineSpacingMergeProbeTest.kt:62,108,164`
- Modify: `app/src/androidTest/kotlin/com/haise/jiyu/translate/PunctuationBlockProbeTest.kt:90`

**Interfaces:**
- Consumes: `MangaOcrPipeline.recognizeCrop` (Task 6), `cropBubbleBoxWithMargin` (Task 6),
  `BubbleBoxDetector.detect` (existující), `RawTextBlock` (existující).
- Produces: `OcrEngine(maskSegmenter: BubbleMaskSegmenter, bubbleBoxDetector: BubbleBoxDetector, mangaOcrPipeline: MangaOcrPipeline)`
  — Hilt DI to zapojí automaticky (`@Inject constructor`, oba nové typy jsou `@Singleton`
  s `@Inject constructor` bez potřeby nového `@Module` bindingu). Task 8/9 (on-device
  testy) konstruují tenhle trojargumentový konstruktor přímo.

- [ ] **Step 1: Přidat importy**

V `app/src/main/kotlin/com/haise/jiyu/translate/OcrEngine.kt`, do importového bloku
(řádky 3-17):

```kotlin
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
```

- [ ] **Step 2: Přidat konstantu timeoutu**

Za `internal const val AUTO_CONFIDENT_CHARS = 20` (řádek 159), před `resolveAutoLanguage`:

```kotlin
/**
 * Bezpečná horní hranice jedné manga-ocr inference na bublinu (viz spec sekce 5 - reálně
 * změřeno ~1-1.6s/bublinu i s 10x mobilní ARM penaltou, velká rezerva). Timeout/výjimka
 * padá na ML Kit fallback jen pro TUHLE bublinu, ne pro celou stránku -
 * TranslateRepository.PAGE_OCR_TIMEOUT_MILLIS (40s) zůstává vnější pojistkou beze změny.
 */
internal const val MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS = 8000L
```

- [ ] **Step 3: Rozšířit konstruktor**

Nahradit (řádky 219-222):

```kotlin
@Singleton
class OcrEngine @Inject constructor(
    private val maskSegmenter: BubbleMaskSegmenter,
) {
```

za:

```kotlin
@Singleton
class OcrEngine @Inject constructor(
    private val maskSegmenter: BubbleMaskSegmenter,
    private val bubbleBoxDetector: BubbleBoxDetector,
    private val mangaOcrPipeline: MangaOcrPipeline,
) {
```

- [ ] **Step 4: Přidat japonskou větev do `recognize()`**

Nahradit blok od `val pixelSource = BitmapPixelSource(bitmap)` po konec `val merged = ...`
volání (řádky 252-272):

```kotlin
        // Sampling barvy pozadí i detekce tvaru bubliny potřebují ještě živou bitmapu,
        // proto běží tady a ne až v TranslateRepository, kam se bitmapa vůbec nedostane
        // (jen relativní souřadnice).
        val pixelSource = BitmapPixelSource(bitmap)
        // Pořadí, ve kterém tenhle seznam skončí, je i pořadí, ve kterém bubliny uvidí
        // překladový model (viz GeminiUltraPrompt.buildUserPrompt) - bez řazení do
        // skutečného pořadí čtení dostával model repliky v podstatě náhodně (podle
        // union-find indexu z mergeNearbyLines), což kazilo návaznost dialogu.
        //
        // noWallBetween: čistě geometrická blízkost (shouldMerge) nestačí - dvě RŮZNÉ
        // bubliny/captions vedle sebe můžou geometrii splňovat, ale mezi nimi je vždycky
        // vizuální hranice (obrys, jiná barva boxu). Bez týhle kontroly se sloučily do
        // jednoho bloku: jedna bublina zmizela beze zbytku (viz uživatelská zpětná vazba),
        // druhá na stránce s reklamou vytvořila jednu přebujelou barevnou plochu.
        //
        // Japonština má od téhle chvíle úplně jinou cestu (viz spec sekce 4): manga-ocr
        // čte VÝŘEZ CELÉ bubliny najednou, ne řádek po řádku, takže `lines` (ML-Kit trial,
        // co jen ROZHODL, že stránka je japonská) se tu zahazuje a mergeNearbyLines se pro
        // ni vůbec nevolá - shouldMerge je pravidlo pro řádkové OCR, ne pro celobublinové.
        val merged = if (resolvedLanguage == "Japanese") {
            sortIntoReadingOrder(recognizeJapaneseWithMangaOcr(bitmap), rightToLeft = true)
        } else {
            sortIntoReadingOrder(
                mergeNearbyLines(lines) { a, b -> !hasWallBetween(pixelSource, bitmap.width, bitmap.height, a, b) },
                // Rozhoduje ROZPOZNANÝ jazyk, ne ten nastavený - pod "Auto" byl nastavený jazyk
                // doslova "Auto", takže japonská stránka dostala pořadí zleva doprava a model
                // četl repliky pozpátku.
                rightToLeft = isRightToLeftScript(resolvedLanguage),
            )
        }
```

- [ ] **Step 5: Přidat privátní metody pro manga-ocr větev + ML Kit fallback**

Za metodu `recognizeLines` (za řádek 381, před `detectShapesOnly`):

```kotlin
    /**
     * Japonská větev [recognize] - viz spec sekce 4. Na rozdíl od ML Kit cesty čte VÝŘEZ
     * CELÉ bubliny najednou ([MangaOcrPipeline]), ne řádek po řádku, takže tady netřeba
     * [mergeNearbyLines].
     *
     * Selhání/timeout jednotlivé bubliny padá na ML Kit ([recognizeCropWithMlKit]) - appka
     * nikdy nesmí zůstat bez textu jen kvůli tomuhle modelu (spec "Cíl").
     */
    private suspend fun recognizeJapaneseWithMangaOcr(bitmap: Bitmap): List<RawTextBlock> {
        val boxes = bubbleBoxDetector.detect(bitmap)
        return boxes.mapNotNull { box ->
            val crop = cropBubbleBoxWithMargin(bitmap, box)
            try {
                val mangaOcrText = withTimeoutOrNull(MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS) {
                    mangaOcrPipeline.recognizeCrop(crop)
                }
                val text = if (!mangaOcrText.isNullOrBlank()) mangaOcrText else recognizeCropWithMlKit(crop)
                if (text.isNullOrBlank()) {
                    null
                } else {
                    RawTextBlock(text = text, leftF = box.leftF, topF = box.topF, rightF = box.rightF, bottomF = box.bottomF)
                }
            } finally {
                crop.recycle()
            }
        }
    }

    /**
     * ML Kit záloha pro jednu už oříznutou bublinu - viz spec sekce 4/5. Stejný recognizer,
     * co [recognizeLines] pro "Japanese", jen na oříznutém výřezu misto celé stránky a se
     * spojeným výsledkem do jednoho stringu (nepotřebujeme řádkovou geometrii, jen text).
     */
    private suspend fun recognizeCropWithMlKit(crop: Bitmap): String? {
        val image = InputImage.fromBitmap(crop, 0)
        val result = try {
            suspendCancellableCoroutine { cont ->
                japaneseRecognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("translate:ocrEngine:mangaOcrMlKitFallback")
            return null
        }
        return result.textBlocks.flatMap { it.lines }.joinToString(" ") { it.text }.ifBlank { null }
    }

```

- [ ] **Step 6: Opravit 7 volání konstruktoru v androidTest**

V každém z těchto 7 míst nahradit `OcrEngine(BubbleMaskSegmenter(context))` za
`OcrEngine(BubbleMaskSegmenter(context), BubbleBoxDetector(context), MangaOcrPipeline(context, BubbleBoxDetector(context)))`
(žádná z těchto 7 testů nepoužívá `"Japanese"` jazyk, takže se `MangaOcrPipeline`/
`BubbleBoxDetector` nikdy skutečně nespustí - jen musí sedět typy konstruktoru):

- `app/src/androidTest/kotlin/com/haise/jiyu/translate/CascadingBubbleOnDeviceTest.kt:77`
- `app/src/androidTest/kotlin/com/haise/jiyu/translate/CascadeOnLightBackgroundTest.kt:88`
- `app/src/androidTest/kotlin/com/haise/jiyu/translate/NativeFontSizeOnDeviceTest.kt:48`
- `app/src/androidTest/kotlin/com/haise/jiyu/translate/LineSpacingMergeProbeTest.kt:62`
- `app/src/androidTest/kotlin/com/haise/jiyu/translate/LineSpacingMergeProbeTest.kt:108`
- `app/src/androidTest/kotlin/com/haise/jiyu/translate/LineSpacingMergeProbeTest.kt:164`
- `app/src/androidTest/kotlin/com/haise/jiyu/translate/PunctuationBlockProbeTest.kt:90`

Příklad (`CascadingBubbleOnDeviceTest.kt:77`), stejný vzor pro zbylých 6:

```kotlin
        val blocks = OcrEngine(BubbleMaskSegmenter(context), BubbleBoxDetector(context), MangaOcrPipeline(context, BubbleBoxDetector(context))).recognize(bitmap, "English")
```

- [ ] **Step 7: Zkompilovat a spustit JVM testy**

Run: `./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, všechny existující JVM testy (`AutoLanguageDetectionTest` atd.)
pořád projdou beze změny.

- [ ] **Step 8: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/main/kotlin/com/haise/jiyu/translate/OcrEngine.kt app/src/androidTest/kotlin/com/haise/jiyu/translate/CascadingBubbleOnDeviceTest.kt app/src/androidTest/kotlin/com/haise/jiyu/translate/CascadeOnLightBackgroundTest.kt app/src/androidTest/kotlin/com/haise/jiyu/translate/NativeFontSizeOnDeviceTest.kt app/src/androidTest/kotlin/com/haise/jiyu/translate/LineSpacingMergeProbeTest.kt app/src/androidTest/kotlin/com/haise/jiyu/translate/PunctuationBlockProbeTest.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "feat: zapojit MangaOcrPipeline do OcrEngine.recognize() pro japonstinu"
```

---

### Task 8: `MangaOcrPipelineOnDeviceTest` (on-device probe)

**Files:**
- Create: `app/src/androidTest/kotlin/com/haise/jiyu/translate/MangaOcrPipelineOnDeviceTest.kt`

**Interfaces:**
- Consumes: `MangaOcrPipeline` (Task 6), `BubbleBoxDetector` (existující).
- Produces: nic (list test, nekonzumuje ho žádný další task).

`MangaOcrPipeline` sama zůstává netestovaná JVM testem (stejně jako `BubbleBoxDetector`/
`BubbleMaskSegmenter`, viz spec "Testování") - potřebuje Android `Bitmap`/`OrtSession`.
Synteticky vykreslený japonský text (ne reálný manga screenshot - appka nebundluje
copyrightovaný obsah, viz precedent `VerticalJapaneseOnDeviceTest`/`CascadingBubbleOnDeviceTest`).

- [ ] **Step 1: Napsat probe test**

```kotlin
package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SONDA k [MangaOcrPipeline] na reálném ONNX modelu (žádný unit test ho nemá - potřebuje
 * Android Bitmap/OrtSession, viz spec sekce "Testování"). Kontroluje, že celá pipeline
 * (preprocessing -> encoder -> greedy decode -> tokenizer -> post_process) doběhne na
 * reálném zařízení a vrátí neprázdný text - přesnost na synteticky vykresleném textu (ne
 * reálném manga fontu, na kterém je model trénovaný) se schválně nevynucuje přesnou shodou,
 * jen se loguje pro ruční kontrolu.
 *
 * Výsledky jdou do logcatu pod značkou "MangaOcrProbe".
 */
@RunWith(AndroidJUnit4::class)
class MangaOcrPipelineOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bubbleBoxDetector by lazy { BubbleBoxDetector(context) }
    private val pipeline by lazy { MangaOcrPipeline(context, bubbleBoxDetector) }

    /** Prostý bílý výřez s vodorovně vysázeným japonským textem - vstup pro recognizeCrop. */
    private fun japaneseCrop(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText(text, 20f, 70f, paint)
        return bmp
    }

    /** Bublina (bílá elipsa s černým obrysem) s japonským textem uvnitř - vstup pro detectAndRecognize. */
    private fun pageWithJapaneseBubble(): Bitmap {
        val w = 900
        val h = 1200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(18, 18, 24))
        val fill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val stroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        canvas.drawOval(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.40f, fill)
        canvas.drawOval(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.40f, stroke)
        val text = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText("こんにちは", w * 0.30f, h * 0.30f, text)
        return bmp
    }

    @Test
    fun recognizeCrop_returnsNonNullTextForSyntheticJapanese() = runBlocking {
        val crop = japaneseCrop("こんにちは")
        val text = pipeline.recognizeCrop(crop)
        Log.i("MangaOcrProbe", "recognizeCrop vratil: \"$text\"")
        assertNotNull("manga-ocr pipeline se nesmi na zarizeni zhroutit / vratit null (asset/tenzor chyba)", text)
    }

    @Test
    fun detectAndRecognize_findsBubbleAndReturnsText() = runBlocking {
        val bitmap = pageWithJapaneseBubble()
        val blocks = pipeline.detectAndRecognize(bitmap)
        Log.i("MangaOcrProbe", "detectAndRecognize nalezl bloku: ${blocks.size}")
        blocks.forEach { b -> Log.i("MangaOcrProbe", "  box=%.2f,%.2f..%.2f,%.2f text=\"%s\"".format(b.leftF, b.topF, b.rightF, b.bottomF, b.text)) }
        // Pozn.: pokud bublinovy YOLO detektor na téhle synteticke elipse nic nenajde
        // (trenovany na realnych manga bublinach, ne programove kreslenych ovalech), je
        // potreba tenhle assert po prvnim behu na zarizeni prehodnotit - viz Task 10.
        assertTrue("bublinovy detektor musi na syntetické bublině najit aspon jeden box", blocks.isNotEmpty())
    }
}
```

- [ ] **Step 2: Zkompilovat**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL. (Skutečné spuštění na zařízení/emulátoru je Task 10.)

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/androidTest/kotlin/com/haise/jiyu/translate/MangaOcrPipelineOnDeviceTest.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "test: pridat on-device sondu pro MangaOcrPipeline"
```

---

### Task 9: `MangaOcrFallbackOnDeviceTest` (end-to-end ML Kit fallback)

**Files:**
- Create: `app/src/androidTest/kotlin/com/haise/jiyu/translate/MangaOcrFallbackOnDeviceTest.kt`

**Interfaces:**
- Consumes: `OcrEngine` (Task 7), `MangaOcrPipeline`/`BubbleBoxDetector` (Task 6/existující).
- Produces: nic.

Ověřuje spec požadavek "appka nikdy nesmí zůstat bez textu jen kvůli tomuhle modelu" na
reálném zařízení tak, že `MangaOcrPipeline` se donutí selhat DETERMINISTICKY: zkonstruuje
se s `InstrumentationRegistry.getInstrumentation().context` (kontext TEST APK) místo
`targetContext` (kontext appky) - test APK nemá `assets/models/*.onnx` zabundlované, takže
`context.assets.open(...)` uvnitř `MangaOcrPipeline` vždycky selže na chybějícím souboru a
`recognizeCrop` vrátí `null` (viz Task 6 - nikdy nevyhazuje). `BubbleBoxDetector` pro
detekci boxu zůstává na reálném `targetContext`, aby bublina byla vůbec nalezena.

- [ ] **Step 1: Napsat end-to-end test**

```kotlin
package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end sonda [OcrEngine.recognize] pro japonštinu - viz spec sekce 4/5. Ověřuje dvě
 * věci na reálném zařízení (žádný unit test tohle nepokryje - potřebuje ML Kit i ONNX):
 *
 *  1. Šťastná cesta: manga-ocr běží normálně, vrátí nějaký text.
 *  2. Záložní cesta: [MangaOcrPipeline] uměle donucený selhat (zkonstruovaný s
 *     INSTRUMENTATION kontextem misto cilove appky - ten nema assets/models/*.onnx
 *     zabundlovane, takze kazde recognizeCrop uvnitr selze na chybejicim assetu a vrati
 *     null) - appka i tak musí vrátit text, protože OcrEngine pro tenhle pripad spadne
 *     na ML Kit.
 *
 * Výsledky jdou do logcatu pod značkou "MangaOcrFallbackProbe".
 */
@RunWith(AndroidJUnit4::class)
class MangaOcrFallbackOnDeviceTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentationContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun pageWithJapaneseBubble(): Bitmap {
        val w = 900
        val h = 1200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(18, 18, 24))
        val fill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val stroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        canvas.drawOval(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.40f, fill)
        canvas.drawOval(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.40f, stroke)
        val text = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText("こんにちは", w * 0.30f, h * 0.30f, text)
        return bmp
    }

    @Test
    fun recognize_happyPath_returnsText() = runBlocking {
        val bubbleBoxDetector = BubbleBoxDetector(targetContext)
        val engine = OcrEngine(
            BubbleMaskSegmenter(targetContext),
            bubbleBoxDetector,
            MangaOcrPipeline(targetContext, bubbleBoxDetector),
        )
        val blocks = engine.recognize(pageWithJapaneseBubble(), "Japanese")
        Log.i("MangaOcrFallbackProbe", "happy path nalezl bloku: ${blocks.size}")
        blocks.forEach { Log.i("MangaOcrFallbackProbe", "  text=\"${it.text}\"") }
        assertTrue("OcrEngine musi na japonske bublině neco najit", blocks.isNotEmpty())
    }

    @Test
    fun recognize_fallsBackToMlKit_whenMangaOcrPipelineCannotLoadModel() = runBlocking {
        val bubbleBoxDetector = BubbleBoxDetector(targetContext)
        // MangaOcrPipeline zkonstruovany s kontextem TEST APK (ne cilove appky) - ten nema
        // assets/models/*.onnx zabundlovane, takze kazde recognizeCrop uvnitr selze na
        // chybejicim souboru a vrati null (viz MangaOcrPipeline - nikdy nevyhazuje).
        val brokenPipeline = MangaOcrPipeline(instrumentationContext, bubbleBoxDetector)
        val engine = OcrEngine(BubbleMaskSegmenter(targetContext), bubbleBoxDetector, brokenPipeline)

        val blocks = engine.recognize(pageWithJapaneseBubble(), "Japanese")
        Log.i("MangaOcrFallbackProbe", "fallback path nalezl bloku: ${blocks.size}")
        blocks.forEach { Log.i("MangaOcrFallbackProbe", "  text=\"${it.text}\"") }

        assertTrue(
            "kdyz manga-ocr nejde nacist, OcrEngine musi spadnout na ML Kit a pořád neco vratit",
            blocks.isNotEmpty(),
        )
    }
}
```

- [ ] **Step 2: Zkompilovat**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" add app/src/androidTest/kotlin/com/haise/jiyu/translate/MangaOcrFallbackOnDeviceTest.kt
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "test: pridat end-to-end sondu OcrEngine happy path + ML Kit fallback"
```

---

### Task 10: Ověření na zařízení a manuální kontrola

**Files:** žádné (jen spuštění existujících testů + manuální kontrola v appce).

**Interfaces:** žádné — poslední task, nic dalšího na něj nenavazuje.

- [ ] **Step 1: Spustit celou JVM test sadu**

```bash
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, všech 156+ existujících testů + 16 nových (8 `MangaOcrPostProcess`
+ 4 `MangaOcrTokenizer` + 4 `MangaOcrDecode`) projde.

- [ ] **Step 2: Spustit on-device testy na připojeném zařízení/emulátoru**

```bash
./gradlew connectedDebugAndroidTest --tests "com.haise.jiyu.translate.MangaOcrPipelineOnDeviceTest" --tests "com.haise.jiyu.translate.MangaOcrFallbackOnDeviceTest"
```

Zkontrolovat logcat (`adb logcat -s MangaOcrProbe MangaOcrFallbackProbe`) - hlavně:
- `recognizeCrop` na syntetickém textu vrátila NĚJAKÝ text (přesnost na syntetickém fontu
  se nevynucuje, jen kontrola, že pipeline neskončí `null`/pádem).
- `detectAndRecognize`/happy-path test našly aspoň jeden box. Pokud YOLO bublinový
  detektor na programově kreslené elipse nic nenajde (reálné riziko - model je trénovaný
  na skutečných manga bublinách), uprav `pageWithJapaneseBubble()` v Task 8/9 (např.
  silnější/nepravidelnější obrys, nebo threshold `bubbleBoxDetector.detect(bitmap, confThreshold = ...)`
  nižší jen pro test) a re-commituj — nejde o změnu produkčního kódu, jen o testovací fixture.
- Fallback test skutečně prošel PŘES `recognizeCropWithMlKit` (ML Kit) - pokud i `blocks.isEmpty()`
  vyjde, zkontroluj, že `instrumentationContext.assets.open("models/manga_ocr_encoder.onnx")`
  fakt hází (test APK by neměl mít `assets/models/` vůbec).

- [ ] **Step 3: Manuální test v appce**

Nainstalovat debug build na zařízení/emulátor, otevřít reálnou japonskou manga kapitolu
(zdroj s japonským obsahem, např. ComicK s japonským jazykem), spustit AI překlad se
zdrojovým jazykem "Japanese" nebo "Auto", a vizuálně zkontrolovat:
- OCR text dává smysl (srovnat s originálem, pokud čitelný) - hlavně u stylizovaného/ručně
  psaného letteringu, kde měl ML Kit historicky problémy (viz spec "Problém").
- Appka nikdy nezůstane bez přeloženého textu v bublině (fallback funguje i na reálných datech).
- Žádný znatelný nárůst čekací doby na stránku nad rámec předchozího chování (per-bublina
  timeout 8s je velkorysá rezerva, ne očekávaná běžná latence).

- [ ] **Step 4: Finální commit (pokud Step 2/3 vyžádaly úpravu test fixture)**

```bash
git -C "C:/Users/ilekr/Desktop/jiyu" status
git -C "C:/Users/ilekr/Desktop/jiyu" add -A
git -C "C:/Users/ilekr/Desktop/jiyu" commit -m "fix: doladit on-device test fixtures podle vysledku behu na zarizeni"
```

(Přeskoč, pokud Step 2/3 nevyžádaly žádnou změnu.)

---

## Self-Review (proveden autorem plánu)

**Pokrytí specu:**
- Sekce 1 (`MangaOcrPipeline`) → Task 6.
- Sekce 2 (`MangaOcrTokenizer`) → Task 3.
- Sekce 3 (`greedyDecode`) → Task 4.
- Sekce 3b (preprocessing) → Task 5.
- Sekce 3c (`MangaOcrPostProcess`) → Task 2.
- Sekce 4 (zapojení do `OcrEngine.recognize()`, ML Kit fallback funkce, `RawTextBlock`
  defaulty) → Task 7.
- Sekce 5 (timeout) → Task 7 Step 2 (konstanta) + Step 5 (`withTimeoutOrNull` na
  `mangaOcrPipeline.recognizeCrop`).
- Sekce 6 (assety, `NOTICE.md`) → Task 1.
- "Ověření na reálných datech" (číselné konstanty, formule) → přeneseno doslovně do
  Task 2 (post-process regex/h2z), Task 5 (grayscale/normalize formule), Task 6 (tenzor
  names/shapes).
- "Testování" sekce → JVM čisté testy Task 2/3/4, on-device sondy Task 8/9, existující
  testy netknuty (Global Constraints + Task 7 Step 7 to ověřuje spuštěním).
- "Mimo rozsah" (beam search, KV-cache, jazyková detekce v pipeline) → žádný task je
  neimplementuje, explicitně zmíněno v Global Constraints.

**Placeholder scan:** Žádný `TBD`/`TODO`/"implementovat později" v žádném tasku. Každý
kódový krok má kompletní, ne naznačený kód. Jediné místo s vědomou nejistotou je Task 8/9
Step (assert na `blocks.isNotEmpty()` u syntetické bubliny) - to NENÍ placeholder, je to
explicitně popsaný risk s konkrétním postupem nápravy v Task 10 Step 2, ne "add error
handling" mlhavina.

**Typová konzistence:**
- `DetectedBubbleBox` (leftF/topF/rightF/bottomF/classId/score) použito shodně v
  `cropBubbleBoxWithMargin` (Task 6) i `recognizeJapaneseWithMangaOcr` (Task 7) -
  žádné pole navíc, žádné přejmenování.
- `MangaOcrTokenizer(vocab: List<String>)` (Task 3) odpovídá volání
  `MangaOcrTokenizer(lines)` v Tasku 6, kde `lines: List<String>` z `bufferedReader().readLines()`.
- `greedyDecode(bosId, eosId, maxTokens, nextToken)` (Task 4) odpovídá volání v Tasku 6 -
  `bosId = tokenizer.bosId`, `eosId = tokenizer.eosId`, `maxTokens` nedoplněno (bere
  default `MANGA_OCR_MAX_DECODE_TOKENS = 96`), `nextToken` lambda vrací `Int` (ID
  dalšího tokenu) shodně s deklarovaným typem.
- `MangaOcrPreprocessing.toEncoderInput(crop: Bitmap): FloatBuffer` (Task 5) odpovídá
  použití v Tasku 6 (`OnnxTensor.createTensor(env, inputBuffer, ...)` bere `FloatBuffer`).
- `MangaOcrPipeline(context: Context, bubbleBoxDetector: BubbleBoxDetector)` (Task 6)
  konstruktor shodný ve všech voláních: Hilt DI (Task 7 přes `@Inject`), 7 androidTest
  call sites (Task 7 Step 6), Task 8/9 (`MangaOcrPipeline(context, bubbleBoxDetector)`).
- `OcrEngine(maskSegmenter: BubbleMaskSegmenter, bubbleBoxDetector: BubbleBoxDetector, mangaOcrPipeline: MangaOcrPipeline)`
  (Task 7) - stejné pořadí/typy parametrů použito v Task 7 Step 6 (7 androidTest sites)
  i Task 9 (`OcrEngine(BubbleMaskSegmenter(targetContext), bubbleBoxDetector, ...)`).
- `MangaOcrPostProcess.postProcess(text: String): String` (Task 2) - volání v Tasku 6
  `MangaOcrPostProcess.postProcess(tokenizer.decode(decodedIds))` sedí (`decode` vrací
  `String`, `postProcess` bere `String`).
- `cropBubbleBoxWithMargin(bitmap: Bitmap, box: DetectedBubbleBox, marginFraction: Float = 0.08f): Bitmap`
  definováno jednou v Tasku 6 (top-level `internal fun`, package `com.haise.jiyu.translate`),
  znovupoužito beze změny signatury v Tasku 7 (`OcrEngine.kt`, stejný package - žádný
  import netřeba).
