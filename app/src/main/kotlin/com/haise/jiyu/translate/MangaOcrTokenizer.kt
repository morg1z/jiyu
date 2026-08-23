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
