package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Čistý JVM test [GeminiUltraPrompt.buildSystemPrompt] - žádná síťová/Android závislost. */
class GeminiUltraPromptTest {

    @Test
    fun `includes manga context when provided`() {
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap(), mangaContext = "Název: \"Test Manga\" (manga), žánry: Akce")
        assertTrue(prompt.contains("Test Manga"))
        assertTrue(prompt.contains("Akce"))
    }

    @Test
    fun `falls back to placeholder when manga context is blank`() {
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap(), mangaContext = "")
        assertTrue(prompt.contains("neznámé"))
    }

    @Test
    fun `size limits in prompt text always match the SizeTag enum, never hardcoded`() {
        // Regrese proti budoucímu rozjetí prompt-textu a skutečných hodnot v SizeTag - obojí
        // musí jít ze STEJNÉHO zdroje (viz interpolace v buildSystemPrompt).
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertTrue(prompt.contains("max ${SizeTag.TINY.maxChars} znaků"))
        assertTrue(prompt.contains("max ${SizeTag.SMALL.maxChars} znaků"))
        assertTrue(prompt.contains("max ${SizeTag.MEDIUM.maxChars} znaků"))
        assertTrue(prompt.contains("max ${SizeTag.LARGE.maxChars} znaků"))
        assertTrue(prompt.contains("max ${SizeTag.WIDE.maxChars} znaků"))
        assertTrue(prompt.contains("max ${SizeTag.TALL.maxChars} znaků"))
    }

    @Test
    fun `references the untranslated marker constant, not a duplicated literal`() {
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertTrue(prompt.contains(GeminiUltraPrompt.UNTRANSLATED_MARKER))
    }

    @Test
    fun `the untranslated marker is reserved for unreadable text, never for a short fragment`() {
        // Uzivatelska zpetna vazba: horni lalok kaskadove bubliny ("...SAY,") zustal anglicky.
        // Prompt si protirecil - sekce o vetach pres vic bublin zakazuje nechat bublinu
        // prazdnou, ale sekce CHYBY uvadela "utrzek" jako duvod pro marker. A horni lalok
        // JE utrzek, takze model poslusne vratil marker a appka bublinu vubec nevykreslila.
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertFalse(
            "utrzek nesmi byt uvedeny jako duvod pro ${GeminiUltraPrompt.UNTRANSLATED_MARKER}",
            prompt.contains("nečitelné OCR, útržek"),
        )
        assertTrue("marker musi byt vyhrazeny necitelnemu textu", prompt.contains("nedá PŘEČÍST"))
        assertTrue("prompt musi vyslovne zakazat marker u kratke bubliny", prompt.contains("NEVRACEJ"))
    }

    @Test
    fun `glossary entries are included verbatim`() {
        val prompt = GeminiUltraPrompt.buildSystemPrompt(mapOf("Gravity Magic" to "Magie tíže"))
        assertTrue(prompt.contains("\"Gravity Magic\" -> \"Magie tíže\""))
    }

    @Test
    fun `empty glossary does not crash and shows the empty-glossary note`() {
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertFalse(prompt.isBlank())
        assertTrue(prompt.contains("žádné zatím uložené pojmy"))
    }

    @Test
    fun `warns against literal word-for-word translation of idioms`() {
        // Uzivatelska zpetna vazba: "coming all this way" prelozeno doslovne ("po tom, co jsme
        // se sem vydali") ztratilo duraz na delku/namahu cesty, ktery idiom nese.
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertTrue(prompt.contains("coming all this way"))
        assertTrue(prompt.contains("IDIOMY"))
    }

    @Test
    fun `warns against the non-standard reflexive verb combination`() {
        // Uzivatelska zpetna vazba: "zbloudit se" je negramaticke - "zbloudit" uz zvratnost
        // vyjadruje samo, pridane "se" mixuje dva vzory.
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertTrue(prompt.contains("zbloudit"))
        assertTrue(prompt.contains("ZVRATNÁ SLOVESA"))
    }

    @Test
    fun `parses new_terms from the response`() {
        val json = """
            {
              "bubbles": [
                {"id": 0, "original": "Hi Frodo", "translated": "Ahoj Frodo", "bubble_size_tag": "SMALL", "is_sfx": false, "syllable_breaks": "Ahoj Frodo"}
              ],
              "new_terms": [
                {"source": "Frodo", "target": "Frodo"},
                {"source": "Gravity Magic", "target": "Magie tíže"}
              ]
            }
        """.trimIndent()

        val response = GeminiUltraPrompt.parseResponse(json)

        assertEquals(2, response.newTerms.size)
        assertEquals(GlossarySuggestion("Frodo", "Frodo"), response.newTerms[0])
        assertEquals(GlossarySuggestion("Gravity Magic", "Magie tíže"), response.newTerms[1])
    }

    @Test
    fun `a response that starts with prose still parses`() {
        // JÁDRO NÁLEZU: naměřeno na zařízení. Groq vrátil odpověď zacinajici textem
        // "Překlady…" a appka celou dávku zahodila na JSONException - včetně znaků,
        // které za to volání upstream odečetl.
        val json = """
            Překlady bublin do češtiny:

            {"bubbles": [{"id": 0, "original": "Hi", "translated": "Ahoj", "bubble_size_tag": "TINY", "is_sfx": false, "syllable_breaks": "Ahoj"}]}
        """.trimIndent()

        val response = GeminiUltraPrompt.parseResponse(json)

        assertEquals("Ahoj", response.bubbles.single().translated)
    }

    @Test
    fun `a response wrapped in a markdown fence still parses`() {
        val json = "```json\n{\"bubbles\": [{\"id\": 0, \"original\": \"Hi\", \"translated\": \"Ahoj\", \"bubble_size_tag\": \"TINY\", \"is_sfx\": false, \"syllable_breaks\": \"Ahoj\"}]}\n```"

        assertEquals("Ahoj", GeminiUltraPrompt.parseResponse(json).bubbles.single().translated)
    }

    @Test
    fun `trailing commentary after the JSON is ignored`() {
        val json = "{\"bubbles\": []}\n\nDoufám, že překlad pomůže!"

        assertTrue(GeminiUltraPrompt.parseResponse(json).bubbles.isEmpty())
    }

    @Test
    fun `a response with no JSON at all keeps its text for the error message`() {
        // Prázdný řetězec by z výjimky udělal hádanku - takhle je v ní vidět, co model poslal.
        assertEquals("Omlouvám se, nemohu pomoci.", GeminiUltraPrompt.extractJsonObject("  Omlouvám se, nemohu pomoci.  "))
    }

    @Test
    fun `missing new_terms field parses as empty list, not a crash`() {
        val json = """{"bubbles": [{"id": 0, "original": "Hi", "translated": "Ahoj", "bubble_size_tag": "TINY", "is_sfx": false, "syllable_breaks": "Ahoj"}]}"""
        val response = GeminiUltraPrompt.parseResponse(json)
        assertTrue(response.newTerms.isEmpty())
    }

    @Test
    fun `new_terms entries with blank source or target are skipped`() {
        val json = """
            {
              "bubbles": [],
              "new_terms": [
                {"source": "", "target": "Something"},
                {"source": "Valid", "target": ""},
                {"source": "Frodo", "target": "Frodo"}
              ]
            }
        """.trimIndent()
        val response = GeminiUltraPrompt.parseResponse(json)
        assertEquals(1, response.newTerms.size)
        assertEquals("Frodo", response.newTerms[0].source)
    }

    // ── Věty rozdělené do víc bublin ────────────────────────────────────────────

    private fun bubble(
        text: String,
        topF: Float,
        bottomF: Float,
        leftF: Float = 0.1f,
        rightF: Float = 0.5f,
        isSfx: Boolean = false,
    ) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = leftF, topF = topF, rightF = rightF, bottomF = bottomF),
        sizeTag = SizeTag.SMALL,
        bubbleType = if (isSfx) BubbleType.SFX else BubbleType.SPEECH,
        isSfx = isSfx,
        lineCount = 1,
    )

    @Test
    fun `a continued bubble is marked so the model knows the sentence carries over`() {
        // Bez tehle znacky mel model jen plochy seznam textu a nemel jak poznat, ze dve
        // bubliny tvori jednu repliku - preklad se pak rozpadl na dva samostatne utrzky.
        val prompt = GeminiUltraPrompt.buildUserPrompt(
            listOf(
                bubble("PROBOHA,", topF = 0.10f, bottomF = 0.18f),
                bubble("TAKOVA DALKA", topF = 0.20f, bottomF = 0.30f),
            ),
        )
        assertTrue("druha bublina ma byt oznacena jako pokracovani", prompt.contains("POKRAČUJE Z: [BUBBLE 0]"))
    }

    @Test
    fun `unrelated bubbles carry no continuation marker`() {
        val prompt = GeminiUltraPrompt.buildUserPrompt(
            listOf(
                bubble("HOTOVO.", topF = 0.10f, bottomF = 0.18f),
                bubble("KAM JDEŠ?", topF = 0.20f, bottomF = 0.30f),
            ),
        )
        assertFalse("ukoncena veta nepokracuje", prompt.contains("POKRAČUJE Z"))
    }

    @Test
    fun `the system prompt forbids moving text between bubbles`() {
        val prompt = GeminiUltraPrompt.buildSystemPrompt(emptyMap())
        assertTrue(prompt.contains("VĚTY PŘES VÍC BUBLIN"))
        assertTrue("musi zakazat presouvani textu", prompt.contains("NIKDY nepřesouvá"))
        assertTrue("musi zakazat slucovani bublin", prompt.contains("nesluč"))
    }

    @Test
    fun `previously translated lines are passed as context, clearly marked as not for translating`() {
        // Bez toho varování model tenhle blok bere jako součást zadání a vrátí ho v odpovědi,
        // což rozhodí párování bublin podle id.
        val prompt = GeminiUltraPrompt.buildUserPrompt(
            listOf(bubble("WHERE ARE YOU GOING?", topF = 0.1f, bottomF = 0.2f)),
            previousLines = listOf("Musíme pryč.", "Počkej na mě."),
        )

        assertTrue("kontext musí být v promptu", prompt.contains("Musíme pryč."))
        assertTrue("musí zakázat opětovný překlad", prompt.contains("NEPŘEKLÁDEJ je znovu"))
        assertTrue("bubliny k překladu musí zůstat oddělené", prompt.contains("=== BUBLINY ==="))
    }

    @Test
    fun `without previous lines the prompt has no context section at all`() {
        // Začátek kapitoly. Prázdná sekce by jen ubírala z rozpočtu znaků.
        val prompt = GeminiUltraPrompt.buildUserPrompt(listOf(bubble("HELLO", topF = 0.1f, bottomF = 0.2f)))

        assertFalse(prompt.contains("CO UŽ ZAZNĚLO"))
    }

    @Test
    fun `the context tail keeps the most recent lines, in their original order`() {
        val previous = listOf("první", "druhá", "třetí", "čtvrtá")

        val tail = GeminiUltraPrompt.recentContextLines(previous, maxLines = 2, maxChars = 1000)

        assertEquals("musí brát od konce, ale neobracet pořadí", listOf("třetí", "čtvrtá"), tail)
    }

    @Test
    fun `a long monologue cannot eat the whole context budget`() {
        // JÁDRO dvojího rozpočtu: samotný limit počtu řádků by tuhle dávku pustil celou.
        val previous = listOf("x".repeat(300), "krátká")

        val tail = GeminiUltraPrompt.recentContextLines(previous, maxLines = 6, maxChars = 100)

        assertEquals(listOf("krátká"), tail)
    }

    @Test
    fun `blank lines never reach the prompt`() {
        val tail = GeminiUltraPrompt.recentContextLines(listOf("ahoj", "   ", ""))

        assertEquals(listOf("ahoj"), tail)
    }

    @Test
    fun `the medium rules really reach the context block, not just exist`() {
        // Pojistka proti odpojení: samotná pravidla se testují níž, ale bez tohohle testu by
        // šlo jejich zapojení do kontextu smazat a nikdo by si toho nevšiml.
        val context = GeminiUltraPrompt.buildMangaContext("Solo Leveling", "MANHWA", listOf("Akce"))

        assertTrue("název i žánr", context.contains("Solo Leveling") && context.contains("Akce"))
        assertTrue("a hlavně pravidla pro manhwu", context.contains("Korejské"))
    }

    @Test
    fun `a work with an unknown content type still gets its title and genres`() {
        val context = GeminiUltraPrompt.buildMangaContext("Něco", "NECO_NOVEHO", listOf("Drama"))

        assertTrue(context.contains("Něco"))
        assertTrue(context.contains("Drama"))
    }

    @Test
    fun `manhwa is told it is Korean, not Japanese`() {
        // JÁDRO: typ díla se posílal jen jako nálepka v závorce a model si musel domyslet,
        // co z ní plyne. U manhwy si domyslel japonská oslovení - "hyung" není "senpai".
        val rules = GeminiUltraPrompt.mediumRules("MANHWA")

        assertTrue("musí říct, že jde o korejské dílo", rules.contains("Korejské"))
        assertTrue("musí korejská oslovení vyjmenovat", rules.contains("hyung"))
        assertFalse("nesmí manhwě podsouvat japonská honorifika", rules.contains("senpai"))
    }

    @Test
    fun `each medium gets rules that name its own tradition`() {
        assertTrue(GeminiUltraPrompt.mediumRules("MANGA").contains("Japonské"))
        assertTrue(GeminiUltraPrompt.mediumRules("MANHUA").contains("Čínské"))
        assertTrue(GeminiUltraPrompt.mediumRules("COMIC").contains("Západní"))
        assertTrue("u novely nejsou bubliny", GeminiUltraPrompt.mediumRules("NOVEL").contains("Próza"))
    }

    @Test
    fun `an unknown content type gets no rules at all`() {
        // Radši žádné pravidlo než špatné: starý záznam v databázi typ mít nemusí a odhad
        // "asi manga" by u manhwy uškodil právě tím, co se tu opravuje.
        assertEquals("", GeminiUltraPrompt.mediumRules(""))
        assertEquals("", GeminiUltraPrompt.mediumRules("NECO_NOVEHO"))
    }

    @Test
    fun `the content type is matched regardless of case and padding`() {
        // Do databáze se typ dostává z různých zdrojů a nikdo nezaručuje tvar.
        assertEquals(GeminiUltraPrompt.mediumRules("MANHWA"), GeminiUltraPrompt.mediumRules(" manhwa "))
    }

    @Test
    fun `the rules stay short, because they are paid for on every single request`() {
        // Systémový prompt jde s KAŽDÝM požadavkem a znakový limit je náš skutečný strop.
        // Bez tyhle hlídky se sem pravidla časem nabalí a ubírají kvótu na vlastní překlad.
        for (type in listOf("MANGA", "MANHWA", "MANHUA", "COMIC", "NOVEL")) {
            val length = GeminiUltraPrompt.mediumRules(type).length
            assertTrue("$type má $length znaků, což je nad rozpočtem", length <= 260)
        }
    }

    // ── demographicToneRule (žánrové tón-pravidlo, viz mediumRules pro původ vs. tohle pro cílovku) ──

    @Test
    fun `shounen genre gets action-oriented tone guidance`() {
        val rule = GeminiUltraPrompt.demographicToneRule(listOf("Action", "Shounen"))
        assertTrue(rule.contains("shónen") || rule.contains("Shónen"))
    }

    @Test
    fun `shoujo genre gets emotional tone guidance, not shounen`() {
        val rule = GeminiUltraPrompt.demographicToneRule(listOf("Romance", "Shoujo"))
        assertTrue(rule.contains("šódžo") || rule.contains("Šódžo"))
        assertFalse(rule.contains("shónen"))
    }

    @Test
    fun `seinen and josei get distinct adult-oriented guidance`() {
        assertTrue(GeminiUltraPrompt.demographicToneRule(listOf("Seinen")).contains("seinen"))
        assertTrue(GeminiUltraPrompt.demographicToneRule(listOf("Josei")).contains("džosei"))
    }

    @Test
    fun `matching is case insensitive and tolerant of surrounding tag text`() {
        // Zdroje formátují štítky různě ("shounen", "Shounen manga", "SHOUNEN"...).
        assertTrue(GeminiUltraPrompt.demographicToneRule(listOf("shounen manga")).contains("shónen"))
        assertTrue(GeminiUltraPrompt.demographicToneRule(listOf("SEINEN")).contains("seinen"))
    }

    @Test
    fun `no demographic tag among the genres is a safe no-op`() {
        assertEquals("", GeminiUltraPrompt.demographicToneRule(listOf("Action", "Isekai")))
        assertEquals("", GeminiUltraPrompt.demographicToneRule(emptyList()))
    }

    @Test
    fun `the demographic tone rule reaches the context block`() {
        // Stejná pojistka proti odpojení jako u mediumRules výš.
        val context = GeminiUltraPrompt.buildMangaContext("Berserk", "MANGA", listOf("Seinen"))
        assertTrue(context.contains("seinen"))
    }
}
