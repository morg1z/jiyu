package com.haise.jiyu.util

import com.haise.jiyu.source.SourceRateLimitedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Volá se na začátku `catch (e: Exception)` ve zdrojích, které jinak polykají VŠECHNO a vrací prázdný
 * seznam: přehodí jen výjimky, které se polykat nesmějí - [CancellationException] (jinak by zrušená
 * korutina běžela dál a rozbila se strukturovaná konkurence) a [SourceRateLimitedException] (HTTP 429,
 * viz RateLimitInterceptor; bez přehození se hláška "příliš mnoho požadavků, zkus to za N s"
 * nikdy nedostala k uživateli a limit vypadal jako "žádné výsledky"). Ostatní chyby se dál polykají
 * jako dřív (audit nález JIYU-SRC-1).
 */
fun Exception.rethrowIfControl() {
    if (this is CancellationException || this is SourceRateLimitedException) throw this
}

/**
 * Web odpověděl úspěšně, ale očekávaný obsah (kontejner, selektor, JSON pole) v odpovědi není - typicky
 * změněné HTML. Je to [java.io.IOException], aby ho `RetryInterceptor` a chybové hlášení zpracovaly stejně
 * jako síťovou chybu. Zdroj ji NESMÍ spolknout do `emptyList()`: prázdná mřížka pak vypadá stejně jako
 * "tady nic není" a rozbitý zdroj zůstane nepovšimnut. Legitimně prázdný výsledek (hledání bez shody)
 * dál znamená prázdný seznam, ne tuhle výjimku.
 */
class SourceParseException(message: String, val url: String? = null) :
    java.io.IOException(if (url != null) "$message ($url)" else message)

/** Značka pro výjimky, které se NESMÍ automaticky opakovat (viz `RetryInterceptor`) - druhý pokus by nic nezměnil. */
interface NonRetryable

/** Zařízení je offline - požadavek se ani nezačne posílat (viz `NoNetworkInterceptor`). */
class NoNetworkException : java.io.IOException("Bez připojení k internetu"), NonRetryable

/**
 * Web je chráněný Cloudflare (nebo podobnou branou) a ověření se nepodařilo vyřešit - ať už proto, že výzva
 * potřebuje uživatele, který ji zrovna neviděl (pozadí), nebo proto, že selhala. Dřív tu interceptor vrátil
 * blokovanou odpověď a zdroj z ní nic nevyparsoval ("prázdný výpis"). Teď je to výjimka, na kterou umí UI nabídnout
 * tlačítko "Vyřešit ověření" ([host] a [url] se k tomu hodí). Nezkouší se znovu automaticky ([NonRetryable]).
 */
open class CloudflareException(val host: String, val url: String, message: String) :
    java.io.IOException(message), NonRetryable

/** Výzvu nešlo vyřešit (cooldown po neúspěchu, zrušené volání, pozadí bez interakce). Uživatel ji může spustit ručně. */
class CloudflareProtectedException(host: String, url: String) :
    CloudflareException(host, url, "Web je chráněný Cloudflare a ověření se nepodařilo vyřešit ($host)")

/** Web přístup natvrdo zablokoval (IP/rate-limit, ne řešitelná výzva) - ruční řešení nepomůže. */
class CloudflareBlockedException(host: String, url: String) :
    CloudflareException(host, url, "Web zablokoval přístup z tohoto zařízení ($host)")

/** Zdroj vyžaduje přihlášení. UI nabídne otevření webu zdroje v appce (viz `ErrorAction.OpenSourceWeb`). */
class AuthRequiredException(val sourceId: String, val url: String) :
    java.io.IOException("Zdroj vyžaduje přihlášení"), NonRetryable

/** Zdroj potřebuje jednorázovou akci uživatele na webu (captcha, souhlas, přihlášení bez cookie). */
class InteractiveActionRequiredException(val sourceId: String, val url: String) :
    java.io.IOException("Zdroj vyžaduje akci na webu"), NonRetryable

/** Web zdroje se přestěhoval na jinou adresu - viz `MirrorProbe`. [newHost] je nabízená nová doména. */
class SourceMovedException(val sourceId: String, val newHost: String) :
    java.io.IOException("Web se přestěhoval na $newHost"), NonRetryable
