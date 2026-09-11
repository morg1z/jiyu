# Třetí strany - natrénované modely

## comic_bubble_detector.onnx

- Zdroj: https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m
- Autor: ogkalu
- Licence: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Architektura: YOLOv8m (Ultralytics), natrénovaný na ~8k obrázcích manga/webtoon/manhua/
  western comics pro detekci bublin (třídy "text_bubble", "text_free").
- Do ONNX (opset 20, imgsz 640, statické rozměry) exportováno lokálně přes
  `ultralytics.YOLO.export(format="onnx")` z originálního `.pt` souboru - repo samo ONNX
  export neposkytuje.
- Žádná modifikace vah, jen formát exportu.

Používá se v [com.haise.jiyu.translate.BubbleBoxDetector] jako nezávislý zdroj "kde na
stránce je bublina", vedle stávající heuristiky založené na OCR textu.

## bubble_mask_segmenter.onnx

- Zdroj: https://huggingface.co/kitsumed/yolov8m_seg-speech-bubble
- Autor: kitsumed
- **Licence: GNU General Public License v3.0 (https://www.gnu.org/licenses/gpl-3.0.html)**
- Architektura: YOLOv8m-seg (Ultralytics), instanční segmentace bublin (jedna třída,
  "speech bubble") - na rozdíl od `comic_bubble_detector.onnx` vrací přímo pixelovou
  masku tvaru bubliny, ne jen obdélník.
- ONNX export (`model_dynamic.onnx`, dynamické rozměry) stažen přímo z repozitáře -
  žádná lokální konverze ani modifikace vah.

**DŮLEŽITÉ - GPL-3.0 dopad na zbytek appky:** GPL-3.0 vyžaduje, aby JAKÝKOLIV kód, který
tenhle model načítá/spouští, byl taky dostupný pod GPL-3.0 kompatibilní licencí komukoliv,
komu appku dáš (i mimo Play Store/veřejný release) - to zahrnuje minimálně
[com.haise.jiyu.translate.BubbleMaskSegmenter] a vše, co na jeho výstupu přímo staví.
Model se proto používá VÝHRADNĚ jako poslední záchranná záloha, když selže jak
[com.haise.jiyu.translate.BubbleShapeDetector.detectShape], tak
[com.haise.jiyu.translate.BubbleShapeDetector.edgeAwareShape] (třída bugů "bublina
s divným/hranatým tvarem, kde flood-fill nenajde uzavřený obrys") - ne pro každou bublinu.

## manga_ocr_encoder.onnx + manga_ocr_decoder_init.onnx + manga_ocr_decoder_step.onnx + manga_ocr_vocab.txt

- Zdroj: https://huggingface.co/ogkalu/manga-ocr-mobile (revize
  `aa0d7d3199f5843f8f5d743f85b44098c8e3ac98`)
- Autor: ogkalu
- Licence: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Architektura: stejná rodina jako `kha-white/manga-ocr-base` (Vision Encoder-Decoder,
  ViT encoder + BERT-styl decoder), ale exportováno se 3 samostatnými ONNX grafy a
  KV-cache pro dekodér: `encoder.onnx` (obrázek -> `encoder_hidden_states`
  [1,196,256]), `decoder_init.onnx` (první token + `encoder_hidden_states` ->
  logity pro pozici 0 + počáteční `self_k`/`self_v` [4,1,4,1,64] + předpočítané
  `cross_k`/`cross_v` [4,1,4,196,64]) a `decoder_step.onnx` (jeden nový token +
  `position_ids` + rostoucí `self_k_cache`/`self_v_cache` [4,1,4,256,64] + stejné
  `cross_k_cache`/`cross_v_cache` -> logity + nová `self_k_slice`/`self_v_slice`
  [4,1,4,1,64], které volající zapíše do cache na pozici `position_ids`). Nahrazuje
  starší `kha-white/manga-ocr-base` export, který KV-cache nepodporoval a při
  dekódování posílal celou dosavadní sekvenci znovu do dekodéru na každém kroku -
  nový model je zároveň výrazně menší (~64 MB celkem vs. ~460 MB).
- Přesný tvar/název vstupů a výstupů ověřen lokálně (`onnxruntime` v Pythonu,
  `InferenceSession.get_inputs()/get_outputs()`), ne převzat z READMEs - viz
  [com.haise.jiyu.translate.MangaOcrPipeline], kde jsou stejné konstanty použité
  v Kotlinu.
- `manga_ocr_vocab.txt` - slovník tokenizeru (9415 řádků, index řádku = ID tokenu,
  stejný BERT-styl znakový formát jako předchozí model, `subword_tokenizer_type=
  "character"` - beze změny v [com.haise.jiyu.translate.MangaOcrTokenizer]).
- Žádná modifikace vah, jen formát exportu (převzat přímo z repozitáře).

Používá se v [com.haise.jiyu.translate.MangaOcrPipeline] jako hlavní zdroj rozpoznaného
textu pro japonštinu (nahrazuje ML Kit Japanese recognizer) - viz
[com.haise.jiyu.translate.OcrEngine.recognize]. ML Kit zůstává záložním zdrojem pro
jednotlivé bubliny, kde tenhle model selže, vyprší timeout, nebo výstup vypadá jako
zdegenerovaná smyčka ([com.haise.jiyu.translate.MangaOcrGarbageFilter]) či má nízkou
průměrnou pravděpodobnost tokenů.
