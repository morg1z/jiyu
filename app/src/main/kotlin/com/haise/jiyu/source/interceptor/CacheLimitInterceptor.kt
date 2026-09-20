package com.haise.jiyu.source.interceptor

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor pro OkHttp cache zdrojů: bez něj by cache držela odpovědi tak dlouho, jak si web řekne
 * (hodiny až dny), nebo - u odpovědí bez `Cache-Control` - podle heuristiky z `Last-Modified`, takže by se
 * seznam nových kapitol mohl dlouho ukazovat starý.
 *
 *  - `no-store` zůstává (web si cache výslovně nepřeje),
 *  - `max-age` delší než [maxAgeSeconds] se zkrátí na [maxAgeSeconds] (shoda s paměťovou cache seznamů stránek),
 *  - odpověď bez `Cache-Control`, ale s validátorem (`ETag`/`Last-Modified`), dostane `no-cache`: uloží se, ale
 *    při každém dalším použití se jen podmíněně ověří (304 = skoro žádná data) - šetří přenos na slabém signálu
 *    a nikdy nevrátí zastaralý obsah.
 */
class CacheLimitInterceptor(private val maxAgeSeconds: Int = 600) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val cc = CacheControl.parse(response.headers)
        if (cc.noStore || cc.noCache) return response

        if (cc.maxAgeSeconds > maxAgeSeconds) {
            return response.newBuilder().header("Cache-Control", "max-age=$maxAgeSeconds").build()
        }
        val hasExplicitFreshness = cc.maxAgeSeconds >= 0 || response.header("Expires") != null
        val hasValidator = response.header("ETag") != null || response.header("Last-Modified") != null
        if (!hasExplicitFreshness && hasValidator) {
            return response.newBuilder().header("Cache-Control", "no-cache").build()
        }
        return response
    }
}
