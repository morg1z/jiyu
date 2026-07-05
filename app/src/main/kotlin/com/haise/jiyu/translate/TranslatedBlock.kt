package com.haise.jiyu.translate

/**
 * Jeden přeložený textový blok (bublina) na stránce mangy.
 * Souřadnice jsou relativní (0.0–1.0) vůči rozměrům obrázku,
 * takže fungují nezávisle na rozlišení displeje.
 */
data class TranslatedBlock(
    val originalText: String,
    val translatedText: String,
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val bottomF: Float,
)
