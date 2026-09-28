package com.haise.jiyu.translate

/**
 * Spojí slovo, které lettering rozdělil na konci řádku pomlčkou.
 *
 * Nahlášený případ: bublina `EVERY-` / `ONE DON'T SCATTER, STAY TOGETHER!` dorazila k modelu
 * jako `EVERY- ONE DON'T SCATTER...`, tedy s rozsypaným začátkem věty - a překlad z ní vyšel
 * `VŠICHNI SE ROZPTÝLEJTE, ZŮSTÁVEJTE SPOLU!`, což si odporuje samo v sobě.
 *
 * Pomlčka se ZÁMĚRNĚ NEMAŽE, jen se odstraní zalomení za ní. Rozdíl mezi dělením slova
 * (`EVERY-` + `ONE`) a skutečným spojovníkem (`well-` + `known`) z textu nepoznáme, ale
 * ponechání pomlčky je správně v obou případech: `EVERY-ONE` model přečte bez potíží a
 * `well-known` zůstane přesně tím, čím je. Smazat pomlčku by druhý případ rozbilo.
 */
fun joinHyphenatedLineBreaks(text: String): String =
    HYPHEN_AT_LINE_END.replace(text) { m -> m.groupValues[1] + "-" }

/** Písmeno, pomlčka, konec řádku (a případné mezery), následované písmenem. */
private val HYPHEN_AT_LINE_END = Regex("(\\p{L})-[ \\t]*\\r?\\n[ \\t]*(?=\\p{L})")

/**
 * Opraví typické OCR chyby latinkového letteringu, které pak model věrně přeloží jako nesmysl
 * (nahlášeno: "I'M FINISHED." přečtené jako "IM FINISHED." -> "Im skončil").
 *
 * Jen VERZÁLKOVÉ celé slovo a jen bez CJK textu - běžné zkratky bez apostrofu ("IM", "DONT", "CANT")
 * lettering nikdy nepíše bez něj, kdežto malá písmena ("im", "cant") mohou být skutečná slova.
 * Úvodní osamocené tečky před slovem ("..FORGET", ".FOGET") jsou zbytek výpustky "...", ne interpunkce.
 */
fun cleanOcrLatinText(text: String, dictionary: Set<String> = emptySet()): String {
    if (text.any { it.code >= 0x3000 }) return text
    var result = LEADING_STRAY_DOTS.replace(text, "...")
    result = UPPERCASE_CONTRACTIONS.replace(result) { m ->
        UPPERCASE_CONTRACTION_FIXES.getValue(m.value)
    }
    result = OCR_WORD_FIXES.replace(result) { m ->
        OCR_WORD_FIX_TABLE.getValue(m.value)
    }
    result = joinIntraLineHyphens(result, dictionary)
    return WORD_TOKEN.replace(result) { m ->
        fixByDictionary(fixNoisyUpperToken(m.value), dictionary)
    }
}

/**
 * Pomlčka následovaná mezerou UVNITŘ řádku - letteringové dělení slova, které
 * [joinHyphenatedLineBreaks] nevidí, protože spojuje jen přes zlom řádku ("\n").
 * Audit Vagabondu: "VAL- LAGE", "MUSH- ROOM", "RELENT- LESSLY" dorazily k modelu
 * rozpadlé a druhá půlka se přeložila doslova ("ROOM" -> "MÍSTNOST" v názvu houby).
 *
 * Spojí se jen se slovníkovým důkazem (spojenec je slovo, nebo má unikátního
 * souseda na vzdálenost 1 - stejná konzervativita jako [fixByDictionary]).
 * Bez důkazu se jen zruší mezera za pomlčkou ("WAIT- WHAT" -> "WAIT-WHAT") -
 * skutečný spojovník "well- known" tak přežije a model čte kompakt tvar líp.
 */
internal fun joinIntraLineHyphens(text: String, dictionary: Set<String> = emptySet()): String =
    INTRA_LINE_HYPHEN.replace(text) { m ->
        val left = m.groupValues[1]
        val right = m.groupValues[2]
        val joined = left + right
        if (dictionary.isNotEmpty() && !BubbleClassifier.isKnownSfx(joined.uppercase())) {
            val hit = when {
                joined.lowercase() in dictionary -> joined
                else -> uniqueDistance1Word(joined.lowercase(), dictionary)
            }
            if (hit != null) {
                return@replace if (joined.all { !it.isLetter() || it.isUpperCase() }) {
                    hit.uppercase()
                } else {
                    hit.replaceFirstChar { if (left.first().isUpperCase()) it.uppercase() else it.lowercase() }
                }
            }
        }
        "$left-$right"
    }

/** Písmena, pomlčka, mezera(y) bez zalomení, písmena - "VAL- LAGE" na jednom řádku. */
private val INTRA_LINE_HYPHEN = Regex("(\\p{L}{2,})- +(\\p{L}{2,})")

private val LEADING_STRAY_DOTS = Regex("^\\.{1,2}(?=\\p{L})")

private val UPPERCASE_CONTRACTION_FIXES = mapOf(
    "IM" to "I'M", "DONT" to "DON'T", "CANT" to "CAN'T", "DIDNT" to "DIDN'T", "ISNT" to "ISN'T",
    "WASNT" to "WASN'T", "COULDNT" to "COULDN'T", "WOULDNT" to "WOULDN'T", "SHOULDNT" to "SHOULDN'T",
    "THATS" to "THAT'S", "WHATS" to "WHAT'S", "YOURE" to "YOU'RE", "THEYRE" to "THEY'RE",
    "YOUVE" to "YOU'VE",
)

private val UPPERCASE_CONTRACTIONS = Regex("(?<![\\p{L}'’])(" + UPPERCASE_CONTRACTION_FIXES.keys.joinToString("|") + ")(?![\\p{L}'’])")

/**
 * Jednotlivá slova, kde OCR pokaždé zamění stejný znak (pozorované na telefonu):
 * koncové G místo S ("WANTG", "COMEG"), přilepené písmeno z okolní sazby ("MLURDER",
 * "WEXT") a nula místo O v názvu eventu ("K.0." -> "K.O."). Bezpečné jen jako přesný
 * slovník - obecné pravidlo "G na konci -> S" by rozbilo KING/THING/SING.
 */
private val OCR_WORD_FIX_TABLE = mapOf(
    "WANTG" to "WANTS", "COMEG" to "COMES", "MLURDER" to "MURDER", "WEXT" to "NEXT",
    "K.0" to "K.O",
    // Vagabond audit (ch1+ch2): lettering s ozdobným R/S čtečky pokaždé rozbije.
    // Slovníkový lint tyhle případy chytne taky, ale tabulka funguje i bez načteného
    // slovníku a je na nic, tedy zůstává jako první rychlá linie.
    "FOGET" to "FORGET", "SIRVIVOR" to "SURVIVOR", "SHURE" to "SURE",
)

// Klíče se do regexu vkládají escapované - "K.0" by jako vzor jinak matchovalo i "KX0".
private val OCR_WORD_FIXES = Regex(
    "(?<![\\p{L}'’])(" + OCR_WORD_FIX_TABLE.keys.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}'’])",
)

private val WORD_TOKEN = Regex("\\S+")

/**
 * Slovníkový lint nad verzálkovými tokeny: token, který ve slovníku není a má PRÁVĚ
 * JEDNOHO souseda v editovací vzdálenosti 1 (výměna/vložení/smazání jednoho písmene),
 * se opraví na něj. Audit Vagabondu: "VAL-LAGE" (letteringový artefakt pomlčky
 * UVNITŘ řádku, joinHyphenatedLineBreaks spojuje jen přes \n) → "VILLAGE".
 *
 * Konzervativní hranice (jinak by lint kazil skutečná slova i zvuky):
 *  - jen čistě verzálkové jádro ≥4 písmen; apostrofy/tečky/číslice vylučují účast
 *    (kontrakce řeší [UPPERCASE_CONTRACTIONS], zkratky jsou nejednoznačné),
 *  - slovo, co ve slovníku JE, se nikdy nedotkne (smite→smile nehrozí),
 *  - známé SFX se nedotkne - "THUD" ve slovníku není a "THUS" je na vzdálenost 1
 *    (viz [BubbleClassifier.isKnownSfx]),
 *  - více kandidátů na vzdálenost 1 → nechat být (nejde rozhodnout).
 *
 * Pomlčka uvnitř tokenu ("VAL-LAGE") se pro dotaz odstraní; oprava se vrací
 * bez ní - letteringové dělení slova v půli řádku je artefakt, ne spojovník.
 */
private fun fixByDictionary(token: String, dictionary: Set<String>): String {
    if (dictionary.isEmpty()) return token
    val start = token.indexOfFirst { it.isLetter() }
    val end = token.indexOfLast { it.isLetter() }
    if (start < 0) return token
    val core = token.substring(start, end + 1)
    if (core.length < 4 || core.length > 16) return token
    if (!core.all { it in 'A'..'Z' || it == '-' }) return token
    if (core.startsWith('-') || core.endsWith('-') || "--" in core) return token
    val joined = core.replace("-", "")
    if (joined.length < 4) return token
    if (joined.lowercase() in dictionary) return token
    if (BubbleClassifier.isKnownSfx(joined)) return token
    val hit = uniqueDistance1Word(joined.lowercase(), dictionary) ?: return token
    return token.take(start) + hit.uppercase() + token.drop(end + 1)
}

/**
 * Slovo ze slovníku na editační vzdálenost přesně 1 - jediné, jinak null.
 * Dřívější exit při druhém zásahu: výsledek "víc než jeden" se nikdy nevrací.
 */
private fun uniqueDistance1Word(word: String, dictionary: Set<String>): String? {
    var hit: String? = null
    fun consider(candidate: String): Boolean {
        if (candidate !in dictionary || candidate == hit) return true
        if (hit != null) { hit = null; return false }
        hit = candidate
        return true
    }
    // Výměna jednoho písmene.
    for (i in word.indices) {
        for (c in 'a'..'z') {
            if (c != word[i] && !consider(word.substring(0, i) + c + word.substring(i + 1))) return null
        }
    }
    // Smazání jednoho písmene.
    for (i in word.indices) {
        if (!consider(word.substring(0, i) + word.substring(i + 1))) return null
    }
    // Vložení jednoho písmene.
    for (i in 0..word.length) {
        for (c in 'a'..'z') {
            if (!consider(word.substring(0, i) + c + word.substring(i))) return null
        }
    }
    return hit
}

/**
 * OCR šum uvnitř VERZÁLKOVÉHO slova - pozorované na telefonu: číslice `6`/`0` místo
 * `S`/`O` ("MAKE6 IT", "6UPERMAX", "THAT'6", "K.0.") a jedno malé písmeno vsunuté do
 * verzálek ("GENETIc6", "cOULD", "GUy"). Malé `l` se mapuje na `I` - v komiksovém
 * letteringu je "I" holá čárka, takže ML Kit vypíše "l" ("LIMlTS" -> "LIMITS"); obě
 * možnosti jsou nejednoznačné, ale "I" uvnitř slova je častější.
 *
 * Konzervativní hranice, aby se nerozbila skutečná slova a kódy:
 *  - aspoň 3 písmena a aspoň 2 verzálky (jinak "iOS"-like nebo příliš krátké tokeny),
 *  - max 1 malé písmeno (víc = skutečný mixed-case, např. "McCready", "iPhone"),
 *  - číslice jen 6/0 a max 1 v tokenů ("R2D2", "C-3PO", "F-16" zůstanou) a při číslici
 *    aspoň 4 písmena ("APT6", "6FT" zůstanou),
 *  - token bez `#`/`@` apod. - tam je šum nejednoznačný ("AN#l").
 */
private fun fixNoisyUpperToken(token: String): String {
    val start = token.indexOfFirst { it.isLetterOrDigit() }
    val end = token.indexOfLast { it.isLetterOrDigit() }
    if (start < 0) return token
    val core = token.substring(start, end + 1)
    if (core.length < 3 || core.any { !(it.isLetterOrDigit() || it in ".'’-") }) return token
    val letters = core.count { it.isLetter() }
    val upper = core.count { it.isUpperCase() }
    val lower = core.count { it.isLowerCase() }
    val digits = core.count { it.isDigit() }
    val fixableDigits = core.count { it == '6' || it == '0' }
    if (upper < 2 || lower > 1 || digits != fixableDigits) return token
    if (digits > 1 || (digits == 1 && letters < 4)) return token
    if (digits == 0 && lower == 0) return token
    val fixed = buildString(core.length) {
        for (c in core) append(
            when {
                c == '6' -> 'S'
                c == '0' -> 'O'
                c == 'l' -> 'I'
                c.isLowerCase() -> c.uppercaseChar()
                else -> c
            },
        )
    }
    return token.take(start) + fixed + token.drop(end + 1)
}

/** Čistí OCR text všech bloků stránky - viz [cleanOcrLatinText]. */
fun List<RawTextBlock>.withCleanedOcrText(dictionary: Set<String> = emptySet()): List<RawTextBlock> =
    map { block ->
        val cleaned = cleanOcrLatinText(block.text, dictionary)
        if (cleaned == block.text) block else block.copy(text = cleaned)
    }
