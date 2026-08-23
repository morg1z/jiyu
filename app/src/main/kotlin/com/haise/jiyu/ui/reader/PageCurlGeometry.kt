package com.haise.jiyu.ui.reader

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dva vizuální styly otáčení stránky - viz [PageCurlGeometry.style].
 */
enum class CurlStyle {
    /** Plochý ohyb po (téměř) celé výšce stránky, jako klasické otáčení listu v knize -
     * čtvrtina pomyslného válce (0..π/2), velký poloměr. */
    CLASSIC,

    /** Stránka se svinuje do úzké trubičky, která putuje napříč stránkou podle tažení - půlka
     * pomyslného válce (0..π), malý konstantní poloměr. Viditelná i "rubová" (tmavší) strana
     * svinutého papíru. */
    ROLL,
}

/** Maximální úhel na pomyslném válci, který [style] ještě vykresluje - určuje, jestli je vidět
 * jen čtvrtina válce (plochý ohyb) nebo půlka (svinutá trubička, viditelná i odvrácená strana). */
private fun CurlStyle.domainMax(): Float = when (this) {
    CurlStyle.CLASSIC -> PI.toFloat() / 2f
    CurlStyle.ROLL -> PI.toFloat()
}

/** Nejtmavší přípustné stínování - u [CurlStyle.ROLL] by `cos(theta)` u theta blízko π jinak
 * vyšel záporný (viz [shadeAt]). */
private const val MIN_SHADE = 0.12f

/** Svislá pozice (0f nahoře, 1f dole), odkud "vychází" kónický ohyb - dolní roh je
 * nejběžnější místo, za které se stránka při otáčení uchopí (viz [verticalTaper]). */
private const val ANCHOR_ROW_T = 1f

/** O kolik je ohyb slabší na opačném svislém konci stránky než u [ANCHOR_ROW_T] - 0.55 =
 * na druhém konci zůstane 45 % síly ohybu, ne 0 (úplně plochý konec by působil nepřirozeně
 * ostře, viz [verticalTaper]). */
private const val CONICAL_TAPER_STRENGTH = 0.55f

/**
 * Geometrie ohybu stránky (styl Google Play Books / iBooks nebo svinutí do trubičky, viz
 * [CurlStyle]). Stránka se odvaluje od hrany otáčení směrem k protější hraně - obsah kousek
 * před osou ohybu se komprimuje/zužuje podle úhlu na pomyslném válci, ne zrcadlí. Za viditelným
 * pásem (jakmile by úhel přesáhl doménu stylu) je papír už "odvalený" mimo pohled - tam prosvítá
 * odkrytá stránka pod ním.
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
     * k hraně otáčení) - roste od 0 do `radius × style.domainMax()` a pak zůstává na tomto
     * maximu, i když [foldX] pokračuje dál (zbytek stránky za pásem je odvalený mimo pohled). */
    val curlBandWidth: Float,
    /** Poloměr pomyslného válce - určuje, jak "těsně" se stránka odvaluje. */
    val radius: Float,
    /** 0f (žádný ohyb) .. 1f (stránka plně otočená). */
    val progress: Float,
    /** Vizuální styl ohybu - viz [CurlStyle]. */
    val style: CurlStyle,
)

/**
 * Spočítá geometrii ohybu pro stránku [pageWidth] x [pageHeight] při míře otočení [progress]
 * (0f..1f, absolutní hodnota - směr určuje [turningFromRight]) ve stylu [style].
 */
fun computePageCurlGeometry(
    pageWidth: Float,
    pageHeight: Float,
    turningFromRight: Boolean,
    progress: Float,
    style: CurlStyle = CurlStyle.CLASSIC,
): PageCurlGeometry {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val radius = when (style) {
        CurlStyle.CLASSIC -> (pageWidth * 0.18f).coerceAtLeast(24f)
        // Výrazně užší než CLASSIC - jinak by "svitek" byl tak široký, že by se svinutí vůbec
        // nestihlo opticky uzavřít do trubičky, než by narazil na maximum šířky stránky.
        CurlStyle.ROLL -> (pageWidth * 0.05f).coerceAtLeast(16f)
    }
    val maxBandWidth = radius * style.domainMax()
    val paperPastFold = clampedProgress * pageWidth
    val curlBandWidth = paperPastFold.coerceAtMost(maxBandWidth)
    val foldX = if (turningFromRight) pageWidth - paperPastFold else paperPastFold
    return PageCurlGeometry(pageWidth, pageHeight, turningFromRight, foldX, curlBandWidth, radius, clampedProgress, style)
}

/**
 * Pro vzdálenost [d] (0f..[PageCurlGeometry.curlBandWidth]) od osy ohybu vrátí skutečný posun
 * na obrazovce - `radius*sin(d/radius)`, vždy <= [d] (komprese, jak se obsah "zakulacuje" na
 * válci). U [d]=0 je posun 0 (přímo na ose ohybu). U [CurlStyle.CLASSIC] roste posun až po
 * `radius` na konci pásu (čtvrtina válce). U [CurlStyle.ROLL] posun nejdřív vyroste na `radius`
 * v polovině pásu a pak zase klesne zpátky k 0 na konci (půlka válce) - to je právě ta viditelná
 * "smyčka" trubičky, protože se obsah vrací zpátky k ose ohybu, jen z opačné (odvrácené) strany.
 */
fun PageCurlGeometry.warpedOffset(d: Float): Float {
    val theta = (d / radius).coerceIn(0f, style.domainMax())
    return radius * sin(theta)
}

/**
 * Stínovací faktor (násobitel jasu) pro vzdálenost [d] od osy ohybu - 1f přímo na ose (plný
 * jas). U [CurlStyle.CLASSIC] klesá k ~0.35f na konci pásu. U [CurlStyle.ROLL] klesá až k
 * [MIN_SHADE] v polovině dráhy zpátky (theta blízko π) - simuluje tmavší rubovou stranu
 * svinutého papíru, co se v tu chvíli natáčí k divákovi.
 */
fun PageCurlGeometry.shadeAt(d: Float): Float {
    val theta = (d / radius).coerceIn(0f, style.domainMax())
    return (0.35f + 0.65f * cos(theta)).coerceAtLeast(MIN_SHADE)
}

/**
 * Násobitel 0f..1f pro to, jak moc je ohyb vyjádřený ve svislé pozici [rowT] (0f horní okraj
 * stránky, 1f dolní okraj) - simuluje KÓNICKÝ ohyb (jako by čtenář uchopil stránku za roh a táhl
 * obloukem), ne čistě VÁLCOVÝ (rovnoměrný pruh přes celou výšku, jak fungovala geometrie dřív).
 * U [ANCHOR_ROW_T] (dolní roh) je násobitel 1f (plná síla ohybu), lineárně klesá k
 * `1f - CONICAL_TAPER_STRENGTH` na opačném konci. Volající tímhle násobí jak výsledný posun
 * z [warpedOffset], tak stínování z [shadeAt] (viz `PageCurlEffect.kt`) - řádky dál od rohu tak
 * mají zároveň menší vizuální ohyb i slabší stín, ne jen jedno z toho.
 */
fun PageCurlGeometry.verticalTaper(rowT: Float): Float {
    val dist = kotlin.math.abs(rowT.coerceIn(0f, 1f) - ANCHOR_ROW_T)
    return (1f - dist * CONICAL_TAPER_STRENGTH).coerceIn(1f - CONICAL_TAPER_STRENGTH, 1f)
}
