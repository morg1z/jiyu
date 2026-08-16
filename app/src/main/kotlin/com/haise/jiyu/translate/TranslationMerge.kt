package com.haise.jiyu.translate

/**
 * Slučování odpovědi modelu zpátky s bublinami, které se posílaly - a hlavně rozhodnutí,
 * co je vlastně použitelný překlad.
 *
 * Proč to má vlastní soubor: model občas nějakou bublinu v JSON odpovědi prostě vynechá,
 * nebo pro ni vrátí prázdný řetězec. Dřív se v takovém případě potichu propadl ORIGINÁL
 * (anglický text) do pole `translatedText` a vykreslil se přes bublinu jako plnohodnotný
 * překlad - čtenář tak viděl anglickou větu vysázenou "česky vypadajícím" způsobem přes
 * zakrytý originál. Tomu se říká tiché selhání a je horší než žádný překlad: kdyby se
 * bublina označila jako nepřeložená, overlay by ji vůbec nekreslil a originál by zůstal
 * čitelný (viz `TranslationLayer` a `TranslatedBlock.isUntranslated`).
 *
 * Druhá, závažnější třída chyby: model v odpovědi posune číslování "id" (halucinace/chyba
 * při počítání pod velkou dávkou - [TranslateRepository.translateChapter] flattenuje bubliny
 * z VÍC stránek do jednoho požadavku, takže jde o desítky bublin v jednom kontextu). Appka
 * `byId[i]` slepě věřila poli "id" a bez kontroly aplikovala překlad NA JINOU bublinu, než
 * pro kterou byl doopravdy určen - viz uživatelská zpětná vazba (bublina "NOT LIKE THAT."
 * zobrazila text, který patřil jiné bublině o pár pozic dál na jiné stránce). Model přitom
 * dostal instrukci vrátit i pole "original" (echo toho, co si myslí, že bublina s tímhle id
 * obsahovala) - to pole se dřív parsovalo, ale nikde se nekontrolovalo. [originalMatches]
 * ho použije jako pojistku: než se překlad použije, ověří se, že model odpovídal na
 * SPRÁVNOU bublinu.
 */

/**
 * true, když text, který model vrátil jako "original" pro tuhle bublinu, dost odpovídá
 * tomu, co jsme mu pod tímhle id doopravdy poslali.
 *
 * Schválně shovívavé porovnání (průnik delších slov, ne přesná shoda) - model může při
 * echu normalizovat mezery/uvozovky nebo opravit drobnou OCR literovku, to není chyba
 * číslování a nechceme kvůli tomu zahazovat jinak správný překlad. Zajímá nás jen případ,
 * kdy je to zjevně JINÝ text (jiná bublina) - tam je překryv slov blízko nule.
 */
internal fun originalMatches(returnedOriginal: String, expectedText: String): Boolean {
    fun words(s: String) = s.lowercase()
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .split(Regex("\\s+"))
        .filter { it.length >= 3 } // kratka slova (spojky, zajmena) jsou skoro vzdy spolecna
        .toSet()

    val expectedWords = words(expectedText)
    if (expectedWords.isEmpty()) return true // prilis kratky text na smysluplne porovnani, neveto
    val returnedWords = words(returnedOriginal)
    val overlap = expectedWords.intersect(returnedWords).size
    return overlap.toFloat() / expectedWords.size >= 0.4f
}

/**
 * true = tenhle záznam z odpovědi modelu se dá použít jako překlad TÉHLE KONKRÉTNÍ bubliny.
 *
 * Nepoužitelný je záznam, který chybí úplně (model bublinu vynechal), má prázdný překlad,
 * nese [GeminiUltraPrompt.UNTRANSLATED_MARKER] (model sám říká "tohle OCR nedává smysl"),
 * nebo jehož echované "original" neodpovídá tomu, co bublina doopravdy obsahovala (viz
 * [originalMatches] - model odpověděl na jinou bublinu). Ve všech případech patří bublina
 * mezi nepřeložené, ne mezi přeložené originálem nebo cizím textem.
 */
internal fun isUsableTranslation(translation: GeminiBubbleTranslation?, expectedOriginal: String): Boolean {
    val text = translation?.translated?.trim() ?: return false
    if (text.isEmpty() || text == GeminiUltraPrompt.UNTRANSLATED_MARKER) return false
    return originalMatches(translation.original, expectedOriginal)
}

/**
 * "Přeložený" text je (až na velikost písmen/okrajové mezery) doslova stejný jako originál -
 * u českého cíle to skoro nikdy neni skutečný překlad, spíš znamka, že model text jen
 * zkopíroval, aniž by dodržel "PĚT PRAVIDEL" z promptu (viz GeminiUltraPrompt sekce
 * "KONTROLA PŘED ODESLÁNÍM" - nic v kódu dřív neověřovalo, jestli je vůbec dodržená).
 *
 * Cistě informativní signál (nemění [isUsableTranslation]/isUntranslated) - u krátkých
 * vlastních jmen, citoslovcí nebo interpunkce může být shoda legitimní (jména se
 * nepřekládají), proto je pod [MIN_VERBATIM_LENGTH] vždycky false.
 */
internal fun isSuspiciousVerbatimCopy(original: String, translated: String): Boolean {
    val o = original.trim()
    val t = translated.trim()
    if (o.length < MIN_VERBATIM_LENGTH) return false
    if (!o.any { it.isLetter() }) return false
    return o.equals(t, ignoreCase = true)
}

private const val MIN_VERBATIM_LENGTH = 4

/**
 * Kolik vět text obsahuje, odhadnuto z koncové interpunkce (běh `.`/`!`/`?` nebo výpustka
 * `…` se počítá jako JEDNA hranice, ne za každý znak zvlášť).
 */
internal fun countSentenceBoundaries(text: String): Int = SENTENCE_BOUNDARY.findAll(text).count()

private val SENTENCE_BOUNDARY = Regex("[.!?]+|…")

/**
 * Podezření, že model při překladu VÍCEVĚTNÉ bubliny (sloučené OCR řádky nebo "POKRAČUJE Z"
 * navazující bublina, viz [GeminiUltraPrompt] sekce "VĚTY PŘES VÍC BUBLIN") jednu nebo víc vět
 * zahodil, místo aby je všechny přeložil.
 *
 * Nahlášeno se srovnávací dvojicí snímků: bublina se dvěma větami "WE NEED TO HURRY THE
 * HARVEST! THE FOOD WON'T LAST MUCH LONGER..." se v češtině objevila jen jako "JÍDLO UŽ DLOUHO
 * VYDRŽET NEBUDE..." - první věta zmizela beze stopy. Prompt to výslovně zakazuje ("Žádnou
 * bublinu nenechávej prázdnou", "nikdy neztrácej informaci"), ale nic v kódu dřív neověřovalo,
 * jestli to model doopravdy dodržel - stejná mezera jako u [isSuspiciousVerbatimCopy].
 *
 * Čistě informativní heuristika (nic nemění, jen loguje) - u textu s jedinou větou (< 2 hranice)
 * se nevyhodnocuje vůbec, protože komprese jedné věty do kratší je legitimní a čekaná (viz
 * prompt "PŘIROZENÁ ČEŠTINA").
 */
internal fun likelyDroppedSentence(originalText: String, translatedText: String): Boolean {
    val originalBoundaries = countSentenceBoundaries(originalText)
    if (originalBoundaries < 2) return false
    return countSentenceBoundaries(translatedText) < originalBoundaries
}

/**
 * Indexy bublin, na které model neodpověděl použitelně a má smysl se na ně doptat znovu.
 *
 * SFX se vynechávají - ty se schválně nepřekládají vůbec (viz [BubbleClassifier]), takže
 * chybějící odpověď u nich není chyba.
 *
 * @param byId odpověď modelu naindexovaná podle "id" (= pozice v seznamu, který se posílal)
 */
internal fun missingTranslationIndices(
    classified: List<ClassifiedBubble>,
    byId: Map<Int, GeminiBubbleTranslation>,
): List<Int> = classified.indices.filter { i ->
    if (classified[i].isSfx) return@filter false
    val t = byId[i]
    // Vědomé "nepřeložím" (UNTRANSLATED_MARKER) se neopakuje - model už jednou vědomě řekl
    // "tohle nepřeložím", opakovaný dotaz na to samé by jen stál další požadavek.
    if (t?.translated?.trim() == GeminiUltraPrompt.UNTRANSLATED_MARKER) return@filter false
    !isUsableTranslation(t, classified[i].raw.text)
}

/**
 * Doplní do [byId] záznamy z opravného dotazu.
 *
 * Opravný dotaz posílá jen podmnožinu bublin, takže "id" v jeho odpovědi jsou pozice v TÉ
 * podmnožině (0..n-1), ne v původním seznamu - [retriedIndices] je převodní tabulka zpět.
 * Použitelný záznam z opravy má přednost; nepoužitelný (včetně znovu špatně očíslovaného -
 * viz [originalMatches]) se zahodí, aby nepřepsal případný dřívější dobrý výsledek.
 *
 * @param classified PŮVODNÍ seznam bublin (stejný, co se poslal napoprvé) - potřeba, aby
 *   šlo ověřit echo "original" i pro záznamy z opravného dotazu.
 */
internal fun mergeRetry(
    byId: Map<Int, GeminiBubbleTranslation>,
    retriedIndices: List<Int>,
    retryResponse: GeminiTranslationResponse?,
    classified: List<ClassifiedBubble>,
): Map<Int, GeminiBubbleTranslation> {
    if (retryResponse == null) return byId
    val merged = byId.toMutableMap()
    for (bubble in retryResponse.bubbles) {
        val originalIndex = retriedIndices.getOrNull(bubble.id) ?: continue
        val expected = classified.getOrNull(originalIndex)?.raw?.text ?: continue
        if (isUsableTranslation(bubble, expected)) merged[originalIndex] = bubble
    }
    return merged
}
