package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleClassifierTest {

    private fun rawBlock(
        text: String,
        shape: List<BubbleShapePoint>? = null,
        leftF: Float = 0.1f,
        topF: Float = 0.1f,
        rightF: Float = 0.2f,
        bottomF: Float = 0.15f,
        lineCount: Int = 1,
        bgUniform: Boolean = true,
    ) = RawTextBlock(
        text = text,
        leftF = leftF,
        topF = topF,
        rightF = rightF,
        bottomF = bottomF,
        shape = shape,
        lineCount = lineCount,
        bgUniform = bgUniform,
    )

    /** Trsovitý/hvězdicovitý obrys (24 vzorků, hroty/prohlubně střídající se každý vzorek) - viz [isJaggedShape]. */
    private fun jaggedShoutShape(): List<BubbleShapePoint> =
        (0 until 24).map { i ->
            val width = if (i % 2 == 0) 0.85f else 0.35f
            BubbleShapePoint(yF = i / 23f, leftF = 0.5f - width / 2f, rightF = 0.5f + width / 2f)
        }

    @Test
    fun `bare page or panel number is classified as sfx (noise, not dialogue)`() {
        val result = BubbleClassifier.classify(rawBlock("3"), lineCount = 1)
        assertTrue(result.isSfx)
        assertEquals(BubbleType.SFX, result.bubbleType)
    }

    @Test
    fun `multi-digit bare number is classified as sfx`() {
        val result = BubbleClassifier.classify(rawBlock("12"), lineCount = 1)
        assertTrue(result.isSfx)
    }

    @Test
    fun `number embedded in real dialogue is not sfx`() {
        val result = BubbleClassifier.classify(rawBlock("THERE'S A MEASLY 1800 YEN LEFT."), lineCount = 1)
        assertFalse(result.isSfx)
    }

    @Test
    fun `ellipsis only bubble is not treated as noise`() {
        val result = BubbleClassifier.classify(rawBlock("..."), lineCount = 1)
        assertFalse(result.isSfx)
    }

    @Test
    fun `real sfx word is still detected`() {
        val result = BubbleClassifier.classify(rawBlock("BOOM!!!"), lineCount = 1)
        assertTrue(result.isSfx)
    }

    @Test
    fun `jagged bubble shape is classified as shout even when text alone would not suggest it`() {
        // Text sam o sobe (male pismeno, bez vykricniku) by dal SPEECH - jagged obrys
        // (skutecna kresba, viz isJaggedShape) musi klasifikaci pretlacit na SHOUT.
        val result = BubbleClassifier.classify(rawBlock("Uz jdou", shape = jaggedShoutShape()), lineCount = 1)
        assertEquals(BubbleType.SHOUT, result.bubbleType)
    }

    @Test
    fun `smooth bubble shape does not force shout classification`() {
        val smoothShape = (0 until 24).map { i -> BubbleShapePoint(yF = i / 23f, leftF = 0.2f, rightF = 0.8f) }
        val result = BubbleClassifier.classify(rawBlock("Uz jdou", shape = smoothShape), lineCount = 1)
        assertEquals(BubbleType.SPEECH, result.bubbleType)
    }

    // ── krátká skutečná slova nesmí spadnout do SFX (viz uživatelská zpětná vazba -
    //    "DAMN..." zůstalo nepřeložené, protože SFX bublina se nikdy nevykresluje) ──

    @Test
    fun `short common interjection is not classified as sfx`() {
        val result = BubbleClassifier.classify(rawBlock("DAMN..."), lineCount = 1)
        assertFalse(result.isSfx)
    }

    @Test
    fun `short common word wait is not classified as sfx`() {
        val result = BubbleClassifier.classify(rawBlock("WAIT!"), lineCount = 1)
        assertFalse(result.isSfx)
    }

    @Test
    fun `short common word stop is not classified as sfx`() {
        val result = BubbleClassifier.classify(rawBlock("STOP"), lineCount = 1)
        assertFalse(result.isSfx)
    }

    // ── útržek věty pokračující do další bubliny není zvuk (viz uživatelská zpětná vazba -
    //    horní lalok kaskádové bubliny "...SAY," zůstal anglicky) ──

    @Test
    fun `the reported fragment of a cascading bubble is dialogue, not a sound effect`() {
        // JÁDRO NÁLEZU: horní lalok "sněhulákové" bubliny, spodní lalok byl přeložený.
        // Jako SFX se blok vůbec neposlal na překlad ani nevykreslil - zůstala angličtina.
        val result = BubbleClassifier.classify(rawBlock("...SAY,"), lineCount = 1)
        assertFalse("útržek věty se nesmí označit za zvuk", result.isSfx)
    }

    @Test
    fun `a short legit word drawn off-panel is not misclassified as sfx`() {
        // Pravidlo pro text mimo bublinu (!bgUniform) schválně nekontroluje samohlásky (chytá
        // i "BOOM"/"CRASH"), takže bez allowlistu by pohltilo i krátkou legitimní repliku
        // vysázenou mimo bublinu pro důraz (nahlášeno v auditu).
        listOf("HEY", "WAIT", "NO", "STOP", "GO").forEach { text ->
            val block = rawBlock(text, bgUniform = false)
            assertFalse("$text mimo bublinu nesmí být SFX", BubbleClassifier.classify(block, lineCount = 1).isSfx)
        }
    }

    @Test
    fun `a repeated instance of the same short sfx word is still recognized as sfx`() {
        // Živý nález: lettering kreslí "GULP GULP" jako dvě oddělené instance, ale OCR/spojení
        // řádků je slije do JEDNOHO bloku s mezerou uvnitř - ostatní pravidla (seznam i
        // bez-samohlásky) vyžadují text bez mezery, takže by tohle prošlo VŠEMI a přeložilo se
        // jako obyčejný text (živý dopad: "GULP GULP" -> "ZÁVĚS").
        assertTrue(BubbleClassifier.classify(rawBlock("GULP GULP"), lineCount = 1).isSfx)
        assertTrue(BubbleClassifier.classify(rawBlock("BOOM BOOM BOOM"), lineCount = 1).isSfx)
        assertTrue(BubbleClassifier.classify(rawBlock("gulp gulp"), lineCount = 1).isSfx)
    }

    @Test
    fun `a comma at the end used to slip past the safety list entirely`() {
        // Druhá, širší polovina nálezu: "core" se ořezávalo jen o !?. a mezeru, takže do
        // porovnání se seznamem šlo "WAIT," a to se nikdy netrefilo. I slova, která seznam
        // VÝSLOVNĚ chrání, tak propadla mezi zvuky.
        listOf("WAIT,", "DAMN,", "NO,", "HEY,", "STOP,").forEach { text ->
            assertFalse("$text musí zůstat replikou", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `a leading ellipsis marks a continuation from the previous bubble`() {
        assertFalse(BubbleClassifier.classify(rawBlock("...SAY"), lineCount = 1).isSfx)
        assertFalse(BubbleClassifier.classify(rawBlock("…TAKE"), lineCount = 1).isSfx)
    }

    @Test
    fun `a trailing tilde is stripped before the safety list is consulted`() {
        // "AH~" je v manhwě běžné - vlnovka se dřív nepočítala mezi ořezávanou interpunkci,
        // takže se porovnávalo "AH~" a slovo ze seznamu se minulo.
        assertFalse(BubbleClassifier.classify(rawBlock("AH~"), lineCount = 1).isSfx)
    }

    @Test
    fun `a real sound effect with a comma is still a sound effect`() {
        // Pojistka proti přestřelení: čárka nesmí zachránit skutečný zvuk, ten se pozná
        // podle slova samotného (sfxWords), ne podle interpunkce.
        assertTrue(BubbleClassifier.classify(rawBlock("BOOM,"), lineCount = 1).isSfx)
        assertTrue(BubbleClassifier.classify(rawBlock("CRASH,"), lineCount = 1).isSfx)
    }

    @Test
    fun `an unknown short all-caps noise is still caught when nothing suggests a sentence`() {
        // Druhá pojistka: pravidlo o krátkém ALL CAPS textu musí dál fungovat tam, kde
        // opravdu jde o zvuk - jinak by oprava jen prohodila jednu chybu za druhou.
        assertTrue(BubbleClassifier.classify(rawBlock("KRRR"), lineCount = 1).isSfx)
        assertTrue(BubbleClassifier.classify(rawBlock("SHNK!!"), lineCount = 1).isSfx)
    }

    @Test
    fun `say alone is dialogue`() {
        assertFalse(BubbleClassifier.classify(rawBlock("SAY"), lineCount = 1).isSfx)
    }

    @Test
    fun `real sfx word boom is still detected even though it is short and all caps`() {
        // Sanity - vyjimka pro bezna slova nesmi rozbit skutecne SFX detekce.
        val result = BubbleClassifier.classify(rawBlock("BOOM"), lineCount = 1)
        assertTrue(result.isSfx)
    }

    // ── vodoznak scanlation skupiny (viz uzivatelska zpetna vazba - cerna skvrna pres kresbu) ──

    @Test
    fun `scanlation domain watermark is classified as sfx`() {
        val result = BubbleClassifier.classify(rawBlock("SIRENSCANS.COM"), lineCount = 1)
        assertTrue(result.isSfx)
    }

    @Test
    fun `watermark read letter by letter with spaces is still detected as domain`() {
        val result = BubbleClassifier.classify(rawBlock("E N S C A N S . C O M"), lineCount = 8)
        assertTrue(result.isSfx)
    }

    @Test
    fun `very tall narrow block with many merged lines is treated as decorative watermark`() {
        val result = BubbleClassifier.classify(
            rawBlock("some vertical text", leftF = 0.5f, topF = 0.1f, rightF = 0.52f, bottomF = 0.9f, lineCount = 10),
            lineCount = 10,
        )
        assertTrue(result.isSfx)
    }

    @Test
    fun `normal long narration block is not mistaken for a watermark`() {
        val result = BubbleClassifier.classify(
            rawBlock(
                "This is a perfectly normal long narration line that spans the width of the panel comfortably.",
                leftF = 0.05f, topF = 0.1f, rightF = 0.95f, bottomF = 0.30f, lineCount = 4,
            ),
            lineCount = 4,
        )
        assertFalse(result.isSfx)
    }

    @Test
    fun `dialogue containing the word scan as a normal sentence is not a watermark`() {
        val result = BubbleClassifier.classify(rawBlock("Let me scan the area first."), lineCount = 1)
        assertFalse(result.isSfx)
    }

    // ── opakovaný dlaždicovaný vodoznak napříč stránkou (viz [BubbleClassifier.classifyPage]) ──

    @Test
    fun `reproduces the reported case - garbled tiled group name scattered across a page`() {
        // Presny scenar z uzivatelske zpetne vazby: pet samostatnych bloku, zadny sam o sobe
        // nesplnuje existujici pravidla (MADRASCANS je moc dlouhe na kratke-ALL-CAPS pravidlo
        // [>6 pismen], "MAD ANS" a merged blok obsahuji mezeru), ale napric strankou tvori
        // jasny vzorec opakovaneho jmena skenlacni skupiny.
        val blocks = listOf(
            rawBlock("MADRASCANS MADRASCANS", leftF = 0.1f, topF = 0.05f, rightF = 0.4f, bottomF = 0.10f, lineCount = 2),
            rawBlock("MAD ANS", leftF = 0.5f, topF = 0.20f, rightF = 0.7f, bottomF = 0.23f),
            rawBlock("4ANS", leftF = 0.6f, topF = 0.40f, rightF = 0.75f, bottomF = 0.43f),
            rawBlock("MADRASCANS", leftF = 0.2f, topF = 0.60f, rightF = 0.4f, bottomF = 0.63f),
            rawBlock("MADRASCANS", leftF = 0.3f, topF = 0.80f, rightF = 0.5f, bottomF = 0.83f),
            // Skutecna replika na te same strance - nesmi se chytit do shluku.
            rawBlock("Wait, is someone there?", leftF = 0.1f, topF = 0.5f, rightF = 0.6f, bottomF = 0.55f),
        )

        val classified = BubbleClassifier.classifyPage(blocks)

        assertTrue("MADRASCANS MADRASCANS should be flagged as watermark", classified[0].isSfx)
        assertTrue("MAD ANS should be flagged as watermark", classified[1].isSfx)
        assertTrue("MADRASCANS (index 3) should be flagged as watermark", classified[3].isSfx)
        assertTrue("MADRASCANS (index 4) should be flagged as watermark", classified[4].isSfx)
        assertFalse("real dialogue must not be swept into the watermark cluster", classified[5].isSfx)
    }

    @Test
    fun `a short repeated word alone does not form a false-positive cluster`() {
        // "MAS" je jen 3 znaky (pod WATERMARK_MIN_OVERLAP_CHARS) - klasifikuje se (nebo ne)
        // podle existujicich pravidel, ne podle noveho shlukovani.
        val blocks = List(3) { rawBlock("MAS", leftF = 0.1f * it, topF = 0.1f * it, rightF = 0.2f + 0.1f * it, bottomF = 0.15f + 0.1f * it) }
        val indices = BubbleClassifier.detectTiledWatermarkIndices(blocks)
        assertTrue("too short to be confidently clustered", indices.isEmpty())
    }

    @Test
    fun `a name repeated identically several times is not treated as a watermark`() {
        // Postava rekne "BAXTER" trikrat - VSECHNY vyskyty jsou bajt-po-bajtu stejne, zadna
        // odchylka. Vodoznak se pozna prave podle toho, ze se cte KAZDYKRAT JINAK (ruzne
        // zkomoleniny), ne podle toho, ze se holt opakuje stejne slovo.
        val blocks = List(3) { i ->
            rawBlock("BAXTER", leftF = 0.1f, topF = 0.1f + 0.2f * i, rightF = 0.4f, bottomF = 0.15f + 0.2f * i)
        }
        val indices = BubbleClassifier.detectTiledWatermarkIndices(blocks)
        assertTrue("identical repeats alone must not be flagged - could be a real repeated name", indices.isEmpty())
    }

    @Test
    fun `two occurrences alone are below the cluster threshold`() {
        val blocks = listOf(
            rawBlock("MADRASCANS", leftF = 0.1f, topF = 0.1f, rightF = 0.4f, bottomF = 0.15f),
            rawBlock("MAD ANS", leftF = 0.1f, topF = 0.5f, rightF = 0.4f, bottomF = 0.55f),
        )
        assertTrue(BubbleClassifier.detectTiledWatermarkIndices(blocks).isEmpty())
    }

    @Test
    fun `a long genuine narration block is never eligible for clustering regardless of coincidental overlap`() {
        val blocks = listOf(
            rawBlock("MADRASCANS", leftF = 0.1f, topF = 0.05f, rightF = 0.4f, bottomF = 0.10f),
            rawBlock("MAD ANS", leftF = 0.1f, topF = 0.20f, rightF = 0.4f, bottomF = 0.23f),
            rawBlock("4ANS", leftF = 0.1f, topF = 0.40f, rightF = 0.4f, bottomF = 0.43f),
            // Dlouha veta, ktera by nahodou mohla obsahovat MADRASCANS jako podposloupnost,
            // kdyby normalizace nemela strop delky - musi zustat mimo shluk.
            rawBlock(
                "My animal draconic sensory abilities notice a scan of narrative sequences approaching us.",
                leftF = 0.05f, topF = 0.6f, rightF = 0.95f, bottomF = 0.7f, lineCount = 3,
            ),
        )
        val indices = BubbleClassifier.detectTiledWatermarkIndices(blocks)
        assertTrue("the long narration block must never be swept into the cluster", 3 !in indices)
    }

    @Test
    fun `a short spanish word is not swallowed as a sound effect`() {
        // NALEZ Z AUDITU: pravidlo "kratky text velkymi pismeny bez mezer = zvuk" melo jako
        // jedinou pojistku ANGLICKY seznam beznych slov, takze u spanelskeho/francouzskeho
        // komiksu platilo bez site. Pravidlo uz neexistuje a klasifikace na zdrojovem jazyce
        // vubec nezavisi - drzi to samo od sebe, ne kvuli vyjimce.
        assertFalse("spanelska replika nesmi propadnout jako zvuk", BubbleClassifier.classify(rawBlock("VAMOS"), 1).isSfx)
        assertFalse(BubbleClassifier.classify(rawBlock("ALORS"), 1).isSfx)
    }

    @Test
    fun `english short words keep their existing protection`() {
        val stop = BubbleClassifier.classify(rawBlock("STOP"), lineCount = 1)
        assertFalse(stop.isSfx)
    }

    @Test
    fun `a real sound effect is still caught whatever the source language`() {
        // sfxWords je vyslovny seznam - ten plati porad a na jazyce nezavisi.
        assertTrue(BubbleClassifier.classify(rawBlock("BOOM"), 1).isSfx)
        assertTrue(BubbleClassifier.classify(rawBlock("CRASH"), 1).isSfx)
    }

    @Test
    fun `dialogue that simply gets extended is not a watermark cluster`() {
        // NALEZ Z AUDITU: tri repliky, kde kazda jen prodluzuje tu predchozi, se shlukly do
        // "vodoznaku" a VSECHNY se oznacily jako SFX - tedy se vubec neprelozily a na strance
        // zustal original. "HELP / HELP ME / HELP ME NOW" neni v akcni scene nic vyjimecneho.
        //
        // Rozdil oproti skutecnemu vodoznaku: tady je kratsi text SOUVISLYM usekem toho
        // delsiho (proste pokracovani vety). Vodoznak precteny OCR pokazde jinak ma naopak
        // uvnitr DIRY - vypadla nebo zamenena pismena, viz test s MADRASCANS vys.
        val blocks = listOf(
            rawBlock("HELP", leftF = 0.1f, topF = 0.05f, rightF = 0.3f, bottomF = 0.10f),
            rawBlock("HELP ME", leftF = 0.1f, topF = 0.30f, rightF = 0.4f, bottomF = 0.35f),
            rawBlock("HELP ME NOW", leftF = 0.1f, topF = 0.60f, rightF = 0.5f, bottomF = 0.65f),
        )
        val indices = BubbleClassifier.detectTiledWatermarkIndices(blocks)
        assertTrue("postupne prodluzovana replika neni vodoznak, bylo $indices", indices.isEmpty())
    }

    @Test
    fun `a name with different honorifics attached is not a watermark cluster`() {
        val blocks = listOf(
            rawBlock("NARUTO", leftF = 0.1f, topF = 0.05f, rightF = 0.3f, bottomF = 0.10f),
            rawBlock("NARUTO KUN", leftF = 0.1f, topF = 0.30f, rightF = 0.4f, bottomF = 0.35f),
            rawBlock("NARUTO SAN", leftF = 0.1f, topF = 0.60f, rightF = 0.4f, bottomF = 0.65f),
        )
        val indices = BubbleClassifier.detectTiledWatermarkIndices(blocks)
        assertTrue("jmeno s ruznymi priponami neni vodoznak, bylo $indices", indices.isEmpty())
    }

    @Test
    fun `classifyPage leaves non-watermark blocks with their normal classification untouched`() {
        val blocks = listOf(
            rawBlock("MADRASCANS", leftF = 0.1f, topF = 0.05f, rightF = 0.4f, bottomF = 0.10f),
            rawBlock("MAD ANS", leftF = 0.1f, topF = 0.20f, rightF = 0.4f, bottomF = 0.23f),
            rawBlock("4ANS", leftF = 0.1f, topF = 0.40f, rightF = 0.4f, bottomF = 0.43f),
            rawBlock("BOOM!!!", leftF = 0.1f, topF = 0.6f, rightF = 0.3f, bottomF = 0.65f),
        )
        val classified = BubbleClassifier.classifyPage(blocks)
        // BOOM!!! je uz beztak SFX z existujiciho pravidla, ne z noveho shlukovani - jen sanity,
        // ze classifyPage normalni klasifikaci vubec nerozbije.
        assertTrue(classified[3].isSfx)
        assertEquals(BubbleType.SFX, classified[3].bubbleType)
    }

    // ── replika polknutá pravidlem "krátké velké písmo = zvuk" (nález z Vagabonda) ──

    @Test
    fun `reproduces the reported bubble - only the middle line came back translated`() {
        // JÁDRO NÁLEZU. Jedna bublina "I / SURVIVED, / TOO..." se rozpadla na tři bloky a
        // v překladu vyšla jako "I / PŘEŽÍT, / TOO..." - prostřední kus byl dost dlouhý na to,
        // aby prošel, krajní dva propadly jako "zvuk", takže se ani neposlaly na překlad, ani
        // nevykreslily. Anglický lettering v nich zůstal.
        listOf("I", "TOO...", "SURVIVED,").forEach { text ->
            assertFalse("„$text\" je replika, ne zvuk", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `a name is dialogue even though it is short and has no vowel-free giveaway`() {
        // "TAKEZŌ." - vlastní jméno v samostatné bublině, tedy případ, kde slučování řádků
        // nehraje roli vůbec. Šest písmen bez mezery: staré pravidlo ho spolklo.
        assertFalse(BubbleClassifier.classify(rawBlock("TAKEZŌ."), 1).isSfx)
    }

    @Test
    fun `the safety list is no longer what decides - unlisted short words survive too`() {
        // Tohle je ten podstatný rozdíl proti dřívějším opravám. Dvakrát se to řešilo tak, že
        // se do seznamu chráněných slov dopsalo další slovo ("DAMN", pak "SAY"). Seznam běžných
        // slov je ale nekonečná množina, takže třetí nález byl jen otázkou času. Žádné z těchhle
        // slov v seznamu nikdy nebylo.
        listOf("TOO", "BOTH", "MINE", "OURS", "THEIRS", "ALIVE", "DEAD", "GONE").forEach { text ->
            assertFalse("„$text\" nikdy nebyl v seznamu a přesto musí projít", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `a sound effect without a single vowel is still caught without any word list`() {
        // Náhrada za zrušené pravidlo o velkých písmenech: skutečné slovo (v jakémkoli jazyce
        // psaném latinkou) má samohlásku, mechanický zvuk často ne. Na rozdíl od velikosti
        // písmen tohle v komiksu, kde je VŠECHNO verzálkami, opravdu něco rozlišuje.
        listOf("KRRR", "SHNK!!", "TSK", "GRR", "PSST", "HMPH").forEach { text ->
            assertTrue("„$text\" nemá samohlásku, je to zvuk", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `the vowel rule only applies to latin script - CJK dialogue must not be swallowed`() {
        // Japonská/korejská replika nemá latinskou samohlásku ŽÁDNOU, takže bez tohohle omezení
        // by pravidlo výš spolklo úplně obyčejný dialog.
        listOf("はい", "そうか", "네", "알았어").forEach { text ->
            assertFalse("„$text\" je dialog, ne zvuk", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `a stretched sound effect is recognised as the sound it stretches`() {
        // Lettering zvuky protahuje ("SOBB", "BOOOM") - porovnání na přesnou shodu je proto
        // míjelo a chytalo je až zrušené pravidlo o verzálkách.
        listOf("SOBB", "BOOOM", "CRASHH", "HUFF").forEach { text ->
            assertTrue("protažené „$text\" je pořád zvuk", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `common comic sounds that used to rely on the all-caps rule are named explicitly now`() {
        listOf("SOB", "SNIF", "HUF", "HAK", "PANT", "ARGH").forEach { text ->
            assertTrue("„$text\" je zvuk", BubbleClassifier.classify(rawBlock(text), 1).isSfx)
        }
    }

    @Test
    fun `a short word drawn straight onto the artwork is a sound effect`() {
        // Druhý nezávislý signál zvuku: zvuk se sází PŘES KRESBU, replika do bubliny. Tohle
        // chytá i zvuky, které v seznamu nejsou a samohlásku mají.
        val onArtwork = BubbleClassifier.classify(rawBlock("ZWISH", bgUniform = false), 1)
        assertTrue("krátký text na kresbě je zvuk", onArtwork.isSfx)

        val inBubble = BubbleClassifier.classify(rawBlock("ZWISH", bgUniform = true), 1)
        assertFalse("stejný text v bublině zvuk není", inBubble.isSfx)
    }

    @Test
    fun `a whole sentence on the artwork stays dialogue`() {
        // Pojistka proti přestřelení pravidla výš: caption bez bubliny je běžná, a dlouhý text
        // zvuk nikdy není.
        val result = BubbleClassifier.classify(rawBlock("MĚLI JSME JEN TRÁVU K JÍDLU.", bgUniform = false), 1)
        assertFalse(result.isSfx)
    }
}
