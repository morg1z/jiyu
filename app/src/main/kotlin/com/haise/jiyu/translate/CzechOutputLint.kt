package com.haise.jiyu.translate

/**
 * Lint ČESKÉHO překladového výstupu - poslední obrana proti zkomoleninám, kterým
 * model při slibném OCR přesto vyrobí (audit Vagabondu: "ZATÍŽETE", "NEMYSL.",
 * "MUSELI", germanismus "UPS!").
 *
 * Princip opačný než u [cleanOcrLatinText]: tady se nic neopravuje, jen se
 * OZNAČÍ podezřelé tokeny - slova, která nejsou ve frekvenčním slovníku
 * (`assets/cs_common_words.txt`, ~35k tvarů z OpenSubtitles) a zároveň
 * nevypadají jako vlastní jméno/pojem. Flagovaný blok se v
 * [TranslateRepository] převezme jako `isUntranslated` a dostane repair retry
 * přes zbylé providery v [fillUntranslatedBlocks]; když ani retry nepomůže,
 * čtenář vidí originál místo zkomoleniny.
 *
 * @param original zdrojový text bubliny - slouží k poznání vlastních jmen:
 *   token překladu, který se (po normalizaci) liší od některého zdrojového
 *   tokenu o ≤2 editační kroky, se bere jako jméno/pojem (Takezō~Takezo,
 *   Frodo~Frodovi, Sakura~Sakuře) a nikdy se neflaguje.
 * @param englishWords anglický slovník (`en_common_words.txt`) - český výstup,
 *   který obsahuje anglické slovo, je verbatim únik ze zdroje ("SURVIVOR",
 *   "I CHCETE" z auditu) a flaguje se vždy, i když se slovu shoduje se zdrojem
 *   (u "SURVIVOR" by jmenový filtr podobnost s originálem viděl jako jméno a
 *   pustil by ho - proto je anglický hit vyhodnocený PŘED jmenovým filtrem).
 */
internal fun suspiciousCzechTokens(
    translated: String,
    original: String,
    dictionary: Set<String>,
    englishWords: Set<String> = emptySet(),
): List<String> {
    if (dictionary.isEmpty()) return emptyList()
    val originalTokens = original.split(LETTER_SPLIT)
        .filter { it.count(Char::isLetter) >= MIN_LINT_TOKEN_LENGTH }
        .map { normalizeLintToken(it) }
        .filter { it.isNotEmpty() }
    return translated.split(LETTER_SPLIT)
        .asSequence()
        .filter { it.count(Char::isLetter) >= MIN_LINT_TOKEN_LENGTH }
        .map { it.lowercase() }
        .distinct()
        .filter { token -> token !in dictionary }
        .filter { token ->
            // Anglický únik - nejdřív, aby ho jmenový filtr nemohl odpustit.
            if (token in englishWords) return@filter true
            val plain = normalizeLintToken(token)
            originalTokens.none { orig -> levenshteinAtMost(plain, orig, 2) }
        }
        .toList()
}

/**
 * Je překlad natolik podezřelý, že je lepší ukázat originál? Podezřelých tokenů
 * musí být ALESPOŇ TRETINA kontrolovaných slov - jedna vyjímaná podoba (vokativ,
 * který slovník nezná, neologismus) celý překlad neshodí, ale samotné
 * "ZATÍŽETE" (1 z 1) nebo "TO JSEM NEMYSL." (1 ze 2) ano.
 */
internal fun isSuspiciousCzechOutput(
    translated: String,
    original: String,
    dictionary: Set<String>,
    englishWords: Set<String> = emptySet(),
): Boolean {
    if (dictionary.isEmpty()) return false
    val checked = translated.split(LETTER_SPLIT).count { it.count(Char::isLetter) >= MIN_LINT_TOKEN_LENGTH }
    if (checked == 0) return false
    val flagged = suspiciousCzechTokens(translated, original, dictionary, englishWords).size
    // U krátkého bloku (<=5 kontrolovaných slov) stačí JEDNO podezřelé slovo -
    // třtinový práh tam byl moc shovinavý a nechal projít "K ZBRAĎ", "MYŠLEL",
    // "DVAJKRÁT", "HLUPAKI" (audit Vagabondu). U delší věty jeden vokativ/
    // neologismus celý překlad shodit nesmí - tam třetina zůstává.
    return flagged > 0 && (checked <= SHORT_BLOCK_CHECKED_MAX || flagged * 3 >= checked)
}

/**
 * Lint se pouští i na třípísmenná slova - "zbraď" ("zbraně"), "lís" ("nás")
 * z auditu by se s délkou 4 nikdy nezkontrolovala. Krátké tokeny jako "už",
 * "asi", "mě" jsou ve frekvenčním slovníku, takže false-positive riziko zůstává
 * malé - a jmenový filtr je pořád aktivní.
 */
private const val MIN_LINT_TOKEN_LENGTH = 3

/**
 * Kolik kontrolovaných tokenů ještě znamená "krátký blok", kde stačí jedno
 * podezřelé slovo k flagu - viz [isSuspiciousCzechOutput].
 */
private const val SHORT_BLOCK_CHECKED_MAX = 5

private val LETTER_SPLIT = Regex("[^\\p{L}]+")

/** Normalizace pro porovnání s originálem - lowercase + bez diakritiky (sdílí tabulku s [isArtTextEcho]). */
private fun normalizeLintToken(token: String): String = buildString(token.length) {
    for (c in token) {
        // Nejdřív lowercase - STRIP_DIACRITICS je klíčovaná malými znaky.
        val lower = c.lowercaseChar()
        val plain = STRIP_DIACRITICS[lower] ?: lower
        if (plain.isLetterOrDigit()) append(plain)
    }
}
