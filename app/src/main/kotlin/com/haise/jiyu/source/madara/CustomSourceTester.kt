package com.haise.jiyu.source.madara

import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Zkouška vlastního (Madara) zdroje z Nastavení - dřív ji ve ViewModelu skládal inline `MadaraSource` s holým `OkHttpClient`. */
@Singleton
class CustomSourceTester @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Počet titulů, které zdroj s danými selektory vrátí na první stránce populárních. */
    suspend fun countPopular(baseUrl: String, selectors: MadaraSelectors): Int =
        MadaraSource(id = "test", name = "Test", baseUrl = baseUrl, client = client, selectors = selectors).getPopular(1).size
}
