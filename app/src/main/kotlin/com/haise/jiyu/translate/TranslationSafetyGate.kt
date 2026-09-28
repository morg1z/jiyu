package com.haise.jiyu.translate

import android.util.Log

/**
 * Poslední obranná vrstva mezi "vyrobeným blokem" a čtenářem.
 *
 * ## Proč existuje
 * Audit Vagabondu ukázal, že se do vyrenderovaného textu dostaly věci, které tam neměly
 * co dělat - až po několika vrstvách kontrol nad tím:
 *  - interní/placeholder tokeny protekly do výstupu ("__g8__", "__g10__" na TOC stránce;
 *    glosářové placeholdery tvaru `__JIYU_PROTECT_0__` / `⟦JIYU_PROTECT_0⟧` by udělaly
 *    totéž, kdyby se restore nestal),
 *  - model v pozičně párované dávce posunul odpovědi o slot, takže se do bubliny
 *    vykreslil text, který doslova byl zdrojem JINÉ bubliny ("BYLI JSME ZLODĚJI" se
 *    objevilo v bublině, kde mělo být "...a sbíráme meče"),
 *  - dvojtečkové výpustky ".." místo "…".
 *
 * Každá z předchozích vrstev (prompt pravidla, parser, lint) takový výstup mohla
 * pustit - gate je proto čistě mechanický a na posledním místě před uložením do cache:
 * rozhoduje výhradně podle textu na bloku, žádná důvěra v to, kdo ho vyrobil.
 *
 * Princip: cokoliv gate označí skončí jako `isUntranslated` = čtenář uvidí originální
 * sazbu, ne rozbitou "překladovou" vrstvu. To je striktně lepší selhání.
 */

/**
 * Placeholder/sentinel tvary, které nesmí nikdy dojít k čtenáři:
 *  - `__anything__`    - zdvojené podtržítko + slovo (modelovy halucinované "fix tokeny"
 *                        typu `__g8__`, glosářové markery `__JIYU_PROTECT_0__`)
 *  - `⟦...⟧`           - glosářové placeholdery druhého tvaru
 *  - `[UNTRANSLATED]`  - sentinel, který má model vrátit místo hádaného překladu
 *    (viz [GeminiUltraPrompt.UNTRANSLATED_MARKER]) - má se zachytit dřív, ale render
 *    gate na něj nevěří.
 *
 * Pozor na false positive: `____` (čtyři podtržítka, vyplňovací čára) tvar nesplňuje -
 * mezi `__` a `__` musí být alespoň jeden alfanumerický znak.
 */
internal val LEAKED_TOKEN_REGEX = Regex(
    "__[\\p{L}\\p{N}_]+__|⟦[^⟧]{0,80}⟧|\\[\\s*UNTRANSLATED[\\p{L}\\p{N}_\\s]*]",
    RegexOption.IGNORE_CASE,
)

fun hasLeakedToken(text: String): Boolean = LEAKED_TOKEN_REGEX.containsMatchIn(text)

/**
 * Normalizovaný klíč textu pro porovnání "překlad == zdroj jiné bubliny" - jen písmena a
 * číslice, lowercase, bez diakritiky (stejná normalizace jako [isArtTextEcho]).
 */
private fun echoKey(text: String): String = buildString(text.length) {
    for (c in text) {
        // Nejdřív lowercase - STRIP_DIACRITICS je klíčovaná malými znaky.
        val lower = c.lowercaseChar()
        val plain = STRIP_DIACRITICS[lower] ?: lower
        if (plain.isLetterOrDigit()) append(plain)
    }
}

/**
 * Indexy bloků, jejichž "překlad" je doslovný echo zdroje JINÉ bubliny dávky.
 *
 * Poziční cesty providerů (Groq/Byok/on-device) párují odpovědi podle POŘADÍ - když model
 * odpověď posune (vynechá položku, sloučí dvě bubliny do jedné), dostane bublina i text
 * určený bublině j. Takový posun je v přímém porovnání mezi jazyky neviditelný - ale prozradí
 * ho okamžik, kdy model do "překladového" pole zkopíruje ORIGINÁLNÍ text cizí bubliny
 * (typický při zacyklení na echu vstupu). Tenhle vzor v auditu znamenal rozházenou
 * zlodějskou sekvenci Vagabonda.
 *
 * Legitimní kolize nehrozí: český překlad se nikdy nerovná anglickému zdroji jiné
 * bubliny znak po znaku (po normalizaci bez interpunkce/diakritiky).
 */
internal fun findShiftedEchoIndices(blocks: List<TranslatedBlock>): Set<Int> {
    val sourceKeys = blocks.map { echoKey(it.originalText) }
    return blocks.indices.filterTo(HashSet()) { i ->
        val b = blocks[i]
        if (b.isSfx || b.isUntranslated || b.isArtText) return@filterTo false
        val key = echoKey(b.translatedText)
        if (key.length < 4) return@filterTo false // příliš krátké na smysluplný důkaz
        sourceKeys.indices.any { j -> j != i && sourceKeys[j] == key }
    }
}

/** ".." / "..." / "...." -> jedna výpustka "…" (audit: "PŘEŽIL.." vykreslené se dvěma tečkami). */
private val MULTI_DOT = Regex("\\.{2,}")
private fun normalizeEllipsis(text: String): String = MULTI_DOT.replace(text, "…")

/**
 * Aplikuje render gate na hotový seznam bloků.
 *
 * - Blok s placeholder/sentinel tokenem v překladu -> `isUntranslated` + texty zpět na
 *   originál (čtenář uvidí původní sazbu místo `__g8__`).
 * - Blok, jehož překlad je echo zdroje JINÉ bubliny -> `isUntranslated` (obsah patří
 *   jinam, ukázat ho tady by znamenalo zobrazení cizí věty ve špatné bublině).
 * - Zbylé bloky dostanou kosmetickou normalizaci dvojitých teček.
 *
 * SFX/art/untranslated bloky se přeskakují - render je tak jako tak neprovede.
 */
internal fun List<TranslatedBlock>.applyRenderSafetyGate(): List<TranslatedBlock> {
    val shifted = findShiftedEchoIndices(this)
    return mapIndexed { i, b ->
        if (b.isSfx || b.isArtText) return@mapIndexed b
        if (i in shifted) {
            Log.w(TAG, "shifted_echo: blok $i obsahuje zdroj cizí bubliny -> ponechávám originál")
            b.copy(
                translatedText = b.originalText,
                displayText = b.originalText,
                isUntranslated = true,
            )
        } else if (!b.isUntranslated &&
            (hasLeakedToken(b.translatedText) || hasLeakedToken(b.displayText))) {
            Log.w(TAG, "leaked_token: blok $i obsahuje placeholder -> ponechávám originál")
            b.copy(
                translatedText = b.originalText,
                displayText = b.originalText,
                isUntranslated = true,
            )
        } else if (!b.isUntranslated) {
            val fixed = normalizeEllipsis(b.translatedText)
            if (fixed == b.translatedText) b else b.copy(
                translatedText = fixed,
                displayText = normalizeEllipsis(b.displayText),
            )
        } else b
    }
}

private const val TAG = "TranslateSafetyGate"
