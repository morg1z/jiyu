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
        private val sourceRestoreMap: Map<String, String>,
        private val targetRestoreMap: Map<String, String>,
    ) {
        val isNoop: Boolean get() = sourceRestoreMap.isEmpty() && targetRestoreMap.isEmpty()

        fun restoreResponse(response: GeminiTranslationResponse): GeminiTranslationResponse {
            if (isNoop) return response
            return response.copy(
                bubbles = response.bubbles.map { b ->
                    b.copy(
                        original = restoreTokens(b.original, sourceRestoreMap),
                        translated = restoreTokens(b.translated, targetRestoreMap),
                    )
                },
            )
        }

        /**
         * Stejné jako [restoreResponse], ale pro cesty bez echo "original" (viz
         * [TranslateRepository]'s Groq/legacy cesta - páruje odpověď POZICÍ, ne id, takže
         * není co porovnávat, jen výsledný přeložený text obnovit).
         */
        fun restoreTranslatedOnly(translated: String): String = restoreTokens(translated, targetRestoreMap)
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
        if (sorted.isEmpty()) return Substitution(classified, emptyMap(), emptyMap())
        val tokens = sorted.mapIndexed { index, entry -> entry to "$TOKEN_PREFIX$index$TOKEN_SUFFIX" }
        val sourceRestoreMap = tokens.associate { (entry, token) -> token to entry.sourceTerm }
        val targetRestoreMap = tokens.associate { (entry, token) -> token to entry.targetTerm }
        val substituted = classified.map { c ->
            var text = c.raw.text
            for ((entry, token) in tokens) text = text.replace(entry.sourceTerm, token, ignoreCase = true)
            if (text == c.raw.text) c else c.copy(raw = c.raw.copy(text = text))
        }
        return Substitution(substituted, sourceRestoreMap, targetRestoreMap)
    }

    /**
     * Case-INsensitive nahrazeni (na rozdil od obycejneho String.replace) - model muze token
     * vratit s jinou velikosti pismen, nez dostal (male/velke JIYU misto Jiyu apod.), zvlast
     * u levneho/free-tier providera. Bez tohohle by se token vubec neobnovil a uzivatel by
     * v prekladu videl syrovy placeholder misto skutecneho jmena.
     */
    private fun restoreTokens(text: String, restoreMap: Map<String, String>): String {
        if (restoreMap.isEmpty()) return text
        var result = text
        for ((token, term) in restoreMap) {
            result = Regex(Regex.escape(token), RegexOption.IGNORE_CASE).replace(result, Regex.escapeReplacement(term))
        }
        return result
    }

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
