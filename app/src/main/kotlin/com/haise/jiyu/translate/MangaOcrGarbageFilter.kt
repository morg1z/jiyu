package com.haise.jiyu.translate

/**
 * Čistý (bez Androidu/ONNX) filtr na "zdegenerovaný" výstup malých encoder-decoder OCR
 * modelů - na prázdném/šumovém/nesmyslném výřezu bubliny místo "nic nenašel" typicky
 * dokola opakuje pár stejných znaků (tečky, čárky, katakanu, pomlčky) místo aby se
 * zastavil na EOS. [MangaOcrPipeline] takový výstup zahodí (vrátí `null`), stejně jako
 * timeout/OOM - volající ([com.haise.jiyu.translate.OcrEngine]) pak zkusí ML Kit fallback
 * na tu samou bublinu místo aby vykreslil dekódovaný nesmysl jako "přeložený" text.
 *
 * Koncept (ne kód) podle `ogkalu/manga-ocr-mobile`'s vlastního post-processingu -
 * viz TRANSLATION_BENCHMARK_REPORT.md, Comic Translate: `_is_pathological_punctuation_run`/
 * `_is_pathological_kana_loop`/`_is_pathological_separator_run`. Jiyu re-implementuje jednu
 * obecnější detekci opakujícího se krátkého vzoru místo tří samostatných regexů - stejný
 * princip, méně kódu k údržbě.
 */
internal object MangaOcrGarbageFilter {

    /**
     * True, pokud [text] vypadá jako zdegenerovaná smyčka - krátký (1-3 znaky) vzor
     * opakovaný alespoň [minRepeats]x a pokrývající alespoň [minCoverageFraction] celého
     * textu. Prázdný/velmi krátký text (< [MIN_LENGTH_TO_CHECK] znaků) se nikdy neoznačí -
     * krátká legitimní odpověď ("...", "!!") by jinak byla falešně zahozena.
     */
    fun isPathologicalOutput(
        text: String,
        minRepeats: Int = MIN_REPEATS,
        minCoverageFraction: Float = MIN_COVERAGE_FRACTION,
    ): Boolean {
        if (text.length < MIN_LENGTH_TO_CHECK) return false
        for (patternLen in 1..MAX_PATTERN_LENGTH) {
            if (hasRepeatingPattern(text, patternLen, minRepeats, minCoverageFraction)) return true
        }
        return false
    }

    /**
     * Hledá NEJDELŠÍ souvislý běh, kde se stejný podřetězec délky [patternLen] opakuje
     * bezprostředně za sebou, a porovná jeho pokrytí s celkovou délkou textu.
     */
    private fun hasRepeatingPattern(text: String, patternLen: Int, minRepeats: Int, minCoverageFraction: Float): Boolean {
        if (text.length < patternLen * minRepeats) return false
        var bestRunChars = 0
        var i = 0
        while (i + patternLen <= text.length) {
            val pattern = text.substring(i, i + patternLen)
            var repeats = 1
            var j = i + patternLen
            while (j + patternLen <= text.length && text.substring(j, j + patternLen) == pattern) {
                repeats++
                j += patternLen
            }
            if (repeats >= minRepeats) bestRunChars = maxOf(bestRunChars, repeats * patternLen)
            i = if (repeats >= minRepeats) j else i + 1
        }
        return bestRunChars.toFloat() / text.length >= minCoverageFraction
    }

    private const val MIN_LENGTH_TO_CHECK = 6
    private const val MAX_PATTERN_LENGTH = 3
    private const val MIN_REPEATS = 4
    private const val MIN_COVERAGE_FRACTION = 0.6f
}
