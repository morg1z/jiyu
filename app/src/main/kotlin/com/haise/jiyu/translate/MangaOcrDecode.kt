package com.haise.jiyu.translate

/**
 * Čisté (bez ONNX Runtime) řízení greedy autoregresivního dekódování - viz
 * [MangaOcrPipeline], které sem injektuje [nextToken] navázané na skutečnou inferenci
 * `manga_ocr_decoder_init.onnx`/`manga_ocr_decoder_step.onnx`. Odděleno schválně, aby šlo
 * otestovat JVM testem na falešném [nextToken], bez nutnosti mít na stroji reálný model
 * nebo Android - stejný vzor jako [resolveAutoLanguage] v OcrEngine.kt.
 *
 * `manga-ocr-mobile` dekóduje S KV-cache (na rozdíl od staršího `manga-ocr-base` exportu,
 * který ji neměl) - [nextToken] v produkci posílá jen JEDEN nový token na krok a rostoucí
 * KV-cache stav drží ve vlastním closure kolem tady definovaného kontraktu, viz
 * [MangaOcrPipeline.recognizeCrop]. Tahle smyčka o tom nic neví: `soFar.size - 1` dává
 * volajícímu přesně pozici dalšího tokenu a `soFar.last()` poslední vygenerovaný token, což
 * KV-cache implementaci stačí, aniž by se měnil tenhle obecný kontrakt.
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
