package com.haise.jiyu.translate

/**
 * Smí se tenhle termín, který model sám navrhl, uložit do glosáře?
 *
 * ## Proč to musí existovat
 * Glosář se plní AUTOMATICKY z pole `new_terms` v odpovědi modelu a v promptu je označený jako
 * závazný. Do teď se ukládalo všechno, co model vrátil, bez jediné kontroly - takže stačilo,
 * aby si tam jednou zapsal nesmysl, a od té chvíle si ho vnucoval ve všech dalších kapitolách.
 * Nahlášený případ: `SHUT YOUR MOUTH BEFORE I TEAR YOU APART.` se přeložilo jako
 * `ZAVŘI PÁNU, NEBO TĚ ROZTŘÍSKÁM.` - slovo "mouth" přitom žádný druhý význam nemá, takže
 * o nedorozumění nešlo; něco mu tam to slovo dosadilo.
 *
 * ## Proč je seznam běžných slov přijatelný právě tady
 * U rozpoznávání zvuků (viz [BubbleClassifier]) je ruční seznam slabina, protože false positive
 * SPOLKNE REPLIKU. Tady je to obráceně: nepřijatý termín znamená jen "nezapamatuje se sám",
 * uživatel ho pořád může přidat ručně v Slovníku. Chyba tedy nic nerozbije a seznam si to
 * může dovolit.
 *
 * Glosář má držet JMÉNA - postavy, místa, organizace, pojmenované techniky. Nic z toho není
 * "mouth" ani "count".
 */
fun isPlausibleGlossaryTerm(source: String, target: String): Boolean {
    val src = source.trim()
    val tgt = target.trim()
    if (src.length < 2 || tgt.isEmpty()) return false
    if (src.length > MAX_TERM_LENGTH || tgt.length > MAX_TERM_LENGTH) return false
    if (containsControlCharacters(src) || containsControlCharacters(tgt)) return false

    // Věta, ne termín. Jméno nemá čtyři slova ani koncovou interpunkci.
    if (src.split(Regex("\\s+")).size > MAX_TERM_WORDS) return false
    if (tgt.trimEnd().lastOrNull() in SENTENCE_PUNCTUATION) return false

    // Jednoslovný běžný výraz do glosáře jmen nepatří. U víceslovných názvů se kontroluje
    // celek, ne jednotlivá slova - "House of the Red Moon" je legitimní název, i když
    // "of" a "the" jsou běžná slova.
    if (src.lowercase() in COMMON_WORDS) return false

    return true
}

const val MAX_TERM_LENGTH = 48
const val MAX_TERM_WORDS = 5
private val SENTENCE_PUNCTUATION = setOf('.', '!', '?', ',', ';', ':')

/**
 * Jen limity délky/počtu slov - na rozdíl od [isPlausibleGlossaryTerm] BEZ seznamu běžných
 * slov. Ten seznam má smysl jen pro filtrování modelem navržených termínů (chránit glosář
 * před halucinací); ruční položku, kterou si uživatel sám napsal do Slovníku, nemá smysl
 * blokovat proto, že obsahuje běžné slovo - jen ji shora omezit v délce, ať nejde přes
 * `GlossaryRepository`/přímý DAO zápis (viz [com.haise.jiyu.ui.detail.MangaDetailViewModel])
 * propašovat cokoli extrémně dlouhého do promptu (nahlášeno v auditu).
 */
fun isWithinGlossaryTermLimits(source: String, target: String): Boolean {
    val src = source.trim()
    val tgt = target.trim()
    if (src.isEmpty() || tgt.isEmpty()) return false
    if (src.length > MAX_TERM_LENGTH || tgt.length > MAX_TERM_LENGTH) return false
    if (containsControlCharacters(src) || containsControlCharacters(tgt)) return false
    if (src.split(Regex("\\s+")).size > MAX_TERM_WORDS) return false
    return true
}

/**
 * Termín/pojem je vždy JEDEN řádek - vložený `\n`/`\r`/jiný řídicí znak by se v
 * [GeminiUltraPrompt]'s `glossaryBlock` propsal přímo do systémového promptu (escapuje se tam
 * jen `"`, ne řídicí znaky). Slovní limit výš to samo nezachytí - `\s+` bere `\n` jako
 * obyčejnou mezeru, takže víceřádkový text s málo "slovy" by jinak prošel.
 */
private fun containsControlCharacters(s: String): Boolean = s.any { it.isISOControl() }

/**
 * Běžná anglická slova, která nikdy nejsou vlastní jméno. Není to slovník - jen ta slova,
 * co se v komiksovém dialogu opakují nejčastěji, plus ta z nahlášeného případu.
 */
private val COMMON_WORDS = setOf(
    "mouth", "count", "guard", "tear", "apart", "shut", "scatter", "stay", "together",
    "hand", "eye", "eyes", "face", "head", "heart", "blood", "body", "life", "death",
    "man", "woman", "boy", "girl", "child", "people", "friend", "enemy", "master",
    "lord", "king", "queen", "god", "devil", "demon", "beast", "monster", "sword",
    "magic", "power", "level", "skill", "quest", "party", "guild", "world", "city",
    "town", "village", "house", "door", "road", "way", "time", "day", "night", "year",
    "thing", "things", "word", "words", "name", "story", "truth", "lie", "fear", "hope",
    "love", "hate", "pain", "help", "work", "money", "food", "water", "fire", "air",
    "earth", "light", "dark", "shadow", "sky", "sun", "moon", "star", "wind", "rain",
    "here", "there", "now", "then", "yes", "no", "not", "never", "always", "everyone",
    "someone", "nothing", "something", "everything", "anyone", "nobody",
)
