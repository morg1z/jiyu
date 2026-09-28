package com.haise.jiyu.util

/**
 * Značka pro stránky, jejichž skutečná URL obrázku se resolvuje líně přes
 * [com.haise.jiyu.source.MangaSource.getImageUrl] (MangaHome chapterfun, FanFox,
 * EHentai...). `Page.url` u takových zdrojů není obrázek, ale virtuální adresa
 * (HTML stránka, ashx endpoint) - čtečka i prefetch posílají URL do Coilu jako
 * prostý string, takže marker se kóduje do fragmentu: `{url}#jiyu_lazy={sourceId}/{index}`.
 * Fragment se nikdy neposílá po síti a [com.haise.jiyu.source.LazyPageFetcher] ho
 * před fetchem sejmě, zavolá `getImageUrl` a stáhne až vrácenou reálnou URL.
 */
object LazyPageUrl {
    private const val MARKER = "jiyu_lazy="

    fun encode(sourceId: String, index: Int, url: String): String =
        "$url#$MARKER$sourceId/$index"

    /** Rozbalí marker z Uri fragmentu → (sourceId, index) nebo null. */
    fun decodeFragment(fragment: String?): Pair<String, Int>? {
        if (fragment == null || !fragment.startsWith(MARKER)) return null
        val payload = fragment.removePrefix(MARKER)
        val slash = payload.indexOf('/')
        if (slash <= 0) return null
        val index = payload.substring(slash + 1).toIntOrNull() ?: return null
        return payload.substring(0, slash) to index
    }
}
