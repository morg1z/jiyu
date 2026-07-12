package com.haise.jiyu.util

/**
 * Normalizuje název mangy pro porovnávání napříč zdroji (case, diakritika, interpunkce).
 * Používá se pro detekci duplicit - stejná manga na dvou různých zdrojích.
 */
fun normalizeMangaTitle(title: String): String =
    title.lowercase()
        .replace(Regex("[^a-z0-9\\u00C0-\\u024F\\u0400-\\u04FF ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
