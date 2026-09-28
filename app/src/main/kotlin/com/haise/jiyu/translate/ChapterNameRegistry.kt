package com.haise.jiyu.translate

/**
 * Registr vlastních jmen v rámci JEDNÉ kapitoly + cache konzistence frází.
 *
 * ## Proč existuje
 * Audit Vagabondu ukázal dva typy nekonzistence, které žádná per-bublinová kontrola
 * nechytně chytí, protože chyba je vidět jen napříč bublinami:
 *  - jméno vydané v překladu pokaždé jinak nebo zkomolené - "TSIJJIKAZE" (dvojité J),
 *    "SAKUSHŪ"/"SAKUSHO"/"SAKUSHU" ve třech tvarech, "HON'IDEN"->"HONIDEN";
 *  - stejná věta přeložená jinak nebo nepřeložená - "NO WAY I'M GONNA DIE!" jednou
 *    česky, jednou zůstalo anglicky; "I SEE." jednou "ROZUMÍM", jednou "...".
 *
 * ## Jak funguje
 *  1) Před překladem kapitoly se projdou všechny OCR zdroje a sesbírají se kandidáti
 *     na jména: tokeny >=4 písmen, verzálkové nebo Capitalized, které nejsou běžná
 *     anglická slova ani známé zvuky. Kanonický tvar preferuje ten s diakritikou
 *     ("TAKEZŌ" z titulku porazí "TAKEZO" z dialogu).
 *  2) Po překladu každé dávky se tokeny výstupu normalizují na kanonický tvar -
 *     přesná shoda klíče (opraví diakritiku) nebo jednoznačná fuzzy shoda na
 *     editační vzdálenost <=1 (<=2 u jmen >=8 znaků) - opraví "TSIJJIKAZE".
 *  3) Stejná zdrojová věta (normalizovaná) dostane vždy překlad svého prvního
 *     výskytu - druhý "NO WAY I'M GONNA DIE!" se vykreslí stejně jako první.
 *
 * Všechno je konzervativní: české/anglické slovníkové slovo se nikdy nepřepíše
 * na jméno a více fuzzy kandidátů = žádná oprava.
 */
internal class ChapterNameRegistry(
    private val englishWords: Set<String> = emptySet(),
    private val czechWords: Set<String> = emptySet(),
) {
    private data class Canonical(var form: String, var count: Int)

    /** Klíč = normalizované jméno (lowercase, bez diakritiky); hodnota = kanonický tvar. */
    private val byKey = mutableMapOf<String, Canonical>()

    /** Normalizovaná zdrojová věta -> překlad prvního výskytu (konzistence frází). */
    private val phraseCache = mutableMapOf<String, String>()

    // --- Sběr kandidátů -----------------------------------------------------

    /**
     * Projde zdrojový (OCR) text a zaregistruje kandidáty na jména. Volá se nad
     * VŠEMI ne-SFX bloky kapitoly ještě před překladem - vícenásobný výskyt a tvary
     * z titulků (s diakritikou) utvářejí kanonickou formu.
     */
    fun observeSource(text: String) {
        for (m in NAME_TOKEN.findAll(text)) {
            val token = m.value
            val letters = token.count { it.isLetter() }
            if (letters < 4) continue
            if (token.none { it.isLetter() && it.uppercaseChar() in LATIN_VOWEL_CHARS }) continue
            // Jen verzálky nebo Capitalized - věty začínající velkým písmenem by
            // jinak registrovaly první slovo každé věty jako "jméno". Verzálky
            // lettering jmen typicky používá; Capitalized kryje normální text.
            if (!token.isAllCapsName() && !token[0].isUpperCase()) continue
            if (token.lowercase() in englishWords) continue
            if (BubbleClassifier.isKnownSfx(token.uppercase())) continue
            val key = normKey(token)
            val hasDiacritic = token.any { it.code > 127 }
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = Canonical(token, 1)
            } else {
                existing.count++
                // Kanonický tvar preferuje diakritiku ("TAKEZŌ" > "TAKEZO").
                if (hasDiacritic && existing.form.none { it.code > 127 }) existing.form = token
            }
        }
    }

    // --- Normalizace překladu ------------------------------------------------

    /**
     * Přepíše tokeny v překladu na kanonické tvary jmen z [observeSource].
     * Token, který je běžné české/anglické slovo, se nikdy nedotkne - jména tak
     * nemůžou "ukrást" normální slovo.
     */
    fun normalizeText(text: String): String {
        if (byKey.isEmpty()) return text
        return NAME_TOKEN.replace(text) { m ->
            val token = m.value
            val letters = token.count { it.isLetter() }
            if (letters < 4) return@replace token
            // Skutečné slovníkové slovo nikdy nepřepisujeme na jméno.
            if (token.lowercase() in czechWords || token.lowercase() in englishWords) {
                return@replace token
            }
            val key = normKey(token)
            val canonical = byKey[key]?.form ?: findFuzzyCanonical(key, letters)
                ?: return@replace token
            if (canonical == token) token else shapedLike(canonical, token)
        }
    }

    /**
     * Jednoznačná fuzzy shoda: klíč tokenu je na editační vzdálenost <=1 (<=2 u
     * >=8 znaků, tam dvojité písmeno/vypadlý apostrof běžně vzniká) od klíče
     * zaregistrovaného jména. Při více kandidátech null - nejde rozhodnout.
     */
    private fun findFuzzyCanonical(key: String, letters: Int): String? {
        val maxDist = if (letters >= 8) 2 else 1
        var hit: String? = null
        for ((k, c) in byKey) {
            if (!levenshteinAtMost(key, k, maxDist)) continue
            if (hit != null && hit != c.form) return null
            hit = c.form
        }
        return hit
    }

    // --- Konzistence frází ----------------------------------------------------

    /**
     * Vrátí překlad této bubliny s ohledem na [phraseCache]: stejná zdrojová věta
     * (po normalizaci) už přeložená -> znovu použije první překlad (jen s koncovou
     * interpunkcí přizpůsobenou zdroji). Nová věta -> zapíše se.
     *
     * @return pár (text, byloPouzitoZCache) - byloPouzitoZCache=true znamená, že
     *   blok se má přepnout na cacheovaný text i když byl untranslated.
     */
    fun consistentTranslation(source: String, translated: String, wasTranslated: Boolean): Pair<String, Boolean> {
        val key = phraseKey(source) ?: return translated to false
        val cached = phraseCache[key]
        if (cached != null) return adaptTerminalPunct(cached, source) to true
        if (wasTranslated) phraseCache[key] = translated
        return translated to false
    }

    /** Normalizovaný klíč věty - lowercase, sjednocené mezery, bez koncové interpunkce. */
    private fun phraseKey(source: String): String? {
        val key = source.trim().lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[.!?…,;:\\s]+$"), "")
        // Příliš krátké fráze ("hm?", "?") by kolizí mohly vzít cizí překlad.
        return key.takeIf { it.count { c -> c.isLetterOrDigit() } >= 4 }
    }

    /**
     * Koncovou interpunkci cacheovaného překladu přizpůsobí zdroji ("ROZUMÍM." pro
     * "I SEE." vs "ROZUMÍM?" pro "I SEE?") - jinak zůstane překlad beze změny.
     */
    private fun adaptTerminalPunct(cached: String, source: String): String {
        val srcPunct = Regex("[.!?…]+$").find(source.trim())?.value ?: return cached
        val cachedTrim = cached.trim()
        val cachedPunct = Regex("[.!?…]+$").find(cachedTrim)?.value
        return if (cachedPunct != null) cachedTrim.removeSuffix(cachedPunct) + srcPunct
        else cachedTrim + srcPunct
    }

    // --- Pomocné --------------------------------------------------------------

    /** Token složený jen z verzálek (a ne-písmen) - "SAKUSHŪ", "HON'IDEN". */
    private fun String.isAllCapsName(): Boolean =
        count { it.isLetter() } >= 4 && all { !it.isLetter() || it.isUpperCase() }

    private fun normKey(token: String): String = buildString(token.length) {
        for (c in token) {
            // Nejdřív lowercase - STRIP_DIACRITICS je klíčovaná malými znaky,
            // jinak by "TAKEZŌ" skončilo jako "takezō" místo "takezo".
            val lower = c.lowercaseChar()
            val plain = STRIP_DIACRITICS[lower] ?: lower
            if (plain.isLetterOrDigit()) append(plain)
        }
    }

    /** Tvar jména přizpůsobený tvaru tokenu: verzálky -> verzálky, jinak tvar registru. */
    private fun shapedLike(canonical: String, token: String): String = when {
        token.isAllCapsName() -> canonical.uppercase()
        token.all { it.isLetter() && it.isLowerCase() } -> canonical.lowercase()
        else -> canonical
    }

    private companion object {
        private val NAME_TOKEN = Regex("[\\p{L}]+(?:['’\\-][\\p{L}]+)*")
        private const val LATIN_VOWEL_CHARS = "AEIOUYÁÄÀÂÃÅÆÉËÈÊÍÏÌÎÓÖÒÔÕØŌÚÜÙÛŮÝŸĚĘĄŁ"
    }
}
