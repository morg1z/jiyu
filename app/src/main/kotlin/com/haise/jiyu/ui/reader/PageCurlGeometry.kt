package com.haise.jiyu.ui.reader

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometrie válcového ohybu stránky (cylinder roll, styl Google Play Books / iBooks).
 * Stránka se odvaluje od hrany otáčení směrem k protější hraně - obsah kousek před osou ohybu
 * se komprimuje/zužuje podle úhlu na pomyslném válci, ne zrcadlí. Za viditelným pásem (jakmile
 * by úhel přesáhl čtvrtinu válce) je papír už "odvalený" mimo pohled - tam prosvítá odkrytá
 * stránka pod ním.
 */
data class PageCurlGeometry(
    val pageWidth: Float,
    val pageHeight: Float,
    /** true = stránka se otáčí/odvaluje směrem k pravému okraji (ohyb tam začíná a postupuje
     * doleva), false = odvaluje se k levému okraji (ohyb postupuje doprava). */
    val turningFromRight: Boolean,
    /** X souřadnice osy ohybu - u [progress]=0 leží přesně na hraně otáčení, u [progress]=1 na
     * protější hraně. */
    val foldX: Float,
    /** Šířka viditelně ohýbaného pásu (v původních souřadnicích bitmapy, měřeno od [foldX] směrem
     * k hraně otáčení) - roste od 0 do [radius]×π/2 a pak zůstává na tomto maximu, i když
     * [foldX] pokračuje dál (zbytek stránky za pásem je odvalený mimo pohled). */
    val curlBandWidth: Float,
    /** Poloměr pomyslného válce - určuje, jak "těsně" se stránka odvaluje. */
    val radius: Float,
    /** 0f (žádný ohyb) .. 1f (stránka plně otočená). */
    val progress: Float,
)

/**
 * Spočítá geometrii válcového ohybu pro stránku [pageWidth] x [pageHeight] při míře otočení
 * [progress] (0f..1f, absolutní hodnota - směr určuje [turningFromRight]).
 */
fun computePageCurlGeometry(
    pageWidth: Float,
    pageHeight: Float,
    turningFromRight: Boolean,
    progress: Float,
): PageCurlGeometry {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val radius = (pageWidth * 0.18f).coerceAtLeast(24f)
    val maxBandWidth = radius * (PI.toFloat() / 2f)
    val paperPastFold = clampedProgress * pageWidth
    val curlBandWidth = paperPastFold.coerceAtMost(maxBandWidth)
    val foldX = if (turningFromRight) pageWidth - paperPastFold else paperPastFold
    return PageCurlGeometry(pageWidth, pageHeight, turningFromRight, foldX, curlBandWidth, radius, clampedProgress)
}

/**
 * Pro vzdálenost [d] (0f..[PageCurlGeometry.curlBandWidth]) od osy ohybu vrátí skutečný posun
 * na obrazovce - `radius*sin(d/radius)`, vždy <= [d] (komprese, jak se obsah "zakulacuje" na
 * válci). U [d]=0 je posun 0 (přímo na ose ohybu), u [d]=[PageCurlGeometry.curlBandWidth] je
 * posun nejvýš `radius` (čtvrtina válce).
 */
fun PageCurlGeometry.warpedOffset(d: Float): Float {
    val theta = (d / radius).coerceIn(0f, PI.toFloat() / 2f)
    return radius * sin(theta)
}

/**
 * Stínovací faktor (násobitel jasu) pro vzdálenost [d] od osy ohybu - 1f přímo na ose (plný
 * jas), klesá k ~0.35f na konci viditelného pásu (nejvíc zakulacená/odvrácená část simuluje
 * stín na zaobleném papíru).
 */
fun PageCurlGeometry.shadeAt(d: Float): Float {
    val theta = (d / radius).coerceIn(0f, PI.toFloat() / 2f)
    return 0.35f + 0.65f * cos(theta)
}
