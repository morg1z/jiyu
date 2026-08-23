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
