package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testy spojování slov, která lettering rozdělil na konci řádku.
 *
 * Nahlášený případ: `EVERY-` / `ONE DON'T SCATTER, STAY TOGETHER!` dorazilo k modelu jako
 * `EVERY- ONE DON'T SCATTER...` a překlad z toho vyšel jako věta, která si odporuje sama.
 */
class OcrTextCleanupTest {

    @Test
    fun `a word split at the end of a line is put back together`() {
        assertEquals(
            "EVERY-ONE DON'T SCATTER, STAY TOGETHER!",
            joinHyphenatedLineBreaks("EVERY-\nONE DON'T SCATTER, STAY TOGETHER!"),
        )
    }

    @Test
    fun `the hyphen stays, so a real compound survives`() {
        // JADRO ROZHODNUTI: rozdil mezi delenim slova a skutecnym spojovnikem z textu
        // nepoznáme. Ponechani pomlcky je spravne v obou pripadech - smazat ji by rozbilo
        // prave tenhle.
        assertEquals("well-known", joinHyphenatedLineBreaks("well-\nknown"))
    }

    @Test
    fun `spaces around the line break do not matter`() {
        assertEquals("AR-MOR?", joinHyphenatedLineBreaks("AR-  \n  MOR?"))
    }

    @Test
    fun `windows line endings work too`() {
        assertEquals("AR-MOR?", joinHyphenatedLineBreaks("AR-\r\nMOR?"))
    }

    @Test
    fun `a line break without a hyphen is left alone`() {
        // Zalomeni mezi vetami nese informaci o sazbe bubliny - neslucovat.
        assertEquals("HELLO\nTHERE", joinHyphenatedLineBreaks("HELLO\nTHERE"))
    }

    @Test
    fun `a dash used as punctuation is not glued to the next line`() {
        // Pomlcka po mezere neni deleni slova, ale interpunkce.
        assertEquals("WAIT -\nWHAT?", joinHyphenatedLineBreaks("WAIT -\nWHAT?"))
    }

    @Test
    fun `a hyphen followed by something other than a letter is left alone`() {
        assertEquals("PAGE-\n42", joinHyphenatedLineBreaks("PAGE-\n42"))
    }

    @Test
    fun `several splits in one bubble are all joined`() {
        assertEquals(
            "MOUN-TAIN BEASTS OF ALL THINGS... WEAR-ING ORCISH AR-MOR?",
            joinHyphenatedLineBreaks("MOUN-\nTAIN BEASTS OF ALL THINGS... WEAR-\nING ORCISH AR-\nMOR?"),
        )
    }

    @Test
    fun `text without any hyphen comes back unchanged`() {
        val text = "SHUT YOUR MOUTH BEFORE I TEAR YOU APART."
        assertEquals(text, joinHyphenatedLineBreaks(text))
    }

    @Test
    fun `uppercase contractions without an apostrophe are restored`() {
        assertEquals("I'M FINISHED.", cleanOcrLatinText("IM FINISHED."))
        assertEquals("DON'T THINK... THAT'S IT!", cleanOcrLatinText("DONT THINK... THATS IT!"))
    }

    @Test
    fun `lowercase words and words that merely contain a contraction are left alone`() {
        assertEquals("im not sure", cleanOcrLatinText("im not sure"))
        assertEquals("TIMBER IMPACT", cleanOcrLatinText("TIMBER IMPACT"))
        assertEquals("I'M HERE", cleanOcrLatinText("I'M HERE"))
    }

    @Test
    fun `a stray leading dot before a word becomes an ellipsis`() {
        assertEquals("...FORGET ABOUT THE ENEMY...", cleanOcrLatinText(".FORGET ABOUT THE ENEMY..."))
        assertEquals("...WAIT", cleanOcrLatinText("..WAIT"))
        assertEquals("...ALREADY", cleanOcrLatinText("...ALREADY"))
    }

    @Test
    fun `japanese text is never touched`() {
        assertEquals(".IM ドドド", cleanOcrLatinText(".IM ドドド"))
    }

    // ── OCR šum uvnitř verzálkových slov (živé nálezy z telefonu - Poison Ivy #41) ──

    @Test
    fun `digit 6 read as S inside an uppercase word is fixed`() {
        assertEquals("MAKES", cleanOcrLatinText("MAKE6"))
        assertEquals("SHOES", cleanOcrLatinText("SHOE6"))
        assertEquals("THAT'S", cleanOcrLatinText("THAT'6"))
        assertEquals("SUPERMAX", cleanOcrLatinText("6UPERMAX"))
    }

    @Test
    fun `digit 0 read as O inside an uppercase word is fixed`() {
        assertEquals("HOUSE", cleanOcrLatinText("H0USE"))
    }

    @Test
    fun `a single lowercase letter inside an uppercase word is uppercased`() {
        assertEquals("GENETICS", cleanOcrLatinText("GENETIc6"))
        assertEquals("COULD", cleanOcrLatinText("cOULD"))
        assertEquals("GUY", cleanOcrLatinText("GUy"))
        // V komiksovém letteringu je I holá čárka - ML Kit ji vypíše jako l.
        assertEquals("LIMITS", cleanOcrLatinText("LIMlTS"))
    }

    @Test
    fun `real codes and short tokens are left alone`() {
        assertEquals("R2D2", cleanOcrLatinText("R2D2"))
        assertEquals("C-3PO", cleanOcrLatinText("C-3PO"))
        assertEquals("F-16", cleanOcrLatinText("F-16"))
        assertEquals("20TH", cleanOcrLatinText("20TH"))
        assertEquals("APT6", cleanOcrLatinText("APT6"))
        assertEquals("60FT", cleanOcrLatinText("60FT"))
        assertEquals("6FT", cleanOcrLatinText("6FT"))
    }

    @Test
    fun `real lowercase words and names are left alone`() {
        assertEquals("the quick brown fox", cleanOcrLatinText("the quick brown fox"))
        assertEquals("McCready", cleanOcrLatinText("McCready"))
        assertEquals("BILL", cleanOcrLatinText("BILL"))
        assertEquals("KING THING SING", cleanOcrLatinText("KING THING SING"))
    }

    @Test
    fun `observed garbled words are fixed by the dictionary`() {
        assertEquals("WANTS", cleanOcrLatinText("WANTG"))
        assertEquals("COMES", cleanOcrLatinText("COMEG"))
        assertEquals("MURDER", cleanOcrLatinText("MLURDER"))
        assertEquals("NEXT", cleanOcrLatinText("WEXT"))
        assertEquals("YOU'VE", cleanOcrLatinText("YOUVE"))
        assertEquals("DC K.O. TIE-IN", cleanOcrLatinText("DC K.0. TIE-IN"))
    }

    @Test
    fun `noise cleanup keeps surrounding punctuation intact`() {
        assertEquals(
            "\"AND I SURE HOPE SHE MAKES IT OUT OKAY.\"",
            cleanOcrLatinText("\"AND I SURE HOPE SHE MAKE6 IT OUT OKAY.\""),
        )
    }

    @Test
    fun `cleaning a page keeps untouched blocks and geometry`() {
        val untouched = RawTextBlock(text = "HELLO", leftF = 0.1f, topF = 0.2f, rightF = 0.3f, bottomF = 0.4f)
        val dirty = RawTextBlock(text = "IM HERE", leftF = 0.5f, topF = 0.6f, rightF = 0.7f, bottomF = 0.8f)
        val result = listOf(untouched, dirty).withCleanedOcrText()
        assertEquals(untouched, result[0])
        assertEquals(dirty.copy(text = "I'M HERE"), result[1])
    }

    // ── Slovníkový lint (audit Vagabondu - en_common_words.txt, ~20k slov) ──

    /** Miniaturní slovník pro testy - produkční má ~20 000 položek z assetu. */
    private val dict = setOf(
        "village", "forget", "survivor", "sure", "swords", "lethal", "alive",
        "thus", "lava", "late", "love", "cave", "work", "born", "fork", "bore",
        "volume", "that", "hell", "hello", "smell", "small",
    )

    @Test
    fun `audit corruptions are fixed by the table even without a dictionary`() {
        assertEquals("FORGET", cleanOcrLatinText("FOGET"))
        assertEquals("SURVIVOR", cleanOcrLatinText("SIRVIVOR"))
        assertEquals("SURE", cleanOcrLatinText("SHURE"))
    }

    @Test
    fun `dictionary lint repairs a missing letter`() {
        // "VALLAGE" → "VILLAGE" (výměna A→I, jediný kandidát v distance 1).
        assertEquals("VILLAGE", cleanOcrLatinText("VALLAGE", dict))
        assertEquals("WELCOME TO THE VILLAGE.", cleanOcrLatinText("WELCOME TO THE VALLAGE.", dict))
    }

    @Test
    fun `dictionary lint removes the inside-line hyphen artifact`() {
        // Lettering rozbil slovo pomlckou uprostred radku (ne na konci - tam ji chyta
        // joinHyphenatedLineBreaks). "VAL-LAGE" -> join "VALLAGE" -> distance1 "VILLAGE".
        assertEquals("VILLAGE", cleanOcrLatinText("VAL-LAGE", dict))
    }

    @Test
    fun `a word already in the dictionary is never touched`() {
        assertEquals("SWORDS", cleanOcrLatinText("SWORDS", dict))
        assertEquals("LETHAL", cleanOcrLatinText("LETHAL", dict))
        assertEquals("VILLAGE", cleanOcrLatinText("VILLAGE", dict))
        assertEquals("VOLUME", cleanOcrLatinText("VOLUME", dict))
    }

    @Test
    fun `known SFX are never repaired into dictionary words`() {
        // "THUD" ve slovniku neni a "THUS" je na vzdalenost 1 - bez SFX ochrany by
        // lint zvuk rozdelal na repliku.
        assertEquals("THUD", cleanOcrLatinText("THUD", dict))
        assertEquals("FWOOSH", cleanOcrLatinText("FWOOSH", dict))
        assertEquals("BOOM", cleanOcrLatinText("BOOM", dict))
    }

    @Test
    fun `ambiguous distance-1 candidates are left alone`() {
        // "LAVE" ma ve fixture slovniku nekolik sousedu (LAVA/LATE/LOVE/CAVE) -
        // lint nema jak rozhodnout, takze nesaha.
        assertEquals("LAVE", cleanOcrLatinText("LAVE", dict))
    }

    @Test
    fun `names and contractions stay untouched by the lint`() {
        // Jmeno mimo slovnik, zadny unikatni soused.
        assertEquals("TAKEZO", cleanOcrLatinText("TAKEZO", dict))
        // Apostrof vylucuje ucast (kontrakce resi drivsi pravidlo; "THAT'S" by jinak
        // sjednoceni na "THATS" opravilo na "THAT").
        assertEquals("THAT'S", cleanOcrLatinText("THAT'S", dict))
        assertEquals("K.O.", cleanOcrLatinText("K.O.", dict))
    }

    @Test
    fun `lint without a dictionary is a no-op`() {
        assertEquals("VALLAGE", cleanOcrLatinText("VALLAGE"))
        assertEquals("VAL-LAGE", cleanOcrLatinText("VAL-LAGE"))
    }

    // ── Pomlčka+mezera UVNITŘ řádku (joinIntraLineHyphens, audit Vagabondu) ──

    @Test
    fun `intra-line hyphen joins when the merged word is provable`() {
        // Lettering rozbil slovo pomlckou uprostred radku ("VAL- LAGE", "FOR- GET") -
        // joinHyphenatedLineBreaks spojuje jen pres zlom \n, tohle je jeho doplneni.
        assertEquals("VILLAGE", cleanOcrLatinText("VAL- LAGE", dict))
        assertEquals("FORGET ABOUT IT.", cleanOcrLatinText("FOR- GET ABOUT IT.", dict))
        assertEquals("SURVIVOR...", cleanOcrLatinText("SUR- VIVOR...", dict))
    }

    @Test
    fun `an unprovable intra-line split only loses the stray space`() {
        // Bez slovnikoveho dukazu se jen zrusi mezera za pomlckou - "WELL- KNOWN"
        // se sbali na "WELL-KNOWN", skutecny spojovnik prezije a model ho cte lip.
        assertEquals("WELL-KNOWN", cleanOcrLatinText("WELL- KNOWN", dict))
        assertEquals("VAL-LAGE", cleanOcrLatinText("VAL- LAGE"))
    }

    @Test
    fun `a hyphenated name is never glued through the lint`() {
        // "MATA- HACHI" neni slovnikove slovo a spojenec "MATAHACHI" nema unikatniho
        // souseda - zustane kompaktni "MATA-HACHI", ne rozbite jmeno.
        assertEquals("MATA-HACHI", cleanOcrLatinText("MATA- HACHI", dict))
    }
}
