package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.haise.jiyu.util.PageImageSource
import com.haise.jiyu.util.ScrambledImageUrl
import com.haise.jiyu.util.TileScramble
import com.haise.jiyu.util.report
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nativně-rozlišený region-decode stránky pro záplaty překladu ([TextPatchProvider]).
 *
 * Proč: záplata se dřív počítala z bitmapy zmenšené na ~1600 px
 * ([TextPatchProvider.PATCH_SOURCE_MAX_DIMENSION]) a pak se roztáhla přes render box na
 * displeji - zbytečně měkké tahy/duchy místo ostrých hran, jaké stránka má. Tady se
 * dekóduje jen OBDÉLNÍK každé záplaty přímo v nativním rozlišení souboru přes
 * `BitmapRegionDecoder`: kvalita plná, paměťová cena pořád jen pár stovek kB-MP místo
 * ~100 MB celé stránky (15 000 px webtoon by jinak znamenal OOM).
 *
 * Session žije jen uvnitř [withSession] - decoder i případný pin Coil disk-cache
 * snapshotu se po návratu zavřou. Volající musí uvnitř bloku stihnout všechny dekódy.
 *
 * Scrambled stránky (dlaždicové proházení, viz [TileScramble]): bajty v cache jsou
 * zamíchané, takže se dekódují potřebné ZDROJOVÉ dlaždice a překreslí na cílové pozice
 * - stejné výsledné pixely jako `TileDescrambleTransformation`, jen bez dekódu celé
 * stránky. Lazy URL se přeskakují (resolvují se přes Coil fetcher, klíč v cache by
 * neseděl) - volající spadne na klasickou downscale cestu jako dosud.
 */
@Singleton
class PageRegionSource @Inject constructor(
    private val pageSource: PageImageSource,
) {
    /**
     * Otevře region-decode session stránky a předá ji [block]. `null` výsledek = zdroj
     * nedostupný (lazy URL, soubor není na disku/stáhnutelný, nedekódovatelný) -
     * volající použije původní downscale cestu. Nikdy nehází (kromě cancellation).
     */
    suspend fun <T> withSession(pageUrl: String, block: (Session) -> T): T? {
        if (pageSource.isLazyUrl(pageUrl)) return null
        val scramble = ScrambledImageUrl.parse(pageUrl)
        return runCatching {
            pageSource.withFile(pageUrl, referer = null) { file ->
                val decoder = pageSource.openRegionDecoder(file) ?: return@withFile null
                try {
                    if (decoder.width <= 0 || decoder.height <= 0) null
                    else block(Session(decoder, scramble))
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
    }

    /**
     * Otevřený `BitmapRegionDecoder` nad stránkou + případné scramble parametry.
     * [width]/[height] = nativní rozměry stránky (pro scrambled = rozměry rozskládané
     * podoby, scramble mění jen POŘADÍ dlaždic, ne rozměry).
     */
    class Session internal constructor(
        private val decoder: BitmapRegionDecoder,
        private val scramble: ScrambledImageUrl.Params?,
    ) {
        val width: Int get() = decoder.width
        val height: Int get() = decoder.height

        /** TileCopy operace pro celou stránku - počítá se jen pro scrambled a jen jednou. */
        private val tileCopies: List<TileScramble.TileCopy>? by lazy {
            scramble?.let { TileScramble.computeTileCopies(width, height, it.grid, it.seed) }
        }

        /**
         * Dekóduje [rect] v nativních pixelech stránky (ořízne se na její rozměry).
         * [sampleSize] = mocnina 2 downsamplingu při dekódu (když je obdélník za
         * paměťovým stropem); u scrambled se dekódují dlaždice nativně a výsledek se
         * po složení škáluje - jinak by se rozjela tile geometrie.
         * `null` = region nelze dekódovat - volající má klasickou zálohu.
         */
        fun decode(rect: Rect, sampleSize: Int = 1): Bitmap? {
            val clipped = Rect(
                rect.left.coerceIn(0, width), rect.top.coerceIn(0, height),
                rect.right.coerceIn(0, width), rect.bottom.coerceIn(0, height),
            )
            if (clipped.width() <= 0 || clipped.height() <= 0) return null
            return if (tileCopies == null) {
                decodePlain(clipped, sampleSize)
            } else {
                decodeScrambled(clipped, tileCopies!!, sampleSize)
            }
        }

        private fun decodePlain(rect: Rect, sampleSize: Int): Bitmap? = try {
            decoder.decodeRegion(
                rect,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sampleSize
                },
            )
        } catch (e: Exception) {
            e.report("translate:region:decode")
            null
        }

        /**
         * Obdélník v ROZSKLÁDANÉM obraze (souřadnice, ve kterých i [Session] reportuje
         * stránku) z bajtů zamíchaného souboru: dekódují se jen zdrojové dlaždice
         * protínající [rect] a překreslí na jejich cílové pozice.
         */
        private fun decodeScrambled(rect: Rect, copies: List<TileScramble.TileCopy>, sampleSize: Int): Bitmap? = try {
            val native = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(native)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val src = Rect()
            val dst = Rect()
            for (copy in copies) {
                // Dlaždice se do výsledku kreslí 1:1 (copy.srcW == copy.dstW apod.) -
                // intersects v dst souřadnicích rozhodne, jestli se do rect trefí.
                dst.set(copy.dstX, copy.dstY, copy.dstX + copy.dstW, copy.dstY + copy.dstH)
                if (!Rect.intersects(dst, rect)) continue
                src.set(copy.srcX, copy.srcY, copy.srcX + copy.srcW, copy.srcY + copy.srcH)
                val tile = decoder.decodeRegion(
                    src,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ) ?: continue
                try {
                    dst.offset(-rect.left, -rect.top)
                    canvas.drawBitmap(tile, null, dst, paint)
                } finally {
                    tile.recycle()
                }
            }
            if (sampleSize <= 1) {
                native
            } else {
                val scaled = Bitmap.createScaledBitmap(
                    native,
                    (rect.width() / sampleSize).coerceAtLeast(1),
                    (rect.height() / sampleSize).coerceAtLeast(1),
                    true,
                )
                native.recycle()
                scaled
            }
        } catch (e: Exception) {
            e.report("translate:region:decode:scrambled")
            null
        }
    }
}
