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
