package com.haise.jiyu.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/** Počet sloupců sítě, na které se ohýbaný pás rozdělí pro [android.graphics.Canvas.drawBitmapMesh]
 * - vyšší číslo = plynulejší zakulacení. Mesh je bilineárně interpolovaný hardwarově, takže na
 * rozdíl od starého přístupu (diskrétní `drawBitmap` proužky) je i s vyšším počtem sloupců levný
 * a hladký - žádné viditelné "schody" mezi sloupci. */
private const val MESH_COLUMNS = 30

/** Počet řádků sítě - musí být > 1, aby [PageCurlGeometry.verticalTaper] měl co plynule
 * interpolovat (kónický ohyb, silnější u rohu než na druhém konci). Dřív tu byly jen 2 řádky
 * (nahoře/dole), protože geometrie byla čistě válcová (stejná po celé výšce). */
private const val MESH_ROWS = 24

/**
 * Vykreslí aktuální stránku s válcovým ohybem podle [geometry] (styl Google Play Books/iBooks -
 * obsah se u osy ohybu komprimuje/zakulacuje, ne zrcadlí jako dřív). [revealedPageBitmap] je
 * stránka odkrývaná pod ohybem (null na hranici kapitoly, kdy sousední stránka ještě neexistuje
 * jako bitmapa).
 */
fun DrawScope.drawPageCurl(
    geometry: PageCurlGeometry,
    currentPageBitmap: ImageBitmap,
    revealedPageBitmap: ImageBitmap?,
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val rawBitmap = currentPageBitmap.asAndroidBitmap()
    // `currentPageBitmap` je rasterizovaná GraphicsLayer vrstva - na řadě zařízení (potvrzeno na
    // Galaxy S24 Ultra) vychází v Config.HARDWARE (GPU-only paměť, žádný přímý přístup k pixelům
    // z CPU). Prosté `Canvas.drawBitmap` (plochá část níž, `revealedPageBitmap`) na tom funguje
    // v pohodě - je to čistě GPU kompozice. Ale ořez pásu pro `drawBitmapMesh`
    // (`Bitmap.createBitmap(source, x, y, w, h)`) na hardwarové bitmapě čte pixely, a na tomhle
    // zařízení tiše vrací bitmapu bez viditelného obsahu - bez pádu, bez chyby, prostě nic
    // nenakreslí. Proto ohýbaný pás vypadal jako plochý řez bez jakéhokoli zakřivení/stínování.
    // Převod na softwarovou kopii CELÉ bitmapy hned na začátku (ne až po ořezu) tomu předchází.
    val bitmap = if (rawBitmap.config == Bitmap.Config.HARDWARE) {
        rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        rawBitmap
    }

    revealedPageBitmap?.let {
        nativeCanvas.drawBitmap(it.asAndroidBitmap(), 0f, 0f, null)
    }

    val direction = if (geometry.turningFromRight) 1f else -1f

    // Plochá část stránky (mezi protější hranou a osou ohybu) - beze změny, bez warpu.
    val flatRect = if (geometry.turningFromRight) {
        Rect(0, 0, geometry.foldX.roundToInt().coerceIn(0, bitmap.width), bitmap.height)
    } else {
        Rect(geometry.foldX.roundToInt().coerceIn(0, bitmap.width), 0, bitmap.width, bitmap.height)
    }
    if (flatRect.width() > 0) {
        nativeCanvas.save()
        nativeCanvas.clipRect(flatRect)
        nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
        nativeCanvas.restore()
    }

    if (geometry.curlBandWidth < 0.5f) return

    // Stín na odkryté stránce kousek před ohýbaným pásem - simuluje, že zvednutý papír vrhá stín.
    drawAheadShadow(nativeCanvas, geometry, direction)

    // Ohýbaný pás: nejdřív oříznout bitmapu jen na pás, co se ohýbá (drawBitmapMesh warpuje
    // celou zdrojovou bitmapu rovnoměrně, nemá vlastní parametr pro texturové souřadnice).
    // `turningFromRight` určuje, který konec ořezu leží na ose ohybu (d=0) - viz mapování d(col)
    // níž.
    val bandLeftF = if (geometry.turningFromRight) geometry.foldX else geometry.foldX - geometry.curlBandWidth
    val bandRightF = if (geometry.turningFromRight) geometry.foldX + geometry.curlBandWidth else geometry.foldX
    val cropLeft = bandLeftF.roundToInt().coerceIn(0, bitmap.width)
    val cropRight = bandRightF.roundToInt().coerceIn(cropLeft, bitmap.width)
    val cropWidth = cropRight - cropLeft
    if (cropWidth <= 0) return
    val croppedBand = Bitmap.createBitmap(bitmap, cropLeft, 0, cropWidth, bitmap.height)
    // `currentPageBitmap` je rasterizovaná GraphicsLayer vrstva - na tomhle zařízení vychází v
    // Config.HARDWARE (GPU-only paměť). Ořez zůstává stejně HARDWARE a `drawBitmapMesh` na něm
    // TICHO nekreslí vůbec nic (bez pádu, bez chyby) - efekt tak vypadal jako plochý řez bez
    // jakéhokoli zakřivení. `drawBitmapMesh` potřebuje softwarově čitelné pixely.
    val bandBitmap = if (croppedBand.config == Bitmap.Config.HARDWARE) {
        croppedBand.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        croppedBand
    }

    // Mřížka MESH_COLUMNS x MESH_ROWS - pro každý bod spočítáme vzdálenost `d` od osy ohybu a
    // z ní warp (`warpedOffset`, komprese k ose) a stín (`shadeAt`), obojí navíc násobené
    // `verticalTaper(rowT)` - KÓNICKÝ ohyb, silnější poblíž rohu, za který se stránka "drží",
    // slabší na druhém konci (viz `PageCurlGeometry.kt`). Dřív tu byly jen 2 řádky a žádný taper,
    // takže ohyb vypadal jako rovnoměrný VÁLEC přes celou výšku, ne jako přirozené uchopení rohu.
    // `colors` pole násobí barvu bitmapy per-vertex - nahrazuje starý PorterDuffColorFilter, teď
    // ale plynule interpolovaný mezi vertexy místo skokového po proužcích.
    val vertsPerRow = MESH_COLUMNS + 1
    val vertsPerCol = MESH_ROWS + 1
    val verts = FloatArray(vertsPerRow * vertsPerCol * 2)
    val colors = IntArray(vertsPerRow * vertsPerCol)
    for (row in 0..MESH_ROWS) {
        val rowT = row.toFloat() / MESH_ROWS
        val y = geometry.pageHeight * rowT
        val taper = geometry.verticalTaper(rowT)
        for (col in 0..MESH_COLUMNS) {
            val frac = col.toFloat() / MESH_COLUMNS
            val d = if (geometry.turningFromRight) {
                geometry.curlBandWidth * frac
            } else {
                geometry.curlBandWidth * (1f - frac)
            }
            val x = geometry.foldX + direction * geometry.warpedOffset(d) * taper
            val idx = row * vertsPerRow + col
            verts[idx * 2] = x
            verts[idx * 2 + 1] = y

            val shade = 1f - (1f - geometry.shadeAt(d)) * taper
            val gray = (shade * 255).roundToInt().coerceIn(0, 255)
            colors[idx] = Color.argb(255, gray, gray, gray)
        }
    }

    val meshPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    nativeCanvas.drawBitmapMesh(bandBitmap, MESH_COLUMNS, MESH_ROWS, verts, 0, colors, 0, meshPaint)
}

/** Kolik vodorovných pruhů se použije na vykreslení stínu - musí sledovat stejný kónický taper
 * jako hlavní mesh (viz [PageCurlGeometry.verticalTaper]), jinak by stín zůstal rovný pruh i
 * když je samotný ohyb nahoře/dole slabší - vypadalo by to nesourodě. */
private const val SHADOW_STRIPS = 12

/** Měkký vržený stín na odkryté stránce těsně před ohýbaným pásem - simuluje, že zvednutý papír
 * vrhá stín na to, co je pod ním. Šířka škáluje s poloměrem ohybu (u širšího/pozvolnějšího ohybu
 * i stín dopadá dál), víc mezikroků v gradientu dělá dopad postupnější (ne jeden ostrý schod) a
 * vodorovné pruhy s [PageCurlGeometry.verticalTaper] kopírují kónický tvar hlavního ohybu, místo
 * jednoho rovného obdélníku přes celou výšku. */
private fun drawAheadShadow(canvas: android.graphics.Canvas, geometry: PageCurlGeometry, direction: Float) {
    val paint = Paint()
    for (i in 0 until SHADOW_STRIPS) {
        val rowT0 = i.toFloat() / SHADOW_STRIPS
        val rowT1 = (i + 1).toFloat() / SHADOW_STRIPS
        val taper = geometry.verticalTaper((rowT0 + rowT1) / 2f)
        val shadowWidth = geometry.radius * 0.6f * taper
        val edgeX = geometry.foldX + direction * geometry.curlBandWidth * taper
        val farX = edgeX + direction * shadowWidth
        paint.shader = LinearGradient(
            edgeX, 0f, farX, 0f,
            intArrayOf(0x40000000, 0x18000000, 0x00000000),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        val left = minOf(edgeX, farX)
        val right = maxOf(edgeX, farX)
        canvas.drawRect(left, geometry.pageHeight * rowT0, right, geometry.pageHeight * rowT1, paint)
    }
}
