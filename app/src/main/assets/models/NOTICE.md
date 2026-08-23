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
