package com.haise.jiyu.source.interceptor

import com.haise.jiyu.di.parseRetryAfterMs
import com.haise.jiyu.source.SourceRateLimitedException
import com.haise.jiyu.source.SourceSlowdown
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Na HTTP 429 vyhodí [SourceRateLimitedException] místo obyčejné odpovědi - viz `Throwable.toFriendlyMessage`
 * pro srozumitelnou hlášku uživateli. Výjimka je `IOException` + `NonRetryable`: přes async `enqueue()` (Coil)
 * se doručí do onFailure místo aby shodila dispatcher vlákno, a [RetryInterceptor] ji přesto neopakuje (druhý
 * pokus by stejně zůstal rate-limitovaný). Host se navíc ohlásí [SourceSlowdown], aby se další požadavky
 * na něj (stahování, předstahování) rozložily v čase.
 */
class RateLimitInterceptor(private val slowdown: SourceSlowdown) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            val retryAfterMs = response.header("Retry-After")?.let { parseRetryAfterMs(it) } ?: 0L
            slowdown.noteRateLimited(chain.request().url.host, retryAfterMs)
            response.close()
            throw SourceRateLimitedException(retryAfterMs)
        }
        return response
    }
}
