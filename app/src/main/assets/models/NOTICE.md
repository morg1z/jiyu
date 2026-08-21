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
