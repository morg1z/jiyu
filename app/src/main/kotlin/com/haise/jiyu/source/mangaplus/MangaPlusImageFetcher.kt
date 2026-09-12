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
        val key = (uri.fragment ?: throw java.io.IOException("missing mplus_key fragment")).removePrefix("mplus_key=")
        val cleanUrl = uri.toString().substringBeforeLast("#")

        val bytes = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(cleanUrl).header("User-Agent", "okhttp/4.12.0").build()
            httpClient.newCall(req).execute().use { resp ->
                // isSuccessful kontrola PRED cimkoli dalsim - bez ni by se treba 403 "Just a
                // moment" HTML telo proste proXORovalo a ulozilo do Coil cache jako platny
                // JPEG (nahlaseny bug), misto aby selhalo jako sitova chyba a nechalo
                // Coil/RetryableAsyncImage zkusit znovu pozdeji.
                if (!resp.isSuccessful) throw java.io.IOException("MangaPlus obrázek ${resp.code}: $cleanUrl")
                resp.body?.bytes() ?: throw java.io.IOException("empty body for $cleanUrl")
            }
        }

        // Poškozený/zkrácený klíč (neplatný hex, prázdný fragment, nebo LICHÝ počet hex
        // znaků - chunked(2) by poslední 1-znakový kousek pořád úspěšně naparsoval jako
        // platnou hex číslici, jen s tiše špatným výsledkem místo výjimky, nahlášený bug)
        // by jinak spadl na NumberFormatException (toInt(16)), nebo u prázdného klíče na
        // ArithmeticException (deleni nulou u keyBytes.size) - Coil takovou vyjimku zachyti
        // jako chybovy stav (viz RetryableAsyncImage), ale s neprehlednou pricinou v logu.
        // IOException misto toho odpovida tomu, co Coil od Fetcheru ocekava jako
        // "sitovy/obsahovy" problem.
        val keyBytes = try {
            if (key.length % 2 != 0) throw NumberFormatException("odd-length key")
            key.chunked(2).map { it.toInt(16).toByte() }.toByteArray().also {
                if (it.isEmpty()) throw NumberFormatException("empty key")
            }
        } catch (e: NumberFormatException) {
            throw java.io.IOException("Neplatný mplus_key fragment: \"$key\"", e)
        }
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
