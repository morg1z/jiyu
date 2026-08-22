package com.haise.jiyu.ui.reader

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.hypot

/**
 * Vykreslí aktuální stránku s ohybem podle [geometry]. [revealedPageBitmap] je stránka,
 * která se odkrývá pod ohybem (null na hranici kapitoly, kdy sousední stránka ještě
 * neexistuje jako bitmapa).
 */
fun DrawScope.drawPageCurl(
    geometry: PageCurlGeometry,
    currentPageBitmap: ImageBitmap,
    revealedPageBitmap: ImageBitmap?,
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val toOffset = { p: Point -> Offset(p.x, p.y) }

    revealedPageBitmap?.let { revealed ->
        nativeCanvas.drawBitmap(revealed.asAndroidBitmap(), 0f, 0f, null)
    }

    val flatPath = polygonPath(geometry.flatRegion.map(toOffset))
    nativeCanvas.save()
    nativeCanvas.clipPath(flatPath.asAndroidPath())
    nativeCanvas.drawBitmap(currentPageBitmap.asAndroidBitmap(), 0f, 0f, null)
    nativeCanvas.restore()

    drawFoldShadow(nativeCanvas, geometry)

    val curledPath = polygonPath(geometry.curledRegion.map(toOffset))
    nativeCanvas.save()
    nativeCanvas.clipPath(curledPath.asAndroidPath())
    val reflection = computeReflectionAcross(geometry.foldEdgeA, geometry.foldEdgeB)
    val matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                reflection.scaleX, reflection.skewX, reflection.transX,
                reflection.skewY, reflection.scaleY, reflection.transY,
                0f, 0f, 1f,
            ),
        )
    }
    val dimPaint = Paint().apply { alpha = 217 } // ~0.85 - simuluje o neco tmavsi rub papiru
    nativeCanvas.drawBitmap(currentPageBitmap.asAndroidBitmap(), matrix, dimPaint)
    nativeCanvas.restore()

    drawFoldHighlight(nativeCanvas, geometry)
}

private fun polygonPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    close()
}

/** Stín na plochém okraji podél linie ohybu - simuluje zvednutý papír vrhající stín na
 * stránku pod sebou. */
private fun drawFoldShadow(canvas: android.graphics.Canvas, geometry: PageCurlGeometry) {
    val a = geometry.foldEdgeA
    val b = geometry.foldEdgeB
    val shadowWidth = 40f * geometry.progress.coerceIn(0.1f, 1f)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().takeIf { it > 0f } ?: 1f
    val normalX = -dy / len
    val normalY = dx / len

    val paint = Paint().apply {
        shader = LinearGradient(
            a.x, a.y,
            a.x - normalX * shadowWidth, a.y - normalY * shadowWidth,
            intArrayOf(0x66000000.toInt(), 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
    }
    val path = android.graphics.Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(b.x - normalX * shadowWidth, b.y - normalY * shadowWidth)
        lineTo(a.x - normalX * shadowWidth, a.y - normalY * shadowWidth)
        close()
    }
    canvas.drawPath(path, paint)
}

/** Zvýraznění na špičce ohybu (světlo odrážející se od zakřiveného papíru u prstu). */
private fun drawFoldHighlight(canvas: android.graphics.Canvas, geometry: PageCurlGeometry) {
    val tip = geometry.dragPoint
    val radius = 60f * geometry.progress.coerceAtLeast(0.05f)
    val paint = Paint().apply {
        shader = RadialGradient(
            tip.x, tip.y, radius,
            intArrayOf(0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(tip.x, tip.y, radius, paint)
}
