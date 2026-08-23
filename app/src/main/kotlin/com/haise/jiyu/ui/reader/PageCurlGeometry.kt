package com.haise.jiyu.ui.reader

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vizuální styly otáčení stránky - viz [PageCurlGeometry.style].
 */
enum class CurlStyle {
    /** Plochý ohyb po (téměř) celé výšce stránky, jako klasické otáčení listu v knize -
     * čtvrtina pomyslného válce (0..π/2), velký poloměr. */
    CLASSIC,

    /** Stránka se svinuje do úzké trubičky, která putuje napříč stránkou podle tažení - půlka
     * pomyslného válce (0..π), malý konstantní poloměr. Viditelná i "rubová" (tmavší) strana
     * svinutého papíru. */
    ROLL,

    /** Stejná válcová geometrie jako [ROLL] (sdílí [computePageCurlGeometry]/[warpedOffset]/
     * [shadeAt]), ale poloměr NENÍ konstantní - roste s tím, kolik papíru je už otočeno
     * (`radius = paperPastFold / π`), takže "svitek" místo tenké trubičky ztloustne přes celou
     * odvalenou délku - žádný plochý zbytek navíc, celá otočená část je vždy vidět jako válec. */
    CYLINDER,

    /** Vlastní sinusová geometrie (NE válcová, nepoužívá [warpedOffset]/[shadeAt]) - viz
     * [com.haise.jiyu.ui.reader.drawWaveCurl]. Otočená část se prohne jako mořská vlna (hrb
     * uprostřed pásu), na vrcholu se "zláme" jako přehoz zpátky. */
    WAVE,
}

/** Převede uloženou textovou hodnotu nastavení ([com.haise.jiyu.settings.CurlStyleSetting]) na
 * [CurlStyle] - sdíleno mezi [com.haise.jiyu.ui.reader.MangaPageCurlReader] a
 * [com.haise.jiyu.ui.reader.PageCurlNovelReader], aby obě čtečky nezávisle nezapomněly na nově
 * přidaný styl (dřív každá měla vlastní binární `if (== ROLL) ROLL else CLASSIC`). */
fun resolveCurlStyle(value: String): CurlStyle = when (value) {
    com.haise.jiyu.settings.CurlStyleSetting.ROLL -> CurlStyle.ROLL
    com.haise.jiyu.settings.CurlStyleSetting.CYLINDER -> CurlStyle.CYLINDER
    com.haise.jiyu.settings.CurlStyleSetting.WAVE -> CurlStyle.WAVE
    else -> CurlStyle.CLASSIC
}

/** Maximální úhel na pomyslném válci, který [style] ještě vykresluje - určuje, jestli je vidět
 * jen čtvrtina válce (plochý ohyb) nebo půlka (svinutá trubička, viditelná i odvrácená strana). */
private fun CurlStyle.domainMax(): Float = when (this) {
    CurlStyle.CLASSIC -> PI.toFloat() / 2f
    CurlStyle.ROLL -> PI.toFloat()
    // Stejná půlka válce jako ROLL - jen poloměr (viz computePageCurlGeometry) roste s
    // paperPastFold misto aby byl konstantni, takze curlBandWidth == paperPastFold vzdy presne
    // (zadny plochy zbytek navic, viz CurlStyle.CYLINDER dokumentace).
    CurlStyle.CYLINDER -> PI.toFloat()
    // Nepouziva se pro warpedOffset/shadeAt (WAVE ma vlastni sinusovou geometrii v
    // drawWaveCurl) - jen omezuje maximalni sirku "vlneni" pres radius*domainMax nize.
    CurlStyle.WAVE -> 1f
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

/** Kolikrát dál je "kamera" (virtuální oko diváka) než poloměr válce - řídí sílu perspektivy
 * v [warpedOffset]. Menší číslo = silnější/dramatičtější perspektivní zkreslení (body dál od
 * diváka se komprimují víc k ose ohybu); větší = slabší, blíž staré čistě ortografické projekci
 * (`radius*sin(theta)` bez dělení hloubkou). 3.5 je střední hodnota - jediné vyladitelné číslo,
 * kdyby efekt na reálném zařízení působil moc slabě nebo moc silně. */
private const val FOCAL_DISTANCE_MULTIPLIER = 3.5f

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
    val paperPastFold = clampedProgress * pageWidth
    val radius = when (style) {
        CurlStyle.CLASSIC -> (pageWidth * 0.18f).coerceAtLeast(24f)
        // Výrazně užší než CLASSIC - jinak by "svitek" byl tak široký, že by se svinutí vůbec
        // nestihlo opticky uzavřít do trubičky, než by narazil na maximum šířky stránky.
        CurlStyle.ROLL -> (pageWidth * 0.05f).coerceAtLeast(16f)
        // Roste s paperPastFold (ne konstantni jako ROLL) - radius*domainMax(=π) tak vzdy vyjde
        // presne paperPastFold, viz maxBandWidth nize (zadne coerceAtMost oriznuti). Floor 16f
        // jen pro bezpecne deleni pri paperPastFold blizko 0 (curlBandWidth tam stejne vyjde
        // male, protoze je to min(paperPastFold, maxBandWidth) a paperPastFold je ten mensi).
        CurlStyle.CYLINDER -> (paperPastFold / PI.toFloat()).coerceAtLeast(16f)
        // Maximalni sirka pasu, na kterem se vlni - viz drawWaveCurl.
        CurlStyle.WAVE -> (pageWidth * 0.35f).coerceAtLeast(40f)
    }
    val maxBandWidth = radius * style.domainMax()
    val curlBandWidth = paperPastFold.coerceAtMost(maxBandWidth)
    val foldX = if (turningFromRight) pageWidth - paperPastFold else paperPastFold
    return PageCurlGeometry(pageWidth, pageHeight, turningFromRight, foldX, curlBandWidth, radius, clampedProgress, style)
}

/**
 * Pro vzdálenost [d] (0f..[PageCurlGeometry.curlBandWidth]) od osy ohybu vrátí skutečný
 * PERSPEKTIVNĚ SPRÁVNÝ posun na obrazovce - ne jen `radius*sin(theta)` (to by byla ortografická
 * projekce, jako by se divák díval z nekonečné vzdálenosti). Bod na povrchu válce v úhlu `theta`
 * má 3D pozici X=`radius*sin(theta)` (vodorovně) a Z=`radius*(1-cos(theta))` (hloubka - jak moc
 * se vzdaluje od diváka, jak se ohyb "otáčí pryč"). Skutečná kamera v konečné vzdálenosti
 * (pinhole model, `focal/(focal+Z)`) vzdálenější body komprimuje k ose ohybu o něco víc než
 * ortografická projekce - proto ohyb teď vypadá jako fyzická 3D perspektiva otáčející se
 * stránky, ne jako mechanicky protažený sinusový pruh.
 *
 * Výsledek je vždy <= [d] (komprese, jak se obsah "zakulacuje" na válci, teď navíc ještě víc
 * kvůli perspektivnímu dělení). U [d]=0 je posun 0 (přímo na ose ohybu). U [CurlStyle.CLASSIC]
 * roste posun až k `radius` na konci pásu (čtvrtina válce, `theta`=π/2), ale díky perspektivě
 * ho nikdy úplně nedosáhne. U [CurlStyle.ROLL] posun nejdřív vyroste (vrchol o něco před polovinou
 * pásu, protože perspektiva vrchol mírně posouvá blíž k ose) a pak zase klesne přesně k 0 na
 * konci (`theta`=π, `sin(π)`=0 přesně, takže perspektivní dělení na tom nic nemění) - to je právě
 * ta viditelná "smyčka" trubičky, protože se obsah vrací zpátky k ose ohybu, jen z opačné strany.
 */
fun PageCurlGeometry.warpedOffset(d: Float): Float {
    val theta = (d / radius).coerceIn(0f, style.domainMax())
    val x = radius * sin(theta)
    val z = radius * (1f - cos(theta))
    val focal = radius * FOCAL_DISTANCE_MULTIPLIER
    return focal * x / (focal + z)
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
