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
