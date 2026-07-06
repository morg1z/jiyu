package com.haise.jiyu.util

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Mapuje výjimky ze zdrojů (MangaDex/ComicK/MangaPlus/Madara) na srozumitelnou
 * českou hlášku, aby uživatel viděl "server neodpovídá" místo syrové
 * Java výjimky nebo obecného "Chyba sítě" pro všechno.
 */
fun Throwable.toFriendlyMessage(): String = when (this) {
    is UnknownHostException    -> "Server nedostupný nebo špatná adresa zdroje"
    is SocketTimeoutException  -> "Vypršel časový limit připojení"
    is IOException             -> "Chyba sítě - zkontroluj připojení k internetu"
    else -> message?.takeIf { it.isNotBlank() } ?: "Neočekávaná chyba (${this::class.simpleName})"
}
