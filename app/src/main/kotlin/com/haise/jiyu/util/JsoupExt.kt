package com.haise.jiyu.util

import org.jsoup.nodes.Element

/** Atributy, do kterých weby s lazy-loadingem dávají skutečnou URL obrázku (`src` bývá placeholder). */
private val LAZY_IMAGE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "data-lazy", "src")

/**
 * URL obrázku z `<img>`/elementu s lazy-loadingem: první neprázdná hodnota z `data-src`, `data-lazy-src`,
 * `data-original`, `data-cfsrc`, `src`; pak první URL ze `srcset`. Placeholdery `data:` se přeskočí.
 * Vrací hodnotu tak, jak je na webu (relativní zůstane relativní) - k absolutní se převádí přes
 * [absoluteMediaUrl] / [resolveSourceUrl].
 */
fun Element.lazySrc(): String? {
    for (name in LAZY_IMAGE_ATTRS) {
        val v = attr(name).trim()
        if (v.isNotEmpty() && !v.startsWith("data:", ignoreCase = true)) return v
    }
    for (name in listOf("data-srcset", "srcset")) {
        val first = attr(name).split(',').firstOrNull()?.trim()?.substringBefore(' ')?.trim()
        if (!first.isNullOrEmpty() && !first.startsWith("data:", ignoreCase = true)) return first
    }
    return null
}

/**
 * Jako `selectFirst`, ale chybějící element je chyba parseru ([SourceParseException]), ne tichý `null`.
 * [url] jen zpřesní hlášení.
 */
fun Element.selectFirstOrThrow(css: String, url: String? = null): Element =
    selectFirst(css) ?: throw SourceParseException("Selektor '$css' nenalezen", url)
