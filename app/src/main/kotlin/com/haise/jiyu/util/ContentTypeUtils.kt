package com.haise.jiyu.util

/**
 * Sjednocené rozpoznání typu obsahu (MANGA/MANHWA/MANHUA/NOVEL) z textového popisku webu -
 * stejná `when` větev byla nezávisle zkopírovaná do ~20 zdrojových tříd (audit nalez
 * "normalizeContentType duplikovaná napříč zdroji"). `default` řeší, že různé weby mají různý
 * převažující typ obsahu, takže když text nesedí na žádnou známou hodnotu, mají se chovat jinak
 * (web zaměřený na manhwa má chybějící/neznámý typ raději brát jako "MANHWA" než "MANGA").
 *
 * Zdroje s odlišnou logikou (ne jen jiným defaultem) - MadaraSource/ManhwaBuddySource/
 * ToongodSource (nullable návratová hodnota, `else -> null` má jiný význam pro volajícího) a
 * ValirScansSource (širší množina NOVEL synonym) - si nechávají vlastní implementaci.
 */
fun normalizeContentType(text: String?, default: String = "MANGA"): String = when (text?.trim()?.lowercase()) {
    "manga" -> "MANGA"
    "manhwa" -> "MANHWA"
    "manhua" -> "MANHUA"
    "novel", "light novel" -> "NOVEL"
    else -> default
}
