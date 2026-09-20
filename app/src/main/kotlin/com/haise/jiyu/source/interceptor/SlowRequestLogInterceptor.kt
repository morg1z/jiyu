package com.haise.jiyu.source.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Jen pro ladění (přidává se v debug buildu): zaloguje každý požadavek, který trval déle než [thresholdMs] -
 * nebo selhal až po tak dlouhé době. Slouží k měření, kde se ztrácí čas na slabém signálu (`adb logcat -s JiyuNet`).
 */
class SlowRequestLogInterceptor(
    private val thresholdMs: Long = 5_000,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.nanoTime()
        try {
            val response = chain.proceed(request)
            report(request.method, request.url.host, request.url.encodedPath, start, "HTTP ${response.code}")
            return response
        } catch (e: Exception) {
            report(request.method, request.url.host, request.url.encodedPath, start, e.javaClass.simpleName)
            throw e
        }
    }

    private fun report(method: String, host: String, path: String, startNanos: Long, outcome: String) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        if (ms >= thresholdMs) log("$method $host$path: ${ms} ms ($outcome)")
    }

    companion object {
        const val TAG = "JiyuNet"
    }
}
