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
    // Slovo se počítá i při drobném překlepu: model při echu často opraví OCR chybu ("SIRVIVOR" ->
    // "SURVIVOR") a přesná shoda pak správný překlad zahodila jako "odpověď na jinou bublinu", takže
    // bublina zůstala anglicky. Jiná bublina má překryv blízko nule i s tolerancí překlepů.
    val overlap = expectedWords.count { expected -> expected in returnedWords || returnedWords.any { isTypoOf(expected, it) } }
    return overlap.toFloat() / expectedWords.size >= 0.4f
}

/** Slova se liší nejvýš o jeden znak (vložení/smazání/záměna) - jen u slov od 4 znaků, kratší by se plést začala. */
private fun isTypoOf(a: String, b: String): Boolean {
    if (a.length < 4 || b.length < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    return when {
        a.length == b.length -> a.substring(i + 1) == b.substring(i + 1)
        a.length > b.length -> a.substring(i + 1) == b.substring(i)
        else -> a.substring(i) == b.substring(i + 1)
    }
}

/**
 * true = tenhle záznam z odpovědi modelu se dá použít jako překlad TÉHLE KONKRÉTNÍ bubliny.
 *
 * Nepoužitelný je záznam, který chybí úplně (model bublinu vynechal), má prázdný překlad,
 * nese [GeminiUltraPrompt.UNTRANSLATED_MARKER] (model sám říká "tohle OCR nedává smysl"),
 * je podezřele doslovná kopie originálu (viz [isSuspiciousVerbatimCopy] - model bublinu jen
 * "přeložil" zkopírováním, čtenář by pak viděl anglickou větu vysázenou jako hotový český
 * překlad, přesně to tiché selhání, kterému se tenhle soubor snaží předejít), nebo jehož
 * echované "original" neodpovídá tomu, co bublina doopravdy obsahovala (viz [originalMatches]
 * - model odpověděl na jinou bublinu). Ve všech případech patří bublina mezi nepřeložené, ne
 * mezi přeložené originálem nebo cizím textem.
 *
 * Dřív se [isSuspiciousVerbatimCopy] jen logovalo (viz `VerbatimCopy` log) - appka pak přesně
 * takové bubliny vykreslila jako hotový překlad (nahlášeno: "HOW WE MAKE A LIVING."/"ABOUT
 * WHAT?" zůstaly anglicky, i když okolní bubliny na stejné stránce přeložené byly).
 */
internal fun isUsableTranslation(translation: GeminiBubbleTranslation?, expectedOriginal: String): Boolean {
    val text = translation?.translated?.trim() ?: return false
    if (text.isEmpty() || text == GeminiUltraPrompt.UNTRANSLATED_MARKER) return false
    if (isSuspiciousVerbatimCopy(expectedOriginal, text)) return false
    return originalMatches(translation.original, expectedOriginal)
}

/**
 * "Přeložený" text je (až na velikost písmen/okrajové mezery) doslova stejný jako originál -
 * u českého cíle to skoro nikdy neni skutečný překlad, spíš znamka, že model text jen
 * zkopíroval, aniž by dodržel "ŠEST PRAVIDEL" z promptu (viz GeminiUltraPrompt sekce
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
 * Model vrátil text skoro totožný s OCR originálem - liší se jen diakritikou nebo drobným
 * poškozením (audit Vagabondu: "Vagakond" -> "Vagabond" přes ručně kreslené titulní logo).
 * Takový překlad nenesl žádnou novou informaci: přepsat lettering běžným fontem bubliny by
 * originál jen zdegradoval. Render tuhle bublinu přeskočí (viz `bubbleSkipReason` /
 * `TranslatedBlock.isArtText`) a originál zůstane vidět.
 *
 * Na rozdíl od [isSuspiciousVerbatimCopy] (přesná shoda = podezření na nedodržení překladu)
 * je tohle MĚKKÁ shoda - Levenshtein ≤2 na stejné délce po normalizaci (lowercase, bez
 * diakritiky, jen písmena/číslice). Pokrývá přesnou shodu i jednoznakové OCR poškození.
 * Pod 4 písmena se nevyhodnocuje - u dvoupísmenných tokenů ("HI"/"OK") je distanční
 * blízkost nejednoznačná a jejich overlay je beztak neškodný.
 *
 * Samotná shoda ("STOP" -> "STOP") už dřív skončila `isUntranslated` přes
 * [isSuspiciousVerbatimCopy]; tady dochytáváme i téměř-shodu, která verbatim filtr mine.
 */
internal fun isArtTextEcho(original: String, translated: String): Boolean {
    val o = normalizeArtTextEcho(original)
    val t = normalizeArtTextEcho(translated)
    if (o.length < 4) return false
    if (o.length == t.length) return levenshteinAtMost(o, t, 2)
    // Logo + přilepený přeložený pojem: překlad obsahuje (téměř) celý originál a jen
    // krátce navazuje - "VAGAKONDVIZBIGEDIIION" -> "...SVAZEK" (audit: cover logo
    // dostalo šedý box s přepsaným textem). Podmínka podílu brání falešnému poplachu
    // "Takezō" < "Takezō jde ven" - originál musí tvořit většinu překladu.
    return o.length >= 6 && t.length > o.length && t.contains(o) && o.length >= (t.length * 0.7f)
}

/** Sjednocení pro [isArtTextEcho] - lowercase, bez diakritiky, jen písmena a číslice. */
private fun normalizeArtTextEcho(text: String): String = buildString(text.length) {
    for (c in text) {
        // Nejdřív lowercase - STRIP_DIACRITICS je klíčovaná malými znaky.
        val lower = c.lowercaseChar()
        val plain = STRIP_DIACRITICS[lower] ?: lower
        if (plain.isLetterOrDigit()) append(plain)
    }
}

internal val STRIP_DIACRITICS = mapOf(
    'á' to 'a', 'ä' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'å' to 'a', 'ā' to 'a',
    'č' to 'c', 'ć' to 'c', 'ç' to 'c',
    'ď' to 'd',
    'é' to 'e', 'ë' to 'e', 'è' to 'e', 'ê' to 'e', 'ě' to 'e', 'ę' to 'e',
    'í' to 'i', 'ï' to 'i', 'ì' to 'i', 'î' to 'i',
    'ł' to 'l', 'ľ' to 'l',
    'ň' to 'n', 'ñ' to 'n', 'ń' to 'n',
    'ó' to 'o', 'ö' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ø' to 'o', 'ō' to 'o',
    'ř' to 'r', 'ŕ' to 'r',
    'š' to 's', 'ś' to 's', 'ș' to 's',
    'ť' to 't',
    'ú' to 'u', 'ü' to 'u', 'ù' to 'u', 'û' to 'u', 'ů' to 'u', 'ū' to 'u',
    'ý' to 'y', 'ÿ' to 'y',
    'ž' to 'z', 'ź' to 'z', 'ż' to 'z',
)

/**
 * Levenshtein se stropem [max] - vrátí false jakmile je jisté, že překročí limit
 * (každý řádek DP tabulky je minimem nad ním). Oba řetězce mají stejnou délku, ale
 * stále řešíme plnou edit-distance: transpozice/zdvojení znaku (insert+delete) jsou
 * častější OCR chyba než pouhá výměna.
 */
internal fun levenshteinAtMost(a: String, b: String, max: Int): Boolean {
    val m = a.length; val n = b.length
    if (kotlin.math.abs(m - n) > max) return false
    var prev = IntArray(n + 1) { it }
    for (i in 1..m) {
        var rowMin = Int.MAX_VALUE
        val cur = IntArray(n + 1)
        cur[0] = i
        for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if (cur[j] < rowMin) rowMin = cur[j]
        }
        if (rowMin > max) return false
        prev = cur
    }
    return prev[n] <= max
}

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
 * Podezření, že model bublinu přeložil BEZ OHLEDU na glosář - originál obsahuje zdrojový
 * termín, ale jeho cílový protějšek se v překladu vůbec nenajde. Stejný "log-only" vzor jako
 * [isSuspiciousVerbatimCopy]/[likelyDroppedSentence] - glosář je v promptu jen "doporučení",
 * které model může ignorovat, a nic v kódu dřív neověřovalo, jestli to dělá.
 *
 * Porovnává se jen PREFIX cílového termínu (viz [MIN_GLOSSARY_STEM_LENGTH]), ne celé slovo -
 * české skloňování mění koncovku ("Frodo" -> "Frodovi"/"Frodem"), takže přesná shoda by
 * falešně hlásila porušení u KAŽDÉHO skloněného vlastního jména, ne jen u skutečně
 * ignorovaného pojmu.
 */
internal fun isGlossaryViolation(original: String, translated: String, glossary: Map<String, String>): Boolean {
    for ((source, target) in glossary) {
        if (source.isBlank() || target.isBlank()) continue
        if (!original.contains(source, ignoreCase = true)) continue
        val stem = target.take(maxOf(MIN_GLOSSARY_STEM_LENGTH, target.length - 2))
        if (!translated.contains(stem, ignoreCase = true)) return true
    }
    return false
}

private const val MIN_GLOSSARY_STEM_LENGTH = 3

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

/**
 * [missingTranslationIndices] rozšířené o dva "kvalitativní" signály, které samy o sobě
 * NEDĚLAJÍ překlad nepoužitelným (viz [isUsableTranslation] - ten se pořád vytiskne), ale
 * mají smysl zkusit znovu, protože jde o rozpoznaný vzor špatného výstupu:
 * [likelyDroppedSentence] (vícevětá bublina přišla o větu, dřív se jen logovalo) a
 * [isRepetitionLoop] (model se zacyklil na znaku/slově/frázi místo skutečného překladu).
 *
 * Sjednoceno přes union NAD [missingTranslationIndices], ne přepsáním - ta funkce má vlastní
 * testy a její chování (chybějící/prázdné/marker/špatné číslování) se nemění, jen se
 * přidávají další důvody k retry stejnou cestou ([mergeRetry] přes stejný `retriedIndices`).
 */
internal fun retryIndicesWithQualityChecks(
    classified: List<ClassifiedBubble>,
    byId: Map<Int, GeminiBubbleTranslation>,
): List<Int> {
    val missing = missingTranslationIndices(classified, byId)
    val missingSet = missing.toHashSet()
    val qualityIssues = classified.indices.filter { i ->
        if (classified[i].isSfx || i in missingSet) return@filter false
        val translated = byId[i]?.translated?.trim() ?: return@filter false
        if (translated.isEmpty() || translated == GeminiUltraPrompt.UNTRANSLATED_MARKER) return@filter false
        likelyDroppedSentence(classified[i].raw.text, translated) || isRepetitionLoop(translated)
    }
    return (missing + qualityIssues).sorted()
}

/**
 * Podezření, že "přeložený" text je zdegenerovaná smyčka - model se zacyklil na jednom
 * znaku/slově/frázi místo aby dokončil skutečný překlad. Na rozdíl od
 * [MangaOcrGarbageFilter] (OCR vstup, libovolný skript, znakové vzory) tohle kontroluje
 * VÝSTUP z LLM (cílový jazyk, slova oddělená mezerami) - proto samostatná detekce založená
 * na slovech/frázích, ne jen na opakujících se znacích.
 *
 * Tři nezávislé signály, kterýkoli stačí:
 * 1) stejný krátký (1-3 znaky) úsek dokola za sebou (např. rozbitá interpunkce),
 * 2) stejné slovo hned za sebou [minWordRepeats]x a víckrát,
 * 3) stejná fráze (3 slova) se v textu opakuje [minPhraseOccurrences]x a víckrát - i
 *    nesouvisle, model umí zacyklenou frázi prokládat jiným textem.
 *
 * Používá se jen jako DALŠÍ důvod k retry (viz [retryIndicesWithQualityChecks]), ne k
 * přímému zahození výsledku - false positive tu stojí jeden extra dotaz, ne ztracený
 * překlad, takže je heuristika záměrně o něco citlivější než [MangaOcrGarbageFilter].
 */
internal fun isRepetitionLoop(
    text: String,
    minCharRepeats: Int = MIN_CHAR_REPEATS,
    minWordRepeats: Int = MIN_WORD_REPEATS,
    minPhraseOccurrences: Int = MIN_PHRASE_OCCURRENCES,
): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < MIN_LENGTH_FOR_REPETITION_CHECK) return false
    if (hasCharacterRun(trimmed, minCharRepeats)) return true
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < 2) return false
    if (hasConsecutiveWordRepeat(words, minWordRepeats)) return true
    return hasRepeatedPhrase(words, minPhraseOccurrences)
}

private fun hasCharacterRun(text: String, minRepeats: Int): Boolean {
    for (patternLen in 1..3) {
        if (text.length < patternLen * minRepeats) continue
        var i = 0
        while (i + patternLen <= text.length) {
            val pattern = text.substring(i, i + patternLen)
            var repeats = 1
            var j = i + patternLen
            while (j + patternLen <= text.length && text.substring(j, j + patternLen) == pattern) {
                repeats++
                j += patternLen
            }
            if (repeats >= minRepeats) return true
            i++
        }
    }
    return false
}

private fun hasConsecutiveWordRepeat(words: List<String>, minRepeats: Int): Boolean {
    var i = 0
    while (i < words.size) {
        val word = words[i].lowercase()
        var repeats = 1
        var j = i + 1
        while (j < words.size && words[j].lowercase() == word) {
            repeats++
            j++
        }
        if (repeats >= minRepeats) return true
        i = j
    }
    return false
}

private fun hasRepeatedPhrase(words: List<String>, minOccurrences: Int, phraseWordCount: Int = 3): Boolean {
    if (words.size < phraseWordCount * 2) return false
    val counts = HashMap<String, Int>()
    for (i in 0..words.size - phraseWordCount) {
        val phrase = (i until i + phraseWordCount).joinToString(" ") { words[it].lowercase() }
        val count = (counts[phrase] ?: 0) + 1
        counts[phrase] = count
        if (count >= minOccurrences) return true
    }
    return false
}

private const val MIN_LENGTH_FOR_REPETITION_CHECK = 6
private const val MIN_CHAR_REPEATS = 5
private const val MIN_WORD_REPEATS = 4
private const val MIN_PHRASE_OCCURRENCES = 3

/**
 * Podezření, že přeložená dávka NENÍ v cílovém jazyce vůbec (model odpověděl anglicky/
 * japonsky misto cesky) - poslední pojistka NAD jednotlivými per-bublina kontrolami výš,
 * protože jedna krátká bublina (jméno, citoslovce) může projít jako "použitelná" i když je
 * celá dávka ve špatném jazyce; tahle kontrola se dělá na SPOJENÉM textu víc bublin najednou.
 *
 * @param combinedText spojený přeložený text víc bublin/odstavců najednou - jednotlivá
 *   krátká bublina by detekci jazyka jen zmátla, proto [minTextLength].
 * @param identifyLanguage vrací BCP-47 kód detekovaného jazyka nebo null (nejistá detekce) -
 *   v produkci navázáno na ML Kit `LanguageIdentification` (viz [TranslateRepository]), v
 *   testu na falešnou funkci - stejný testovatelný vzor jako [resolveAutoLanguage] v
 *   OcrEngine.kt.
 */
internal suspend fun isWrongTargetLanguage(
    combinedText: String,
    targetLanguage: String,
    minTextLength: Int = MIN_TEXT_LENGTH_FOR_LANGUAGE_CHECK,
    identifyLanguage: suspend (String) -> String?,
): Boolean {
    val expectedCode = targetLanguageBcp47Code(targetLanguage) ?: return false
    val trimmed = combinedText.trim()
    if (trimmed.length < minTextLength) return false
    val detectedCode = identifyLanguage(trimmed) ?: return false
    return detectedCode != expectedCode
}

private const val MIN_TEXT_LENGTH_FOR_LANGUAGE_CHECK = 40

/**
 * BCP-47 kód, který ML Kit `LanguageIdentification` vrací pro tenhle CÍLOVÝ jazyk - stejné
 * jazyky jako [OnDeviceTranslator] podporuje, ale udržováno samostatně (ne
 * [com.haise.jiyu.source.LanguageMap], ten má zdrojově-specifické kódy jako "pt-br"/"zh-hk",
 * které by se s ML Kit výstupem nikdy neshodly).
 */
private fun targetLanguageBcp47Code(targetLanguage: String): String? = when (targetLanguage) {
    "Czech" -> "cs"
    "English" -> "en"
    "Japanese" -> "ja"
    "Korean" -> "ko"
    "Chinese" -> "zh"
    "Chinese (Traditional)" -> "zh"
    "German" -> "de"
    "French" -> "fr"
    "Spanish" -> "es"
    "Russian" -> "ru"
    "Portuguese" -> "pt"
    "Italian" -> "it"
    "Polish" -> "pl"
    "Turkish" -> "tr"
    "Dutch" -> "nl"
    "Arabic" -> "ar"
    "Hindi" -> "hi"
    "Thai" -> "th"
    "Vietnamese" -> "vi"
    "Indonesian" -> "id"
    else -> null
}
