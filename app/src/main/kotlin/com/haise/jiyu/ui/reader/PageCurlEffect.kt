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
    val bitmap = currentPageBitmap.asAndroidBitmap()

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
    val bandBitmap = Bitmap.createBitmap(bitmap, cropLeft, 0, cropWidth, bitmap.height)

    // Mřížka MESH_COLUMNS x 1 (svisle se ohyb neliší, stejně jako v referenci) - pro každý
    // sloupec spočítáme vzdálenost `d` od osy ohybu a z ní warp (`warpedOffset`, komprese k ose)
    // a stín (`shadeAt`). `colors` pole násobí barvu bitmapy per-vertex - nahrazuje starý
    // PorterDuffColorFilter, teď ale plynule interpolovaný mezi sloupci místo skokového po proužcích.
    val vertsPerRow = MESH_COLUMNS + 1
    val verts = FloatArray(vertsPerRow * 2 * 2)
    val colors = IntArray(vertsPerRow * 2)
    for (row in 0..1) {
        val y = geometry.pageHeight * row
        for (col in 0..MESH_COLUMNS) {
            val frac = col.toFloat() / MESH_COLUMNS
            val d = if (geometry.turningFromRight) {
                geometry.curlBandWidth * frac
            } else {
                geometry.curlBandWidth * (1f - frac)
            }
            val x = geometry.foldX + direction * geometry.warpedOffset(d)
            val idx = row * vertsPerRow + col
            verts[idx * 2] = x
            verts[idx * 2 + 1] = y

            val gray = (geometry.shadeAt(d) * 255).roundToInt().coerceIn(0, 255)
            colors[idx] = Color.argb(255, gray, gray, gray)
        }
    }

    val meshPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    nativeCanvas.drawBitmapMesh(bandBitmap, MESH_COLUMNS, 1, verts, 0, colors, 0, meshPaint)

    drawFoldCrease(nativeCanvas, geometry)
}

/** Jemný stín na odkryté stránce těsně před ohýbaným pásem - simuluje, že zvednutý papír vrhá
 * stín na to, co je pod ním. */
private fun drawAheadShadow(canvas: android.graphics.Canvas, geometry: PageCurlGeometry, direction: Float) {
    val shadowWidth = 28f
    val edgeX = geometry.foldX + direction * geometry.curlBandWidth
    val farX = edgeX + direction * shadowWidth
    val paint = Paint().apply {
        shader = LinearGradient(
            edgeX, 0f, farX, 0f,
            intArrayOf(0x55000000.toInt(), 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
    }
    val left = minOf(edgeX, farX)
    val right = maxOf(edgeX, farX)
    canvas.drawRect(left, 0f, right, geometry.pageHeight, paint)
}

/** Tenký světlý proužek přesně na ose ohybu - simuluje odlesk světla na hraně zakulaceného
 * papíru. */
private fun drawFoldCrease(canvas: android.graphics.Canvas, geometry: PageCurlGeometry) {
    val creaseWidth = 3f
    val paint = Paint().apply {
        color = 0x40FFFFFF
        isAntiAlias = true
    }
    val left = geometry.foldX - creaseWidth / 2f
    canvas.drawRect(left, 0f, left + creaseWidth, geometry.pageHeight, paint)
}
