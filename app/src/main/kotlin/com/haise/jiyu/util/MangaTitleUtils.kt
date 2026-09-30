package com.haise.jiyu.util

/**
 * Normalizuje název mangy pro porovnávání napříč zdroji (case, diakritika, interpunkce).
 * Používá se pro detekci duplicit - stejná manga na dvou různých zdrojích.
 *
 * NFKD dekompozice + odstranění combining diacritics (`\p{Mn}`) sjednotí přepisy jako
 * "café"/"cafe" - bez tohohle i drobný rozdíl v diakritice znamenal falešnou neshodu.
 * Povolený znakový rozsah kromě Latin/Cyrillic zahrnuje i Hiraganu/Katakanu, CJK Unified
 * Ideographs a Hangul - dřív se tyhle znaky mazaly úplně, takže titul evidovaný jinde jen
 * v původním písmu (bez romanizace) se nikdy nespároval (nahlášený bug).
 */
fun normalizeMangaTitle(title: String): String {
    // Jen U+0300-U+036F ("Combining Diacritical Marks", tj. latinske/cyrilicke prizvuky) -
    // NFKD dekompozice totiz stejnym mechanismem rozklada i japonske znely/polozvucne
    // katakana/hiragana znaky (dakuten/handakuten, U+3099/U+309A) na zakladni znak +
    // kombinujici znamenko. Siroke \p{Mn} by tak omylem "opravilo" napr. "ベ" (be) na
    // "ヘ" (he) - jiny zvuk, ne kosmeticky detail. NFC na konci zpetne slozi vse, co
    // nebylo v cilenem rozsahu (japonske znely znaky), zpet do puvodni jednoznakove podoby.
    val withoutDiacritics = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKD)
        .replace(Regex("[\\u0300-\\u036F]+"), "")
    val recomposed = java.text.Normalizer.normalize(withoutDiacritics, java.text.Normalizer.Form.NFC)
    return recomposed.lowercase()
        .replace(Regex("[^a-z0-9\\u00C0-\\u024F\\u0400-\\u04FF\\u3040-\\u30FF\\u4E00-\\u9FFF\\uAC00-\\uD7A3 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * Slabý "podobný název" filtr pro agregované hledání - některé zdroje dotaz
 * ignorují a místo výsledků vrátí svůj popular listing ("symbie" -> IDW Sonic).
 * Název projde, když obsahuje celý normalizovaný dotaz, je název podřetězec
 * dotazu ("absolute batman issue 1" vs "absolute batman"), nebo trefí aspoň
 * polovinu smysluplných tokenů dotazu (>=3 znaky) - u dotazu s jedním tokenem
 * stačí jeho jediný hit (dřív stačil JEDINÝ hit i u vícetokenového dotazu, takže
 * "solo leveling ragnarok" prošlo i přes "Ragnarok Online" - audit AGG-1).
 * Dotaz ze samých krátkých slov nefiltruje.
 */
fun titleMatchesQuery(title: String, query: String): Boolean {
    val qt = normalizeMangaTitle(query)
    if (qt.isBlank()) return true
    val tt = normalizeMangaTitle(title)
    if (tt.isBlank()) return false
    if (tt.contains(qt) || (tt.length >= MIN_QUERY_TOKEN_LEN && qt.contains(tt))) return true
    val tokens = qt.split(' ').filter { it.length >= MIN_QUERY_TOKEN_LEN }.distinct()
    if (tokens.isEmpty()) return true
    val matched = tokens.count { tt.contains(it) }
    return matched * 2 >= tokens.size
}

private const val MIN_QUERY_TOKEN_LEN = 3
