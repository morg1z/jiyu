package com.haise.jiyu.source.interceptor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.haise.jiyu.source.comix.ComixScramble
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/**
 * Dešifruje obrázky stránek comix.to. CDN chrání stránky dvěma nezávisle skladatelnými
 * vrstvami, poznatelnými z response hlaviček (viz [ComixScramble]):
 *
 *  - `x-enc-*`: XOR stream nad prvními `x-enc-len` bajty. Server ho aplikuje na každou
 *    čtvrtou stránku a hlavičky odemkne jen když request nese `Origin: https://comix.to`
 *    - proto zdroj tyhle URL označuje fragmentem `#enc-scrambled` (po síti se neposílá).
 *  - `x-scramble-*`: obrázek rozřezaný na 5×5 dlaždice a přeházený seedovanou permutací.
 *    Server hlavičky vrací jen když request má `v3` query flag a NEMÁ `Origin` - přesný
 *    opak legacy vrstvy; proto je Origin jen u `#enc-scrambled` URL.
 *
 * Interceptor sedí na sdíleném `@ImageHttpClient`, takže dešifruje stejně pro čtečku
 * (Coil) i pro offline stahování (`ChapterDownloadWorker`). Cache kolize chráněného a
 * nechráněného obsahu nehrozí - Coil klíčuje podle celé URL včetně fragmentu.
 */
class ComixImageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host
        if (!isComixHost(host)) return chain.proceed(request)

        if (request.url.fragment == ComixScramble.LEGACY_FRAGMENT && request.header("Origin") == null) {
            request = request.newBuilder().header("Origin", "https://comix.to").build()
        }

        var response = chain.proceed(request)
        if (response.code == 404) {
            response = retryPathFallbacks(chain, request, response)
        }
        if (!response.isSuccessful) return response

        val plan = ComixScramble.planFromHeaders { name -> response.header(name) }
            ?: return response

        var bytes = response.body?.bytes() ?: return response
        if (plan.hasXor) {
            bytes = ComixScramble.decodeXor(bytes, plan.encSeed, plan.encLen, plan.encAlgo)
        }

        var contentType = response.header("Content-Type") ?: "image/webp"
        if (plan.hasGrid) {
            val scrambled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (scrambled != null) {
                val restored = restoreTiles(scrambled, plan.gridSeed, plan.gridAlgo)
                if (restored != null) {
                    val out = ByteArrayOutputStream(bytes.size)
                    restored.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bytes = out.toByteArray()
                    contentType = "image/png"
                    restored.recycle()
                }
                // scrambled se recykluje VZDY - drive se recyklovala jen pri uspechu, takze
                // kdyz restoreTiles vratil null (maly rozmer/chybejici seed), zustala
                // viset nativni alokace na kazdem obraku (audit - pomaly leak).
                if (restored != scrambled) scrambled.recycle()
            }
        }

        return response.newBuilder()
            .body(bytes.toResponseBody(contentType.toMediaType()))
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .build()
    }

    private fun isComixHost(host: String): Boolean = host == "comix.to" || host.endsWith(".comix.to")

    /** CDN drží stejný obrázek pod několika zaměnitelnými segmenty cesty (`/i5/`, `/si/`,
     * `/i/`, `/sii/`, `/ii/`) a ten z page listu nemusí existovat - na 404 se zkouší
     * postupně všechny varianty, než se vzdá. */
    private fun retryPathFallbacks(chain: Interceptor.Chain, request: Request, response: Response): Response {
        val url = request.url.toString()
        val fallbacks = ComixScramble.PATH_FALLBACKS
            .map { url.replaceFirst(ComixScramble.PATH_FALLBACK_REGEX, it) }
            .filter { it != url }
        if (fallbacks.isEmpty()) return response

        var last = response
        for (fallbackUrl in fallbacks) {
            last.close()
            last = chain.proceed(request.newBuilder().url(fallbackUrl).build())
            if (last.code != 404) break
        }
        return last
    }

    /** Poskládá 5×5 dlaždice zpátky. `order[srcIdx]` = kam dlaždice srcIdx patří. */
    private fun restoreTiles(scrambled: Bitmap, seed: Int, algo: String?): Bitmap? {
        val width = scrambled.width
        val height = scrambled.height
        if (width < ComixScramble.GRID || height < ComixScramble.GRID) return null
        val tileW = width / ComixScramble.GRID
        val tileH = height / ComixScramble.GRID
        val order = ComixScramble.gridOrder(seed, algo)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // Nejprv cely obrazek - zachova okrajove pixely z celociselneho deleni dlazdic.
        canvas.drawBitmap(scrambled, 0f, 0f, null)
        val srcRect = Rect()
        val dstRect = Rect()
        for (srcIdx in 0 until ComixScramble.NUM_TILES) {
            val dstIdx = order[srcIdx]
            srcRect.set(
                (srcIdx % ComixScramble.GRID) * tileW, (srcIdx / ComixScramble.GRID) * tileH,
                (srcIdx % ComixScramble.GRID + 1) * tileW, (srcIdx / ComixScramble.GRID + 1) * tileH,
            )
            dstRect.set(
                (dstIdx % ComixScramble.GRID) * tileW, (dstIdx / ComixScramble.GRID) * tileH,
                (dstIdx % ComixScramble.GRID + 1) * tileW, (dstIdx / ComixScramble.GRID + 1) * tileH,
            )
            canvas.drawBitmap(scrambled, srcRect, dstRect, null)
        }
        return output
    }
}
