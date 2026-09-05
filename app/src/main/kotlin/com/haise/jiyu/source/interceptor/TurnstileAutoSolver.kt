package com.haise.jiyu.source.interceptor

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Automatický solver pro Cloudflare Turnstile / interaktivní CAPTCHA.
 *
 * Funguje tak, že provede GET na challenge URL a zkusí získat `cf_clearance`
 * cookie přímo z response (Set-Cookie header). Pokud se nepodaří, retry s
 * exponenciálním backoffem. Po vyčerpání pokusů vrátí null a volající
 * (CloudflareChallengeBridge) eskaluje na UI dialog.
 *
 * Používá vlastní [OkHttpClient] s in-memory CookieJar, aby nezasahoval
 * do cookie jaru hlavního klienta.
 */
internal object TurnstileAutoSolver {

    private const val MAX_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 1_500L
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 15L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .build()
    }

    private val cookieJar = InMemoryCookieJar()

    /**
     * Zkusí automaticky vyřešit Cloudflare challenge.
     *
     * @param challengeUrl URL challenge stránky (ta, na kterou OkHttp dostal 403/redirect)
     * @param host hostname (pro logování)
     * @return cookies string (např. "cf_clearance=abc123; path=/; HttpOnly") nebo null při selhání
     */
    fun trySolve(challengeUrl: String, host: String): String? {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val request = Request.Builder()
                    .url(challengeUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response: Response = client.newCall(request).execute()
                response.use { res ->
                    val code = res.code
                    val setCookies = res.headers("Set-Cookie")

                    val cfClearance = setCookies.firstOrNull { c -> c.startsWith("cf_clearance=") }

                    if (cfClearance != null) {
                        return cfClearance
                    }

                    // 403 s cf-chl = challenge stále aktivní, retry
                    if (code == 403 || code == 503) {
                        val cfChl = res.header("cf-chl")
                        if (cfChl != null && attempt < MAX_ATTEMPTS) {
                            delay(BASE_DELAY_MS * (1L shl (attempt - 1)))
                            continue
                        }
                    }

                    // 200 ale bez cf_clearance v Set-Cookie – zkus cookie jar
                    if (code == 200) {
                        val jarCookie = cookieJar.cookiesFor(challengeUrl)
                            .firstOrNull { it.name == "cf_clearance" }
                        if (jarCookie != null) {
                            return "${jarCookie.name}=${jarCookie.value}; path=/"
                        }
                    }
                }
            } catch (e: Exception) {
                // Network error – retry
                if (attempt < MAX_ATTEMPTS) {
                    delay(BASE_DELAY_MS * (1L shl (attempt - 1)))
                    continue
                }
                return null
            }
        }
        return null
    }

    private fun delay(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Jednoduchý in-memory CookieJar pro izolaci od hlavního klienta. */
    private class InMemoryCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) {
            synchronized(cookies) {
                val list = cookies.getOrPut(url.host) { mutableListOf() }
                val names = responseCookies.map { it.name }.toSet()
                list.removeAll { it.name in names }
                list.addAll(responseCookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(cookies) {
                return cookies[url.host]?.toList() ?: emptyList()
            }
        }

        fun cookiesFor(url: String): List<Cookie> {
            val host = try {
                HttpUrl.parse(url)?.host ?: ""
            } catch (_: Exception) {
                ""
            }
            synchronized(cookies) {
                return cookies[host]?.toList() ?: emptyList()
            }
        }
    }
}
