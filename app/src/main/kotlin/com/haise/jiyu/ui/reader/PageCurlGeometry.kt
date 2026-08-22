package com.haise.jiyu.ui.reader

import kotlin.math.hypot

/** Prostý 2D bod - záměrně NE Compose `Offset`, aby tahle část šla testovat v čistém
 * JVM testu bez závislosti na Android/Compose runtime. */
data class Point(val x: Float, val y: Float) {
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun times(scalar: Float) = Point(x * scalar, y * scalar)
    fun distanceTo(other: Point): Float = hypot((x - other.x).toDouble(), (y - other.y).toDouble()).toFloat()
}

/**
 * Geometrie ohybu stránky pro dané tažení. Fold linie prochází středem mezi rohem stránky
 * ([corner]) a pozicí prstu ([dragPoint]), kolmo na spojnici rohu a prstu - klasický
 * "single-corner curl" (stejný princip jako běžné page-curl knihovny na Androidu/iOS).
 */
data class PageCurlGeometry(
    val corner: Point,
    val dragPoint: Point,
    /** Body na okraji stránky, kde fold linie protíná hranici. */
    val foldEdgeA: Point,
    val foldEdgeB: Point,
    /** Vrcholy plochě zůstávající části stránky (opačná strana od [corner]). */
    val flatRegion: List<Point>,
    /** Vrcholy ohýbané části stránky (strana s [corner]). */
    val curledRegion: List<Point>,
    /** 0f (prst u rohu, žádný ohyb) .. 1f (prst u protějšího rohu, plně otočeno). */
    val progress: Float,
)

/**
 * Spočítá geometrii ohybu pro stránku o rozměrech [pageWidth] x [pageHeight], kdy uživatel
 * táhne roh [corner] směrem k [dragPoint]. [dragPoint] se nejdřív ořízne, aby nešel dál
 * než na opačnou stranu stránky (brání degenerované geometrii při přetažení mimo).
 */
fun computePageCurlGeometry(
    corner: Point,
    dragPoint: Point,
    pageWidth: Float,
    pageHeight: Float,
): PageCurlGeometry {
    val maxDistance = hypot(pageWidth.toDouble(), pageHeight.toDouble()).toFloat() * 1.05f
    val toDrag = dragPoint - corner
    val rawDistance = corner.distanceTo(dragPoint)
    val clampedDrag = when {
        rawDistance == 0f -> corner
        rawDistance > maxDistance -> corner + toDrag * (maxDistance / rawDistance)
        else -> dragPoint
    }

    val mid = Point((corner.x + clampedDrag.x) / 2f, (corner.y + clampedDrag.y) / 2f)
    val axis = clampedDrag - corner
    val foldDir = Point(-axis.y, axis.x)

    val rectCorners = listOf(
        Point(0f, 0f), Point(pageWidth, 0f), Point(pageWidth, pageHeight), Point(0f, pageHeight),
    )
    fun side(p: Point): Float = foldDir.x * (p.y - mid.y) - foldDir.y * (p.x - mid.x)
    val cornerSign = side(corner).sign()

    val curled = mutableListOf<Point>()
    val flat = mutableListOf<Point>()
    val edgeIntersections = mutableListOf<Point>()

    for (i in rectCorners.indices) {
        val a = rectCorners[i]
        val b = rectCorners[(i + 1) % rectCorners.size]
        val sideA = side(a)
        val onCornerSide = sideA.sign() == cornerSign || sideA == 0f
        (if (onCornerSide) curled else flat).add(a)

        val sideB = side(b)
        val crosses = (sideA > 0f && sideB < 0f) || (sideA < 0f && sideB > 0f)
        if (crosses) {
            val t = sideA / (sideA - sideB)
            val intersection = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            curled.add(intersection)
            flat.add(intersection)
            edgeIntersections.add(intersection)
        }
    }

    val (foldEdgeA, foldEdgeB) = if (edgeIntersections.size >= 2) {
        edgeIntersections[0] to edgeIntersections[1]
    } else {
        corner to corner
    }

    val progress = (rawDistance.coerceAtMost(maxDistance) / maxDistance).coerceIn(0f, 1f)

    return PageCurlGeometry(corner, clampedDrag, foldEdgeA, foldEdgeB, flat, curled, progress)
}

private fun Float.sign(): Float = when {
    this > 0f -> 1f
    this < 0f -> -1f
    else -> 0f
}

/** Šest koeficientů affinní matice zrcadlení (bez závislosti na `android.graphics.Matrix`,
 * aby vzorec šel testovat v čistém JVM testu). Pořadí odpovídá `Matrix.setValues()`. */
data class ReflectionMatrixCoefficients(
    val scaleX: Float, val skewX: Float, val transX: Float,
    val skewY: Float, val scaleY: Float, val transY: Float,
)

/** Odvodí matici zrcadlení bodů přes přímku danou body [lineStart]/[lineEnd] - použito
 * na vykreslení "rubu" ohýbané stránky (viz [PageCurlGeometry.curledRegion]). */
fun computeReflectionAcross(lineStart: Point, lineEnd: Point): ReflectionMatrixCoefficients {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val lenSq = dx * dx + dy * dy
    if (lenSq < 0.0001f) {
        return ReflectionMatrixCoefficients(1f, 0f, 0f, 0f, 1f, 0f)
    }
    val a = (dx * dx - dy * dy) / lenSq
    val b = 2 * dx * dy / lenSq
    val d = -a
    return ReflectionMatrixCoefficients(
        scaleX = a, skewX = b, transX = lineStart.x - a * lineStart.x - b * lineStart.y,
        skewY = b, scaleY = d, transY = lineStart.y - b * lineStart.x - d * lineStart.y,
    )
}

fun ReflectionMatrixCoefficients.apply(p: Point): Point =
    Point(scaleX * p.x + skewX * p.y + transX, skewY * p.x + scaleY * p.y + transY)
