package com.haise.jiyu.util

/**
 * Co může uživatel udělat s chybou, kromě obyčejného "zkusit znovu". Vychází z typu výjimky
 * (viz [toErrorAction]) - UI podle toho přidá tlačítko a `ErrorActionHandler` akci provede.
 */
sealed interface ErrorAction {
    /** Vyřešit Cloudflare ověření (viditelný dialog s WebView). */
    data class SolveCloudflare(val url: String) : ErrorAction

    /** Použít novou adresu zdroje (zdroj se přestěhoval). */
    data class UseNewDomain(val sourceId: String, val host: String) : ErrorAction

    /** Otevřít web zdroje v appce (přihlášení / jednorázová akce). */
    data class OpenSourceWeb(val sourceId: String, val url: String) : ErrorAction
}

/** Akce vhodná pro tuhle chybu, nebo `null`, když stačí obyčejné zopakování. Hledá i v obalených příčinách. */
fun Throwable.toErrorAction(): ErrorAction? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 6) {
        when (t) {
            is CloudflareBlockedException -> return null // ruční řešení nepomůže
            is CloudflareProtectedException -> return ErrorAction.SolveCloudflare(t.url)
            is SourceMovedException -> return ErrorAction.UseNewDomain(t.sourceId, t.newHost)
            is AuthRequiredException -> return ErrorAction.OpenSourceWeb(t.sourceId, t.url)
            is InteractiveActionRequiredException -> return ErrorAction.OpenSourceWeb(t.sourceId, t.url)
        }
        t = t.cause?.takeIf { it !== t }
        depth++
    }
    return null
}
