package com.haise.jiyu.ui.reader

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/** Počet tenkých svislých proužků, na které se ohýbaný pás rozdělí - vyšší číslo = plynulejší
 * zakulacení, ale víc `drawBitmap` volání za snímek. 40 je dost jemné na to, aby jednotlivé
 * proužky nebyly vidět, a levné dost na to, aby to drželo 60fps během tažení prstem. */
private const val STRIP_COUNT = 40

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

    // Ohýbaný pás: tenké svislé proužky, každý s trochu jiným posunem (komprese k ose ohybu,
    // viz `warpedOffset`) a stínováním (tmavší, čím víc zakulacený, viz `shadeAt`). `drawBitmap`
    // s `src`/`dst` obdélníky samo natáhne/zúží obsah proužku mezi originální a warpovanou
    // šířkou - přesně tahle komprese vytváří dojem zakulaceného papíru.
    for (i in 0 until STRIP_COUNT) {
        val d0 = geometry.curlBandWidth * i / STRIP_COUNT
        val d1 = geometry.curlBandWidth * (i + 1) / STRIP_COUNT

        val srcX0 = geometry.foldX + direction * d0
        val srcX1 = geometry.foldX + direction * d1
        val srcLeft = minOf(srcX0, srcX1)
        val srcRight = maxOf(srcX0, srcX1)
        val srcRect = Rect(
            srcLeft.roundToInt().coerceIn(0, bitmap.width),
            0,
            srcRight.roundToInt().coerceIn(0, bitmap.width),
            bitmap.height,
        )
        if (srcRect.width() <= 0) continue

        val warped0 = geometry.warpedOffset(d0)
        val warped1 = geometry.warpedOffset(d1)
        val dstX0 = geometry.foldX + direction * warped0
        val dstX1 = geometry.foldX + direction * warped1
        val dstLeft = minOf(dstX0, dstX1)
        val dstRight = maxOf(dstX0, dstX1).coerceAtLeast(dstLeft + 1f)
        val dstRect = RectF(dstLeft, 0f, dstRight, geometry.pageHeight)

        val shade = geometry.shadeAt((d0 + d1) / 2f)
        val gray = (shade * 255).roundToInt().coerceIn(0, 255)
        val paint = Paint().apply {
            isAntiAlias = true
            colorFilter = PorterDuffColorFilter(Color.rgb(gray, gray, gray), PorterDuff.Mode.MULTIPLY)
        }
        nativeCanvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

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
