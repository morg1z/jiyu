package com.haise.jiyu.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.Transformation
import com.haise.jiyu.ui.reader.TileDescrambleTransformation
import com.haise.jiyu.util.ScrambledImageUrl
import com.haise.jiyu.util.report
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stahuje a dekóduje bitmapu stránky pro OCR - přes Coil (stejná cache jako UI zobrazení
 * v RetryableAsyncImage, takže se stránka typicky nestahuje dvakrát) a se stejnou
 * [TileDescrambleTransformation] jako tam.
 *
 * Dřív [OcrEngine] stahovala bitmapu sama přes syrový OkHttp bez jakékoli transformace -
 * u zdrojů s dlaždicovým scramblingem (anti-scraping ochrana, viz [ScrambledImageUrl]) tak
 * OCR běžel na poskládané/nesmyslné bitmapě a tiše vracel prázdný výsledek. Přesunutím
 * načítání sem (mimo OcrEngine, který je čistě on-device ML Kit a s HTTP nemá co dělat) se
 * tenhle bug opravuje jako vedlejší efekt sjednocení s cestou, kterou appka používá pro
 * zobrazení.
 */
@Singleton
class PageBitmapLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * @param maxDimension když je zadané, Coil stránku zmenší tak, aby se vešla do čtverce téhle
     *   velikosti. Používá to [TextPatchProvider]: dekódovat 15 000 px vysokou stránku webtoonu
     *   v plném rozlišení kvůli záplatě několika bublin je spolehlivá cesta k OOM.
     *
     *   OCR cesta parametr NEPŘEDÁVÁ a pracuje s plným rozlišením jako dosud - jinak by se
     *   změnilo, co OCR přečte, a tím i obsah cache překladů.
     */
    suspend fun load(url: String, maxDimension: Int? = null): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("/") || url.startsWith("file://")) {
                val path = url.removePrefix("file://")
                if (maxDimension != null) {
                    // Na rozdíl od Coil větve níž (viz `.size(it)`) tahle cesta dřív `maxDimension`
                    // úplně ignorovala - lokálně stažená (file://) 15000px webtoon stránka se tak
                    // pořád dekódovala v plném rozlišení, přesně ten OOM, kterému `maxDimension`
                    // u TextPatchProvider měl zabránit.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                } else {
                    BitmapFactory.decodeFile(path)
                }
            } else {
                val scramble = ScrambledImageUrl.parse(url)
                val transforms = buildList<Transformation> {
                    scramble?.let { add(TileDescrambleTransformation(it.grid, it.seed)) }
                }
                val request = ImageRequest.Builder(context)
                    .data(url.substringBeforeLast("#")) // strip #mplus_key= fragment
                    .apply { if (transforms.isNotEmpty()) transformations(transforms) }
                    // Coil na API 26+ defaultně dekóduje do Config.HARDWARE (bitmapa žije v GPU
                    // paměti) - výsledek je pro zobrazení skvělý, ale bitmap.getPixel() na ní
                    // tvrdě spadne s IllegalStateException. Tahle bitmapa jde rovnou do OCR
                    // (BubbleShapeDetector, hasWallBetween/ringColor), které čtou pixely jeden
                    // po druhém - proto to appka spolehlivě shazovalo hned po startu hromadného
                    // překladu (viz uživatelská zpětná vazba "u překladu appka spadne").
                    .allowHardware(false)
                    .apply { maxDimension?.let { size(it) } }
                    .build()
                val result = Coil.imageLoader(context).execute(request)
                (result as? SuccessResult)?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
            }
        } catch (e: Exception) {
            // Nenačtená stránka = nepřeložená stránka. Bez hlášení se to navenek projeví jen
            // tím, že překlad "některé stránky přeskočil", bez jakékoli stopy proč.
            e.report("translate:bitmap:load")
            null
        }
    }

    /** Standardní Android vzor - největší mocnina 2, po které se obě strany pořád vejdou do [maxDimension]. */
    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
