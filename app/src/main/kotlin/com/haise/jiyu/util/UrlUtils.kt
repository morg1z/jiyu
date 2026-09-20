package com.haise.jiyu.util

import java.net.URI

private fun hostKey(url: String): String? =
    runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")

private fun originOf(base: String): String {
    val uri = runCatching { URI(base) }.getOrNull()
    val scheme = uri?.scheme
    val host = uri?.host
    return if (scheme != null && host != null) {
        buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
    } else {
        base.trimEnd('/')
    }
}

/**
 * Adresa titulu/kapitoly tak, jak se ukládá do DB: absolutní URL téhož webu (bez ohledu na `www.` a
 * http/https) se zkrátí na cestu + query, protokolově relativní `//host/x` se dorovná na `https:`.
 * Adresa JINÉHO hostitele zůstane absolutní. Dřív se používalo holé `removePrefix(base)`, které
 * odstranilo jen přesnou shodu s `base` - mirror, `www` nebo jiné schéma pak zůstaly v ceste celé
 * a požadavek `"$base$url"` skládal nesmyslnou adresu.
 */
fun toSourcePath(base: String, href: String): String {
    val h = href.trim()
    val absolute = when {
        h.startsWith("//") -> "https:$h"
        h.startsWith("http://", ignoreCase = true) || h.startsWith("https://", ignoreCase = true) -> h
        else -> return h
    }
    val uri = runCatching { URI(absolute) }.getOrNull() ?: return absolute
    val baseHost = hostKey(base)
    if (baseHost == null || hostKey(absolute) != baseHost) return absolute
    return buildString {
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** Adresa pro požadavek: absolutní zůstane, `//host` dostane `https:`, cesta se připojí k [base]. */
fun resolveSourceUrl(base: String, url: String): String = when {
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
    url.startsWith("//") -> "https:$url"
    url.isEmpty() || url.startsWith("/") || url.startsWith("?") || url.startsWith("#") -> base + url
    else -> base.trimEnd('/') + "/" + url
}

/**
 * URL obrázku/obálky: prázdné a `data:` se zahodí (`null`), `//cdn/x` dostane `https:`, `/x` se připojí
 * k původu [base]. Relativní cesta bez `/` se nedá spolehlivě vyřešit bez adresy dokumentu, takže se zahodí.
 * Dřív se každé URL nezačínající `http` zahodilo bez pokusu o vyřešení.
 */
fun absoluteMediaUrl(base: String, raw: String?): String? {
    val r = raw?.trim().orEmpty()
    return when {
        r.isEmpty() || r.startsWith("data:", ignoreCase = true) -> null
        r.startsWith("http://", ignoreCase = true) || r.startsWith("https://", ignoreCase = true) -> r
        r.startsWith("//") -> "https:$r"
        r.startsWith("/") -> originOf(base) + r
        else -> null
    }
}
