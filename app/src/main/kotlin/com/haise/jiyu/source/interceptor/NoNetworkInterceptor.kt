package com.haise.jiyu.source.interceptor

import com.haise.jiyu.util.NoNetworkException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Bez připojení selže požadavek hned, ne až po timeoutu (dřív se offline poznalo po 15-30 s čekání na connect).
 * Stojí na začátku řetězce. [isOnline] se vyhodnocuje při každém požadavku, aby se po obnovení připojení
 * nic neblokovalo.
 */
class NoNetworkInterceptor(private val isOnline: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!isOnline()) throw NoNetworkException()
        return chain.proceed(chain.request())
    }
}
