package com.haise.jiyu.source

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

/**
 * Vetsina konkretnich zdroju ma "private val base = "https://realsite.com"" napevno
 * v kodu (na rozdil od MadaraSource, ktery baseUrl bere jako konstruktorovy parametr).
 * Aby sly testovat bez zasahu do produkcniho kodu, tenhle klient prepise host+port
 * kazdeho odchoziho requestu na MockWebServer, ale zachova path+query - takze zdroj
 * i s hardcoded "https://realsite.com/manga/foo" ve skutecnosti zavola MockWebServer
 * na "/manga/foo" a dostane canned fixture odpovidajici realne URL strukture.
 */
fun redirectingClient(mockWebServer: MockWebServer): OkHttpClient {
    val mockUrl = mockWebServer.url("/")
    return OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val newUrl = original.url.newBuilder()
                .scheme(mockUrl.scheme)
                .host(mockUrl.host)
                .port(mockUrl.port)
                .build()
            chain.proceed(original.newBuilder().url(newUrl).build())
        }
        .build()
}
