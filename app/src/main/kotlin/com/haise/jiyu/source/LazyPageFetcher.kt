package com.haise.jiyu.source

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.util.LazyPageUrl
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * Coil Fetcher pro lazy URL stránek (viz [LazyPageUrl]). Zdroje jako MangaHome
 * (chapterfun.ashx), FanFox nebo EHentai vracejí v `Page.url` virtuální adresu,
 * kterou do skutečné URL obrázku překládá až `MangaSource.getImageUrl` - bez
 * tohohle fetcheru se do Coilu dostal samotný virtuální string a stránka se
 * nikdy nenačetla (černá obrazovka, nahlášeno na zařízení u MangaHome).
 *
 * U zdrojů bez lazy resolveru je to jen drahá zkratka: defaultní `getImageUrl`
 * vrátí `page.url` beze změny, takže se stáhne stejná URL jako bez markeru.
 */
class LazyPageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val httpClient: OkHttpClient,
    private val repository: MangaRepository,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val (sourceId, index) = LazyPageUrl.decodeFragment(uri.fragment)
            ?: throw java.io.IOException("Neplatný jiyu_lazy fragment: \"${uri.fragment}\"")
        val virtualUrl = uri.buildUpon().fragment(null).toString()
        val realUrl = try {
            repository.resolvePageImageUrl(sourceId, Page(index = index, url = virtualUrl))
        } catch (e: Exception) {
            e.rethrowIfControl()
            android.util.Log.e("LazyPageFetcher", "getImageUrl FAIL $sourceId $virtualUrl", e)
            throw java.io.IOException("getImageUrl selhalo pro $virtualUrl: ${e.message}", e)
        }
        // SEC-4: URL stranek do logu jen v debugu (R8 v release Log.i stripne,
        // guard je tu explicitne pro repro/testovaci buildy bez minify).
        if (BuildConfig.DEBUG) android.util.Log.i("LazyPageFetcher", "resolved $sourceId[$index] -> ${realUrl.take(120)}")
        if (realUrl.isBlank()) throw java.io.IOException("getImageUrl vrátilo prázdnou URL pro $virtualUrl")

        return withContext(Dispatchers.IO) {
            // options.headers nesou hlavičky z ImageRequest (hlavně Referer zdroje -
            // bez něj CDN s hotlink ochranou odpoví 403, viz buildPageImageRequest).
            val req = Request.Builder().url(realUrl).apply {
                for (name in options.headers.names()) {
                    for (value in options.headers.values(name)) header(name, value)
                }
            }.build()
            httpClient.newCall(req).execute().use { resp ->
                if (BuildConfig.DEBUG) android.util.Log.i("LazyPageFetcher", "fetch ${resp.code} ${realUrl.take(100)}")
                if (!resp.isSuccessful) throw java.io.IOException("Obrázek stránky ${resp.code}: $realUrl")
                val bytes = resp.body?.bytes() ?: throw java.io.IOException("empty body for $realUrl")
                SourceResult(
                    source = ImageSource(Buffer().also { it.write(bytes) }, options.context),
                    // jen type/subtype - charset sufix by mohl zmást výběr dekodéru
                    mimeType = resp.body?.contentType()?.let { "${it.type}/${it.subtype}" },
                    dataSource = DataSource.NETWORK,
                )
            }
        }
    }

    class Factory(
        private val httpClient: OkHttpClient,
        private val repository: MangaRepository,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val hit = LazyPageUrl.decodeFragment(data.fragment) != null
            if (hit && BuildConfig.DEBUG) android.util.Log.i("LazyPageFetcher", "create for ${data.toString().take(100)}")
            return if (hit) LazyPageFetcher(data, options, httpClient, repository) else null
        }
    }
}
