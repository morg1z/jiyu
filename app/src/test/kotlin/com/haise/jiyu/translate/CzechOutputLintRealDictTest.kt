package com.haise.jiyu.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regresní test nad PRODUKČNÍMI slovníky v `src/main/assets` - audit Vagabond
 * ch.1 odhalil, že `cs_common_words.txt` postrádal top frekventovaná slova ("ale",
 * "tady", "aby", "věc", "tráva", "svazek"...), takže lint flagoval správné překlady
 * a ~16 bublin kapitoly šlo čtenáři anglicky přestože překlad existoval.
 */
class CzechOutputLintRealDictTest {

    private fun loadWords(name: String): Set<String> {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("../app/src/main/assets/$name"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: error("asset $name nenalezen (zkoušeno: ${candidates.joinToString { it.path }})")
        return f.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }

    private val cs by lazy { loadWords("cs_common_words.txt") }
    private val csCore by lazy { loadWords("cs_core_words.txt") }
    private val en by lazy { loadWords("en_common_words.txt") }

    @Test
    fun `production czech dictionary contains top-frequency words`() {
        // Slova, jejichž absence ve starém 35k slovníku rozbila lint.
        val mustHave = listOf("ale", "tady", "aby", "věc", "tráva", "svazek", "ten", "den", "dej", "všechno", "nechte", "zemřu", "zemřít")
        val missing = mustHave.filter { it !in cs }
        assertTrue("cs_common_words.txt postrádá běžná slova: $missing", missing.isEmpty())
    }

    @Test
    fun `previously hidden correct translations are not flagged`() {
        // Reálné dvojice z Vagabond ch.1, které lint se starým slovníkem označil
        // untranslated - po opravě musí projít.
        val cases = listOf(
            "JUST LEAVE ME HERE." to "Nechte mě tady.",
            "I'M GOING TO LAST MUCH LONGER.." to "Budu trvat mnohem déle.",
            "GIVE ME A BREAK." to "Dej mi přestávku.",
            "TAKEZO. IF I DIE." to "Takezo. Jestli zemřu.",
            "BUT..." to "Ale ...",
            "...BUT IT ALL CAME TO NOTHING.." to "... ale všechno přišlo k ničemu.",
            "ALL\nWE'VE HAD\nTO EAT IS\nGRASS.\nSURE" to "Jediné, co jsme museli jíst, je tráva. Ujistěte",
            "AND NOW WERE ON THE LOSING SIDE..." to "A teď byli na ztrátové straně ...",
            "THE\nREAL\nTHING!" to "Skutečná věc!",
            "Volume 3" to "Svazek 3",
            "ONCE WE FIND A VILLAGE WELL GET SOME MEDICINE." to "Jakmile najdeme vesnici dobře získat nějaký lék.",
        )
        val flagged = cases.filter { (src, tr) -> isSuspiciousCzechOutput(tr, src, cs, en, csCore) }
        assertTrue("lint pořád flaguje správné překlady: $flagged", flagged.isEmpty())
    }

    @Test
    fun `gibberish and english leaks still flag with real dictionaries`() {
        assertTrue(isSuspiciousCzechOutput("ZATÍŽETE.", "CRUSH THEM.", cs, en, csCore))
        // "nemysl" sedí v ocásku frekvenčního seznamu (typo v titulkovém korpusu,
        // rank ~231k) - slovník končí na 160k, takže zůstává flagovatelný.
        assertTrue(isSuspiciousCzechOutput("TO JSEM NEMYSL.", "THAT'S NOT WHAT I MEANT.", cs, en, csCore))
        // "enemy" je v ocásku CS slovníku (titulkový šum), ale ne v čistém jádru -
        // EN-leak flag proto pořád proběhne, i když je "ve slovníku".
        assertTrue(isSuspiciousCzechOutput("JSEM ENEMY.", "I'M AN ENEMY.", cs, en, csCore))
    }
}
