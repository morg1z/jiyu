package com.haise.jiyu.util

import android.content.Context
import coil.Coil
import coil.ImageLoader

/**
 * Druhý Coil ImageLoader pro ZOBRAZOVACÍ requesty stránek čtečky (zdařilé přes
 * [com.haise.jiyu.JiyuApp]). Nese vlastní OkHttp Dispatcher - viditelná stránka pod
 * prstem tak nemůže uvíznout ve frontě za prefetch requesty téhož hostitele, které
 * ucpou `maxRequestsPerHost` sdíleného klienta (hlášený bug: "stránka se načítá
 * donekonečna, scroll pryč a zpět ji spraví" - zrušení + re-enqueue ji na konce
 * procpané fronty dostalo až po dojetí prefetch vlny).
 *
 * Memory i disk cache jsou sdílené instance s hlavním loaderem (disk cache přes
 * Coilův SingletonDiskCache na stejném adresáři) - žádný druhý download, žádná
 * druhá paměťová nádrž. Fallback na [Coil.imageLoader] jen dokud není install
 * (unit testy, App bez onCreate cesty).
 */
object PageDisplayImageLoader {

    @Volatile
    private var loader: ImageLoader? = null

    fun install(imageLoader: ImageLoader) {
        loader = imageLoader
    }

    fun get(context: Context): ImageLoader = loader ?: Coil.imageLoader(context)
}
