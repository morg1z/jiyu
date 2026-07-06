package com.haise.jiyu.source.mangaplus

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * Coil Fetcher pro XOR-šifrované obrázky z MANGA Plus.
 * Aktivuje se pro URL s fragmentem `#mplus_key=<hex>`.
 * Stáhne šifrovaný obrázek a byte-po-bytu ho XORuje s klíčem.
 */
class MangaPlusImageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val httpClient: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val key = (uri.fragment ?: error("missing mplus_key fragment")).removePrefix("mplus_key=")
        val cleanUrl = uri.toString().substringBeforeLast("#")

        val bytes = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(cleanUrl).header("User-Agent", "okhttp/4.12.0").build()
            httpClient.newCall(req).execute().use { resp ->
                resp.body?.bytes() ?: error("empty body for $cleanUrl")
            }
        }

        val keyBytes = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decrypted = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }

        return SourceResult(
            source = ImageSource(Buffer().also { it.write(decrypted) }, options.context),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val httpClient: OkHttpClient) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            return if (data.fragment?.startsWith("mplus_key=") == true)
                MangaPlusImageFetcher(data, options, httpClient)
            else null
        }
    }
}
