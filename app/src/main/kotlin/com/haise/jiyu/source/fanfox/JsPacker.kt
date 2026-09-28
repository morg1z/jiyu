package com.haise.jiyu.source.fanfox

/**
 * Dekóduje JS obfuskovaný přes Dean Edwards "packer"
 * (`eval(function(p,a,c,k,e,d){...}(payload,radix,count,words,0,{}))`).
 * FanFox/MangaFox tímhle skrývá skutečné URL obrázků v `chapterfun.ashx` odpovědi.
 */
object JsPacker {

    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    private fun toBase(num: Int, radix: Int): String {
        if (num < radix) return ALPHABET[num].toString()
        return toBase(num / radix, radix) + ALPHABET[num % radix]
    }

    fun unpack(payload: String, radix: Int, count: Int, words: List<String>): String {
        var p = payload
        for (c in count - 1 downTo 0) {
            val token = toBase(c, radix)
            val word = words.getOrElse(c) { "" }
            val replacement = word.ifEmpty { token }
            p = Regex("(?<![a-zA-Z0-9_])" + Regex.escape(token) + "(?![a-zA-Z0-9_])").replace(p, Regex.escapeReplacement(replacement))
        }
        return p
    }

    /**
     * Rozparsuje `eval(function(p,a,c,k,e,d){...}('payload',radix,count,'w1|w2|...'.split('|'),0,{}))`
     * a vrátí dekódovaný JS zdroj, nebo null pokud vstup neodpovídá packer formátu.
     */
    fun unpackEval(evalSource: String): String? {
        // Pozor: samotna '}' musi byt na Androidu escapovana (ICU regex, narozdil
        // od JDK, ji jako literal neprijme a hodi PatternSyntaxException) - proto
        // testy na JVM prochazi, ale na zarizeni tenhle regex padal.
        val match = Regex(
            """\}\('(.*)',(\d+),(\d+),'(.*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(evalSource) ?: return null
        val (payloadRaw, radixStr, countStr, wordsRaw) = match.destructured
        val payload = payloadRaw.replace("\\'", "'")
        val words = wordsRaw.split("|")
        return unpack(payload, radixStr.toInt(), countStr.toInt(), words)
    }
}
