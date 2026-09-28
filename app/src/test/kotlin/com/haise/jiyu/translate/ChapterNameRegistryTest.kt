package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test registru jmen a konzistence frází ([ChapterNameRegistry]) -
 * audit Vagabondu: "TSIJJIKAZE"/"SAKUSHO"/"HONIDEN" jako poškozené varianty
 * jmen a "NO WAY I'M GONNA DIE!" přeložené jednou česky a jednou vůbec.
 */
class ChapterNameRegistryTest {

    private val en = setOf("the", "war", "sword", "swords", "collect", "corpse", "village", "survivor")
    private val cs = setOf("jsme", "zabiju", "svazek", "vesnice", "meče", "sbírej", "každé", "mrtvoly", "přeživší")

    @Test
    fun `a diacritic form from a title wins as canonical`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.observeSource("TAKEZO JDE VEN.")
        reg.observeSource("SHINMEN TAKEZŌ, ZAKLÍNAČ")
        // Kanonický tvar je ten s makronem - výstup se sjednotí na něj.
        assertEquals("TAKEZŌ JDE VEN.", reg.normalizeText("TAKEZO JDE VEN."))
    }

    @Test
    fun `a mangled name in the output is fixed to the canonical form`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.observeSource("TSUJIKAZE SE VRACÍ.")
        assertEquals("TSUJIKAZE!", reg.normalizeText("TSIJJIKAZE!"))
    }

    @Test
    fun `a dropped apostrophe in a name is restored`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.observeSource("HON'IDEN MATAJIRO.")
        // Klíč "honiden" se přesně trefí kanonickým tvarem s apostrofem.
        assertEquals("HON'IDEN", reg.normalizeText("HONIDEN"))
    }

    @Test
    fun `dictionary words are never rewritten as names`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.observeSource("VILLAGE SURVIVOR SWORDS.")
        // "swords" je anglické slovníkové slovo - registr ho nesmí přepsat,
        // i když by klíč byl blízko nějakému jménu.
        val out = reg.normalizeText("SBÍREJ MEČE Z KAŽDÉ MRTVOLY.")
        assertEquals("SBÍREJ MEČE Z KAŽDÉ MRTVOLY.", out)
    }

    @Test
    fun `nothing registers from a lowercase word`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.observeSource("village swords collect")   // jen malá písmena, žádný kandidát
        assertEquals("village swords", reg.normalizeText("village swords"))
    }

    @Test
    fun `identical source sentences get identical translations`() {
        val reg = ChapterNameRegistry(en, cs)
        // První výskyt se zapíše...
        val (first, fromCache1) = reg.consistentTranslation("NO WAY I'M GONNA DIE!", "TAKHLE NECHCI ZEMŘÍT!", wasTranslated = true)
        assertFalse(fromCache1)
        assertEquals("TAKHLE NECHCI ZEMŘÍT!", first)
        // ...druhý dostane tentýž překlad i když model vrátil něco jiného.
        val (second, fromCache2) = reg.consistentTranslation("NO WAY I'M GONNA DIE!", "ROZHODNĚ NEUMÍRAM!", wasTranslated = true)
        assertTrue(fromCache2)
        assertEquals("TAKHLE NECHCI ZEMŘÍT!", second)
    }

    @Test
    fun `a cached phrase rescues a block the model dropped`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.consistentTranslation("I SEE.", "ROZUMÍM.", wasTranslated = true)
        // Tentokrát model zdroj vynechal - blok je untranslated, ale cache ho do-přeloží.
        val (out, fromCache) = reg.consistentTranslation("I SEE.", "I SEE.", wasTranslated = false)
        assertTrue(fromCache)
        assertEquals("ROZUMÍM.", out)
    }

    @Test
    fun `cached phrase adapts terminal punctuation to the new source`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.consistentTranslation("I SEE.", "ROZUMÍM.", wasTranslated = true)
        val (out, _) = reg.consistentTranslation("I SEE!", "I SEE!", wasTranslated = false)
        assertEquals("ROZUMÍM!", out)
    }

    @Test
    fun `an untranslated first occurrence does not poison the cache`() {
        val reg = ChapterNameRegistry(en, cs)
        // První výskyt NEBYL přeložen - nesmí se zapsat (jinak by se originál
        // propagoval jako "překlad").
        reg.consistentTranslation("GIVE ME A BREAK.", "GIVE ME A BREAK.", wasTranslated = false)
        val (out, fromCache) = reg.consistentTranslation("GIVE ME A BREAK.", "NEBLÁZNI.", wasTranslated = true)
        assertFalse(fromCache)
        assertEquals("NEBLÁZNI.", out)
    }

    @Test
    fun `very short phrases never collide through the cache`() {
        val reg = ChapterNameRegistry(en, cs)
        reg.consistentTranslation("HM?", "HM?", wasTranslated = true)
        val (out, fromCache) = reg.consistentTranslation("AH!", "ACH!", wasTranslated = true)
        assertFalse(fromCache)
        assertEquals("ACH!", out)
    }
}
