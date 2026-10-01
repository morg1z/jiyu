package com.haise.jiyu.translate

import com.haise.jiyu.data.db.entity.GlossaryEntity

/**
 * Nahrazení "protectExact" glosářových pojmů neprůhlednými tokeny před odesláním k překladu
 * a jejich obnova po odpovědi - viz [GlossaryEntity.protectExact] a
 * [TranslateRepository]. Na rozdíl od běžné promptové injekce (viz
 * [GeminiUltraPrompt.buildSystemPrompt] glosářový blok - jen "doporučení", které model může
 * ohnout/přeložit jinak), substituce fyzicky nahradí text pojmu tokenem, který model nemá
 * důvod překládat - garance je tak na úrovni řetězce, ne na vůli modelu se řídit instrukcí.
 *
 * Token je JEDEN na POJEM (ne na výskyt) - stejný token se objeví ve VŠECH bublinách dávky,
 * které daný pojem obsahují, což je žádoucí: model vidí konzistentně stejný marker.
 *
 * [restoreResponse] obnoví tokeny HNED po přijetí odpovědi - v poli "original" (echo, viz
 * [GeminiBubbleTranslation.original]) zpátky na SKUTEČNÝ zdrojový pojem, v poli "translated"
 * na cílový. Volající ([TranslateRepository]) pak dál pracuje s [ClassifiedBubble]/
 * [GeminiTranslationResponse], jako by substituce vůbec neproběhla - [originalMatches],
 * [isSuspiciousVerbatimCopy] a [retryIndicesWithQualityChecks] tak porovnávají SKUTEČNÝ
 * text na obou stranách, ne token. Substituovaná (`.classified`) verze bublin se používá
 * VÝHRADNĚ pro samotný odchozí request, nikde jinde.
 */
internal object GlossaryPlaceholders {

    data class Substitution(
        val classified: List<ClassifiedBubble>,
        private val sourceRestoreTerms: List<String>,
        private val targetRestoreTerms: List<String>,
    ) {
        val isNoop: Boolean get() = sourceRestoreTerms.isEmpty() && targetRestoreTerms.isEmpty()

        fun restoreResponse(response: GeminiTranslationResponse): GeminiTranslationResponse {
            if (isNoop) return response
            return response.copy(
                bubbles = response.bubbles.map { b ->
                    b.copy(
                        original = restoreTokens(b.original, sourceRestoreTerms),
                        translated = restoreTokens(b.translated, targetRestoreTerms),
                    )
                },
            )
        }

        /**
         * Stejné jako [restoreResponse], ale pro cesty bez echo "original" (viz
         * [TranslateRepository]'s Groq/legacy cesta - páruje odpověď POZICÍ, ne id, takže
         * není co porovnávat, jen výsledný přeložený text obnovit).
         */
        fun restoreTranslatedOnly(translated: String): String = restoreTokens(translated, targetRestoreTerms)
    }

    /**
     * Nahradí výskyty [protectedEntries]' sourceTerm v každé bublině tokenem tvaru
     * `__JIYU_PROTECT_n__`. Delší pojmy se nahrazují dřív než kratší (stejný princip jako
     * [OnDeviceTranslator.prepareGlossary]), aby kratší pojem, který je podřetězcem delšího,
     * nerozbil substituci delšího dřív, než na něj dojde řada. Case-INsensitive shoda - manga
     * bubliny pojem často napíšou celý verzálkami (křik/zvýraznění, např. "FRODO"), a přesně
     * takovou bublinu má `protectExact` chránit stejně jako běžně psanou - case-sensitive
     * shoda by ji tiše nechala bez ochrany (nahlášeno v auditu).
     *
     * Beze změny (identita), když [protectedEntries] je prázdné - nejčastější případ, funkce
     * se pak nemusí volat vůbec, ale takhle je volající strana (viz [TranslateRepository])
     * o to jednodušší (nemusí to sama větvit).
     */
    fun substitute(classified: List<ClassifiedBubble>, protectedEntries: List<GlossaryEntity>): Substitution {
        val sorted = protectedEntries.filter { it.sourceTerm.isNotBlank() }.sortedByDescending { it.sourceTerm.length }
        if (sorted.isEmpty()) return Substitution(classified, emptyList(), emptyList())
        val tokens = sorted.mapIndexed { index, entry -> entry to "$TOKEN_PREFIX$index$TOKEN_SUFFIX" }
        val substituted = classified.map { c ->
            var text = c.raw.text
            for ((entry, token) in tokens) text = text.replace(entry.sourceTerm, token, ignoreCase = true)
            if (text == c.raw.text) c else c.copy(raw = c.raw.copy(text = text))
        }
        // Index v tokenu ("JIYU_PROTECT_n") = index pojmu v `sorted` - restore mapy proto
        // držíme jako listy indexované přesně tímhle n, ne mapy token->pojem (viz
        // TOKEN_VARIANT_REGEX: model nemusí vrátit token v kanonickém tvaru).
        return Substitution(substituted, sorted.map { it.sourceTerm }, sorted.map { it.targetTerm })
    }

    /**
     * Obnovi tokeny na pojmy - TOLERANTNE na tvar, ve kterem je model vratil. Posila se
     * kanonicky "⟦JIYU_PROTECT_n⟧", ale model (hlavne levny/free-tier) ho obcas prepise do
     * jine podoby: "__JIYU_PROTECT_0__" (podtrzitka - tvar, ktery driv prompt dokumentoval a
     * model ho "opravoval" podle nej), "[JIYU_PROTECT_0]", "jiyu_protect_0", pripadne bez
     * dekoraci. Kdyby se obnovil jen presny kanonicky tvar, zustal by v textu syrovy token -
     * ten pak [hasLeakedToken] v render gate chytne a celou bublinu hodí do isUntranslated,
     * TAKZE se ctenari ukaze anglicky original misto spravneho prekladu (zmereno na
     * zarizeni: "NA CO ČEKÁŠ, __JIYU_PROTECT_0__?!").
     *
     * Regex proto matchuje povinne jen "JIYU_PROTECT_n" jadro (umelecky retezec, v prirozenem
     * textu se nevyskytuje) a dekorace na obou stranach jsou volitelne - ze zname mnoziny,
     * takze se na rozdil od "[^\\w]*" neuzere za tokenem pridrzene "?!" ci jina interpunkce.
     * Token s indexem mimo rozsah se necha nedotceny (protece dal do gate = konzervativni
     * zamitnuti, stejne jako dnes).
     */
    private fun restoreTokens(text: String, restoreTerms: List<String>): String {
        if (restoreTerms.isEmpty()) return text
        return TOKEN_VARIANT_REGEX.replace(text) { m ->
            val term = m.groupValues[1].toIntOrNull()?.let { restoreTerms.getOrNull(it) }
            term ?: m.value
        }
    }

    private val TOKEN_VARIANT_REGEX = Regex(
        "(?:⟦|__|_|[\\[({<【《«⟨]|\\*\\*|\\*)?\\s*JIYU_PROTECT[_\\s-]{0,2}(\\d+)(?:\\s*(?:⟧|__|_|[\\])}>】》»⟩]|\\*\\*|\\*))?",
        RegexOption.IGNORE_CASE,
    )

    // Znaky `⟦`/`⟧` (matematicke zavorky, U+27E6/U+27E7) - zamerne NE "__..._n__". Puvodni
    // format dvou podtrzitek na obou koncich je vizualne totozny s Markdown tucnym pismem
    // ("__text__"), a model (zvlast levny/free-tier) ho pri "prekladu" obcas "opravi"/
    // preformatuje presne jako by slo o formatovani - token se pak nikdy nenajde a
    // neobnovi, uzivatel misto skutecneho jmena uvidi syrovy placeholder (nahlaseno v
    // auditu). Tyhle znaky se v beznem textu neobjevuji a nic markdown-like nepripominaji,
    // takze pro model neni duvod je jakkoli "opravovat".
    private const val TOKEN_PREFIX = "⟦JIYU_PROTECT_"
    private const val TOKEN_SUFFIX = "⟧"
}
