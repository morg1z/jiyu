package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test výstupního lintu češtiny ([suspiciousCzechTokens] /
 * [isSuspiciousCzechOutput]) - audit Vagabondu: "ZATÍŽETE", "NEMYSL.", germanismus
 * "UPS!" se ukázaly čtenáři jako překlad. Testovací slovník je malý a úmyslný -
 * produkční asset `cs_common_words.txt` se do JVM testu nenatáhne.
 */
class CzechOutputLintTest {

    private val dict = setOf(
        "jsem", "aspoň", "ještě", "naživu", "vidím", "malý", "bastarde", "pojďme",
        "svazek", "kapitola", "zabiju", "museli", "nemyslel", "starče", "kluku",
        "dej", "toho", "hlasitost", "lovci", "uprchlíků", "kde", "jsme", "zastavit",
    )

    // ── suspiciousCzechTokens ──

    @Test
    fun `a truncated word is flagged`() {
        // "TO JSEM NEMYSL." - model usekl větu "to jsem nemyslel" uprostřed slova.
        assertEquals(
            listOf("nemysl"),
            suspiciousCzechTokens("TO JSEM NEMYSL.", "THAT'S NOT WHAT I MEANT.", dict),
        )
    }

    @Test
    fun `a gibberish word is flagged`() {
        assertEquals(
            listOf("zatížete"),
            suspiciousCzechTokens("ZATÍŽETE.", "CRUSH THEM.", dict),
        )
    }

    @Test
    fun `a correct czech sentence flags nothing`() {
        assertTrue(
            suspiciousCzechTokens("ASPOŇ JSME JEŠTĚ NAŽIVU.", "AT LEAST WE'RE STILL ALIVE.", dict).isEmpty(),
        )
    }

    @Test
    fun `a name echoed from the original is never flagged`() {
        // "takezō" není v CS slovníku, ale normalizovaně sedí na "TAKEZO" v originálu.
        assertTrue(suspiciousCzechTokens("TAKEZŌ!", "TAKEZO!", dict).isEmpty())
    }

    @Test
    fun `a declined name similar to the original is not flagged`() {
        // Skloňované jméno ("Froda") je 1 editační krok od "Frodo" - není to zkomolenina.
        assertTrue(suspiciousCzechTokens("VIDÍM FRODA.", "I SEE FRODO.", dict).isEmpty())
    }

    @Test
    fun `words shorter than four letters are never checked`() {
        // "ty"/"mě" - krátké tokeny se přeskakují; "malý"/"bastarde" jsou ve slovníku.
        assertTrue(suspiciousCzechTokens("TY MALÝ BASTARDE!", "YOU LITTLE BASTARD!", dict).isEmpty())
    }

    @Test
    fun `an empty dictionary never flags anything`() {
        assertTrue(suspiciousCzechTokens("ZATÍŽETE.", "CRUSH.", emptySet()).isEmpty())
        assertFalse(isSuspiciousCzechOutput("ZATÍŽETE.", "CRUSH.", emptySet()))
    }

    // ── isSuspiciousCzechOutput ──

    @Test
    fun `a fully gibberish bubble is suspicious`() {
        assertTrue(isSuspiciousCzechOutput("ZATÍŽETE.", "CRUSH THEM.", dict))
    }

    @Test
    fun `half wrong short bubble is suspicious`() {
        // "jsem" ok + "nemysl" flagged = 50 % - radši originál než useknutá věta.
        assertTrue(isSuspiciousCzechOutput("TO JSEM NEMYSL.", "THAT'S NOT WHAT I MEANT.", dict))
    }

    @Test
    fun `one unknown token in a long correct sentence is not suspicious`() {
        // 1 flag z 5 kontrolovaných (20 %) - jeden neologismus/skloňovaný tvar
        // nesmí shodit jinak správný překlad.
        assertFalse(
            isSuspiciousCzechOutput(
                "LOVCI UPRCHLÍKŮ MUSELI ZASTAVIT XYZZYRA.",
                "THE RUNAWAY HUNTERS HAD TO STOP XYZZYRA.",
                dict,
            ),
        )
    }

    @Test
    fun `punctuation-only text is never suspicious`() {
        assertFalse(isSuspiciousCzechOutput("...", "...", dict))
        assertFalse(isSuspiciousCzechOutput("?!", "?!", dict))
    }

    // ── Lint v2 (audit Vagabondu ch1+ch2) ──

    @Test
    fun `one suspicious token is enough inside a short block`() {
        // "MYŠLEL" ve slovníku není (správně "myslel") - u krátkého bloku stačí
        // jediné podezřelé slovo; třtinový práh pustil "K ZBRAĎ"/"DVAJKRÁT".
        assertTrue(isSuspiciousCzechOutput("JSEM MYŠLEL STARČE.", "I THOUGHT SO, OLD MAN.", dict))
    }

    @Test
    fun `an english word leaked into czech is always flagged`() {
        // "SURVIVOR" je anglické slovo i zdrojový token - jmenový filtr by ho pustil
        // (shoda se zdrojem), EN slovník ho chytí dřív. Verbatim únik z auditu.
        assertTrue(
            isSuspiciousCzechOutput(
                "JSEM SURVIVOR.", "I'M A SURVIVOR.", dict,
                englishWords = setOf("survivor", "want", "break"),
            ),
        )
    }

    @Test
    fun `a longer sentence still needs the third-of-tokens rule`() {
        // 8 kontrolovaných slov, 1 skutečně podezřelé ("KVARGH" není ve slovníku ani
        // blízko žádnému zdrojovému tokenu) -> pod třetinou -> neflaguje se.
        assertFalse(
            isSuspiciousCzechOutput(
                "LOVCI UPRCHLÍKŮ MUSELI VIDÍM ZASTAVIT JEŠTĚ KDE KVARGH.",
                "THE RUNAWAY HUNTERS HAD TO SEE WHERE KVARGH STOPPED.",
                dict,
            ),
        )
    }
}
