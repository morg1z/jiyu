package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nahlášeno se srovnávací dvojicí snímků ze stránky Vagabonda: originál „WE'RE TAKING OFF."
 * se vykreslil jako „ODLÉTÁME" a pod tím OSAMOCENÁ TEČKA na vlastním řádku - uživateli to
 * čte jako „bublina přišla o text". Viz [tidyStrandedPunctuation].
 */
class StrayPunctuationTest {

    @Test
    fun `a space before the closing period is removed`() {
        // JÁDRO: mezera nabídne zalamovači zlom, „ODLÉTÁME" se na řádek vejde a „." už ne.
        assertEquals("ODLÉTÁME.", tidyStrandedPunctuation("ODLÉTÁME ."))
    }

    @Test
    fun `a line break before the closing period is removed`() {
        assertEquals("ODLÉTÁME.", tidyStrandedPunctuation("ODLÉTÁME\n."))
    }

    @Test
    fun `other closing punctuation is handled too`() {
        assertEquals("MŮŽEŠ CHODIT?", tidyStrandedPunctuation("MŮŽEŠ CHODIT ?"))
        assertEquals("PRO ZÁBAVU!", tidyStrandedPunctuation("PRO ZÁBAVU !"))
        assertEquals("TAKEZO…", tidyStrandedPunctuation("TAKEZO …"))
        assertEquals("JSEM HOTOVÝ...", tidyStrandedPunctuation("JSEM HOTOVÝ ..."))
    }

    @Test
    fun `a leading ellipsis stays attached to its own word`() {
        // POJISTKA proti přestřelení. Věta pokračující z předchozí bubliny („...KONČÍ") je běžná
        // komiksová sazba - slepit ji na předchozí slovo by změnilo zalomení, které tam autor
        // chtěl. Pravidlo proto sahá jen na interpunkci, za kterou už nic není.
        assertEquals("TAK\n...KONEC", tidyStrandedPunctuation("TAK\n...KONEC"))
        assertEquals("BITVA ...SKONČILA", tidyStrandedPunctuation("BITVA ...SKONČILA"))
    }

    @Test
    fun `normal text is left exactly as it is`() {
        assertEquals("NEMŮŽU UŽ CHODIT.", tidyStrandedPunctuation("NEMŮŽU UŽ CHODIT."))
        assertEquals("ANO, JISTĚ.", tidyStrandedPunctuation("ANO, JISTĚ."))
        assertEquals("", tidyStrandedPunctuation(""))
    }

    @Test
    fun `unicode space separators before the closing period are removed too`() {
        // Nahlášeno se skutečnou stránkou (Vagabond, "A JSME UPRCHLÍCI" + osamocená tečka) -
        // bublina seděla v JEDNÉ souvislé bublině (ověřeno vizuálně zoomem do screenshotu,
        // žádný oddělený tvar/pozadí), takže šlo o zalomení, ne o ztracený OCR blok. Testy
        // s obyčejnou mezerou/zlomem řádku výš ale prochází - podezření padlo na to, že model
        // (teď i Cerebras/Mistral/Groq, ne jen Gemini) místo obyčejné mezery U+0020 občas
        // vrátí jiný unicode "space" znak, který stará třída [ \t\n\r ] nezná, takže
        // zalamovači zůstane nabídnutý zlom stát.
        //
        // Znaky se skládají z kódu přes [Char], ne jako literál v souboru - stejný důvod jako
        // u SOFT_HYPHEN v SoftHyphenation.kt (neviditelný/těžko odlišitelný znak je nebezpečný
        // na dohledání/úpravu přímo v souboru).
        val emSpace = 0x2003.toChar()
        val thinSpace = 0x2009.toChar()
        val ideographicSpace = 0x3000.toChar()
        val enSpace = 0x2002.toChar()
        assertEquals("UPRCHLÍCI.", tidyStrandedPunctuation("UPRCHLÍCI$emSpace."))
        assertEquals("UPRCHLÍCI.", tidyStrandedPunctuation("UPRCHLÍCI$thinSpace."))
        assertEquals("UPRCHLÍCI.", tidyStrandedPunctuation("UPRCHLÍCI$ideographicSpace."))
        assertEquals("UPRCHLÍCI.", tidyStrandedPunctuation("UPRCHLÍCI$enSpace."))
    }

    @Test
    fun `a zero-width space before the closing period is removed too`() {
        // Neviditelný znak (nulová šířka) je ještě zákeřnější než unicode mezera - v editoru/logu
        // vypadá text úplně stejně jako správně slepený, takže bez tohohle testu by regrese
        // prošla bez povšimnutí.
        val zeroWidthSpace = 0x200B.toChar()
        val zeroWidthNoBreakSpace = 0xFEFF.toChar() // BOM
        assertEquals("UPRCHLÍCI.", tidyStrandedPunctuation("UPRCHLÍCI$zeroWidthSpace."))
        assertEquals("UPRCHLÍCI.", tidyStrandedPunctuation("UPRCHLÍCI$zeroWidthNoBreakSpace."))
    }

    @Test
    fun `spacing inside the sentence is not touched`() {
        // Mezera před čárkou UPROSTŘED věty se nechává být: za ní text pokračuje, takže osamocený
        // řádek z ní vzniknout nemůže, a přepisovat uživateli text nad rámec nahlášené chyby nemá
        // proč.
        assertEquals("ANO , JISTĚ.", tidyStrandedPunctuation("ANO , JISTĚ."))
    }

    @Test
    fun `a space before a question or exclamation mark is removed anywhere in the text`() {
        // Nahlášeno se stránkou Vagabonda: "CO DĚLÁŠ ?", "...TAKEZŌ ?" - na rozdíl od
        // tidyStrandedPunctuation smí sáhnout KAMKOLI v textu (viz doc komentář u
        // tidyFrenchStyleSpacing), ne jen na osamocený konec věty.
        assertEquals("CO DĚLÁŠ?", tidyFrenchStyleSpacing("CO DĚLÁŠ ?"))
        assertEquals("UTÍKEJ!", tidyFrenchStyleSpacing("UTÍKEJ !"))
        assertEquals("TAKEZŌ?", tidyFrenchStyleSpacing("TAKEZŌ ?"))
        assertEquals("CO DĚLÁŠ? PROČ?", tidyFrenchStyleSpacing("CO DĚLÁŠ ? PROČ ?"))
    }

    @Test
    fun `a space before a colon or semicolon is removed too`() {
        assertEquals("POSLOUCHEJ: TOHLE JE DŮLEŽITÉ.", tidyFrenchStyleSpacing("POSLOUCHEJ : TOHLE JE DŮLEŽITÉ."))
        assertEquals("POČKEJ; JEŠTĚ NE.", tidyFrenchStyleSpacing("POČKEJ ; JEŠTĚ NE."))
    }

    @Test
    fun `a unicode or zero-width space before a question mark is removed too`() {
        val emSpace = 0x2003.toChar()
        val zeroWidthSpace = 0x200B.toChar()
        assertEquals("CO DĚLÁŠ?", tidyFrenchStyleSpacing("CO DĚLÁŠ${emSpace}?"))
        assertEquals("CO DĚLÁŠ?", tidyFrenchStyleSpacing("CO DĚLÁŠ${zeroWidthSpace}?"))
    }

    @Test
    fun `a period or comma mid-sentence is not touched`() {
        // Tečka/čárka schválně vynechané - viz doc komentář u tidyFrenchStyleSpacing.
        assertEquals("ANO , JISTĚ.", tidyFrenchStyleSpacing("ANO , JISTĚ."))
    }

    @Test
    fun `a block without a single letter carries nothing translatable`() {
        assertFalse(hasTranslatableLetters("."))
        assertFalse(hasTranslatableLetters("..."))
        assertFalse(hasTranslatableLetters("  !? "))
        assertFalse(hasTranslatableLetters(""))
    }

    @Test
    fun `a block with letters is kept`() {
        assertTrue(hasTranslatableLetters("A"))
        assertTrue(hasTranslatableLetters("...SKONČILA."))
        assertTrue(hasTranslatableLetters("ODLÉTÁME"))
    }
}
