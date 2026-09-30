package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Co si render připravil pro jednu bublinu nad rámec dat z překladu:
 *
 * - [patch] = záplata pozadí pro text ležící přímo na kresbě (viz [buildTextPatch]),
 * - [textArgb] = jádrová barva původního písma v záplatě (viz meanTextArgbOut u
 *   [buildTextPatch]) - překlad se jí kreslí místo volby černá/bílá podle pozadí, takže
 *   lettering drží styl originálu,
 * - [recovered] = obrys bubliny znovunalezený při vykreslení (viz [recoverBubble]) pro bloky,
 *   kde detekce tvaru při OCR selhala - typicky proto, že navzorkovaný prstenec kolem OCR
 *   boxu ležel mimo bublinu.
 *
 * Vše se počítá až při zobrazení stránky z pixelů, které [TextPatchProvider] pro záplaty
 * stejně načítá, a výsledek se ukládá JEN do paměti - do Room nic nepřibývá a nemusí se
 * zvedat `PIPELINE_VERSION`, takže se opraví i už dřív přeložené stránky.
 */
data class BubbleOverlayFix(
    val patch: Bitmap? = null,
    val textArgb: Int? = null,
    val recovered: RecoveredBubble? = null,
    /** Jak dopadla výroba záplaty pro tuhle bublinu (viz [PatchState]) - diagnostika. */
    val patchState: PatchState = PatchState.NOT_PLANNED,
    /** Rozměry vykrojeného obdélníku v pixelech zmenšené bitmapy (0 = záplata se nestavěla). */
    val patchWidthPx: Int = 0,
    val patchHeightPx: Int = 0,
    /** Jak dopadla render-time obnova obrysu (viz [RecoveryState]) - diagnostika. */
    val recoveryState: RecoveryState = RecoveryState.NOT_CANDIDATE,
    /** Konkrétní důvod zamítnutí recovery (viz onReject v [recoverBubble]). */
    val recoveryReject: String? = null,
)

/** Výsledek výroby záplaty pro jednu bublinu - viz recordRender v TranslationDiagnostics. */
enum class PatchState {
    /** Bublina záplatu nikdy nechtěla (jednolité pozadí / SFX / nepřeložená / art-text). */
    NOT_PLANNED,
    /** Záplata se postavila a kreslí se. */
    BUILT,
    /** Bitmapa stránky se nenačetla - žádná záplata ani recovery neexistuje. */
    NO_BITMAP,
    /** Výpočet záplat vyhodil výjimku - bubliny se vykreslují fallback cestou. */
    FAILED,
    /** Render box přesáhl MAX_PATCH_PIXELS - záplata by sama byla placka. */
    TOO_BIG,
    /** buildTextPatch vrátil prázdno (krajový/degenerovaný výřez). */
    EMPTY,
    /** Celá oblast padla do masky textu - záplata by byla jednolitá = placka. */
    DEGENERATE_UNIFORM,
    /** Záplata se spočítala, ale bitmapa z ní nesestavila (0 řádků po ořezu). */
    BAD_BITMAP,
}

/** Výsledek render-time obnovy obrysu bubliny - viz recordRender v TranslationDiagnostics. */
enum class RecoveryState {
    /** Blok obnovu nepotřebuje (má obrys z OCR / nepřekládaný / art-text / SFX). */
    NOT_CANDIDATE,
    /** Obrys se znovunašel - blok se kreslí jako skutečná bublina. */
    RECOVERED,
    /** Recovery se pokusila a zamítla - důvod je v [BubbleOverlayFix.recoveryReject]. */
    REJECTED,
    /** Bitmapa stránky se nenačetla - recovery se vůbec nepokusila. */
    NO_BITMAP,
    /** Výpočet vyhodil výjimku před doběhnutím recovery. */
    FAILED,
}

/**
 * Vyrábí a drží záplaty pozadí pro bubliny, které leží přímo na kresbě (viz [buildTextPatch]),
 * a zároveň znovunachází obrysy bublin, kde selhala OCR-time detekce (viz [recoverBubble]).
 *
 * Počítá se to až při zobrazení stránky, ne při překladu, a výsledek se ukládá JEN do paměti.
 * Díky tomu do Room nic nepřibývá a nemusí se zvedat `PIPELINE_VERSION` - cache hotových
 * překladů tuhle změnu přežije.
 *
 * ## Proč tolik opatrnosti kolem paměti
 * Stránky webtoonů bývají extrémně vysoké (u některých zdrojů přes 15 000 px). Dekódovat
 * takovou stránku v plném rozlišení kvůli záplatě několika bublin je spolehlivý způsob, jak
 * appku shodit na OOM. Proto:
 *  - bitmapa se vůbec nenačítá, když na stránce žádná bublina záplatu ani obnovu nepotřebuje
 *    (všechny bloky mají svůj obrys už z OCR),
 *  - načítá se zmenšená na [PATCH_SOURCE_MAX_DIMENSION] (v záplatě se ztratí trochu ostrosti,
 *    ale nahrazuje se tím jednolitá barevná placka, takže je to výhodný obchod),
 *  - reference na ni se pouští hned po spočítání záplat, v paměti zůstávají jen ty malé výřezy,
 *  - a i ty drží [LruCache] se stropem v bajtech, ne v počtu položek.
 */
@Singleton
class TextPatchProvider @Inject constructor(
    private val pageBitmapLoader: PageBitmapLoader,
) {
    private val cache = object : LruCache<String, Map<Int, BubbleOverlayFix>>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Map<Int, BubbleOverlayFix>): Int =
            value.values.sumOf { (it.patch?.byteCount ?: 0) + SHAPE_ENTRY_BYTES }.coerceAtLeast(1)
    }

    /**
     * @param positioned bloky i s obdélníkem, přes který se doopravdy vykreslí - záplata se
     *   počítá přesně přes něj (viz [patchPlan]), ne přes OCR box textu.
     * @return fixy klíčované pozicí v [positioned]; prázdná mapa, když není co záplatovat ani
     *   obnovovat, nebo se stránku nepodařilo načíst (volající pak jen nakreslí výplň jako
     *   dosud).
     */
    suspend fun patchesFor(pageUrl: String, positioned: List<PositionedTranslationBlock>): Map<Int, BubbleOverlayFix> {
        val plan = patchPlan(positioned)
        // Kandidáti na obnovu obrysu: bloky, které se vykreslí a tvar nemají - u nich hrozí
        // heuristický box přetékající přes okraj bubliny (viz [recoverBubble]). Bloky s
        // obrysem z OCR i SFX/nepřeložené/art-text (ty se nekreslí vůbec) se přeskočí.
        // Text-na-kresbě (bgUniform=false) se sem nevyřazuje: recovery je právě ten
        // mechanismus, který pozná, jestli jde o skutečnou bublinu (viz art_lettering
        // v BubbleOverlayLayer) - u lettering v malbě vrátí null a blok se pak vykreslí
        // jen přes záplatu, jinak zůstane originál nedotčený.
        val recoverable = positioned.withIndex()
            .filter { (_, pos) -> pos.block.shape == null && !pos.block.isSfx && !pos.block.isUntranslated && !pos.block.isArtText }
            .map { it.index }
        if (plan.isEmpty() && recoverable.isEmpty()) return emptyMap()

        // Klíč pokrývá VŠECHNY bloky - obrys souseda (přes clampShapeToOwnLobe) i heuristické
        // boxy ovlivňují, kam recovery/layout dosáhne, takže změna libovolného z nich musí
        // cache zneplatnit.
        val key = "$pageUrl#" + positioned.withIndex().joinToString(",") { (i, pos) ->
            val b = pos.block
            "$i:${pos.leftF},${pos.minTopF},${pos.rightF},${pos.maxBottomF},${b.leftF},${b.topF},${b.rightF},${b.bottomF}"
        }
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.Default) {
            val bitmap = pageBitmapLoader.load(pageUrl, PATCH_SOURCE_MAX_DIMENSION)
            val result = when {
                bitmap == null -> diagOnlyMap(plan.keys, recoverable, PatchState.NO_BITMAP, RecoveryState.NO_BITMAP)
                else -> runCatching { buildFixes(bitmap, positioned, plan, recoverable) }
                    .onFailure { it.report("translate:patch:build") }
                    .getOrElse { diagOnlyMap(plan.keys, recoverable, PatchState.FAILED, RecoveryState.FAILED) }
            }
            // Diag-only mapy (NO_BITMAP/FAILED) se necachují - přechodné selhání by jinak
            // přežilo do konce session a stránka by se o záplatu už nikdy nepokusila.
            if (result.values.any { it.patch != null || it.recovered != null }) cache.put(key, result)
            result
        }
    }

    /**
     * Mapa čistě pro diagnostiku, když se záplaty/recovery vůbec nespočítaly - každý dotčený
     * blok dostane fix se stavem, aby recordRender věděl, že se o něj pokoušelo (a čím to
     * skončilo), místo aby hádal ze statických flagů bloku.
     */
    private fun diagOnlyMap(
        planned: Set<Int>,
        recoverable: List<Int>,
        patchState: PatchState,
        recoveryState: RecoveryState,
    ): Map<Int, BubbleOverlayFix> =
        (planned + recoverable).associateWith { i ->
            BubbleOverlayFix(
                patchState = if (i in planned) patchState else PatchState.NOT_PLANNED,
                recoveryState = if (i in recoverable) recoveryState else RecoveryState.NOT_CANDIDATE,
            )
        }

    private fun buildFixes(
        bitmap: Bitmap,
        positioned: List<PositionedTranslationBlock>,
        plan: Map<Int, PatchRect>,
        recoverable: List<Int>,
    ): Map<Int, BubbleOverlayFix> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyMap()
        val source = PixelSource { x, y -> bitmap.getPixel(x, y) }

        // 1) Obnova obrysů z interiéru OCR boxů (viz [recoverBubble]). Běží před záplatami,
        //    protože nalezený obrys mění i obdélník, přes který se záplata počítá - u
        //    přetékající bubliny je výřez obrysu menší a správně umístěný oproti
        //    heuristickému boxu.
        val allRaw = positioned.map {
            RawTextBlock(
                text = it.block.originalText,
                leftF = it.block.leftF,
                topF = it.block.topF,
                rightF = it.block.rightF,
                bottomF = it.block.bottomF,
            )
        }
        val recovered = HashMap<Int, RecoveredBubble>()
        val recoveryRejects = HashMap<Int, String>()
        var rejected = 0
        for (i in recoverable) {
            val own = allRaw[i]
            var rejectReason: String? = null
            val r = recoverBubble(
                source = source,
                width = w,
                height = h,
                leftF = own.leftF,
                topF = own.topF,
                rightF = own.rightF,
                bottomF = own.bottomF,
                own = own,
                others = allRaw.filterIndexed { j, _ -> j != i },
                onReject = { rejectReason = it },
            )
            // Stejná pojistka jako dropDegenerateShape při OCR: flood-fill může uniknout
            // z bubliny do kresby i tady (tmavá scéna s dominantním kbelíkem projde
            // "no_dominant_color" brankou a poměrový strop 60x je moc povolný pro obrys
            // ~6x větší než text). Bez kontroly by se zahozený leak vrátil přes
            // recoveredShape a zakryl půlku stránky (audit RWS ch.215 p77).
            if (r != null && !isDegenerateShapeForText(r.shape, positioned[i].block)) {
                recovered[i] = r
            } else {
                rejected++
                recoveryRejects[i] = if (r == null) rejectReason ?: "unknown" else "shape_leaked"
            }
        }
        // Observabilita stejného stylu jako ShapeCoverage v OcrEngine: kolik shapeless
        // bublin se při vykreslení podařilo zachránit (`adb logcat -s BubbleRecovery`).
        if (BuildConfig.DEBUG && recoverable.isNotEmpty()) {
            Log.d("BubbleRecovery", "recovered=${recovered.size} rejected=$rejected of=${recoverable.size}")
        }

        // 2) Záplaty - přes obnovený obrys, když existuje (interiér bubliny, žádný okraj
        //    stránky v řezu), jinak přes heuristický box jako dosud.
        val patches = HashMap<Int, Pair<Bitmap, Int?>>()
        val patchDiag = HashMap<Int, PatchState>()
        val patchDims = HashMap<Int, Pair<Int, Int>>()
        for ((index, rect) in plan) {
            val b = positioned[index].block
            val shape = recovered[index]?.shape
            val effRect = if (shape != null) {
                PatchRect(
                    leftF = shape.minOf { it.leftF },
                    topF = shape.first().yF,
                    rightF = shape.maxOf { it.rightF },
                    bottomF = shape.last().yF,
                )
            } else {
                rect
            }
            val left = (effRect.leftF * w).toInt()
            val top = (effRect.topF * h).toInt()
            val right = (effRect.rightF * w).toInt()
            val bottom = (effRect.bottomF * h).toInt()
            val boxW = (right - left).coerceAtMost(w)
            val boxH = (bottom - top).coerceAtMost(h)
            if (boxW <= 0 || boxH <= 0) {
                patchDiag[index] = PatchState.EMPTY
                continue
            }
            // Absurdně velký box (chybná OCR souřadnice) by znamenal záplatu přes půl stránky -
            // to už není oprava, to je nová placka. Radši nechat původní výplň.
            if (boxW.toLong() * boxH > MAX_PATCH_PIXELS) {
                patchDiag[index] = PatchState.TOO_BIG
                patchDims[index] = boxW to boxH
                continue
            }

            val textArgbOut = IntArray(1)
            val argb = buildTextPatch(
                source = source,
                imageWidth = w,
                imageHeight = h,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                // Záchranná barva (kdyby záplata neměla z čeho dopočítat) - pro obnovenou
                // bublinu je nejlepší důkaz barva jejího interiéru, ne znečištěný prstenec.
                bgArgb = recovered[index]?.interiorArgb ?: b.bgColorArgb,
                // Písmo se hledá jen tam, kde ho OCR opravdu našlo - zbytek boxu je kresba,
                // kterou nemá smysl prahovat ani dopočítávat (viz [buildTextPatch]).
                textLeft = (b.leftF * w).toInt(),
                textTop = (b.topF * h).toInt(),
                textRight = (b.rightF * w).toInt(),
                textBottom = (b.bottomF * h).toInt(),
                meanTextArgbOut = textArgbOut,
            )
            if (argb.isEmpty()) {
                patchDiag[index] = PatchState.EMPTY
                patchDims[index] = boxW to boxH
                continue
            }
            // Degenerovaná záplata: celá oblast padla do masky písma a dopočítat se nedalo
            // (viz buildTextPatch - vrátí jednolitou barvu). U bubliny s obrysem je
            // jednolitý výsledek neškodný (ořízne se konturou a splývá s výplní), ale u
            // lettering-na-kresbě je to přesně ta placka přes malbu, kvůli které záplata
            // vznikla - takový blok se má radši přeskočit, ne překrýt barvou.
            if (b.shape == null && shape == null && argb.isUniform()) {
                patchDiag[index] = PatchState.DEGENERATE_UNIFORM
                patchDims[index] = boxW to boxH
                continue
            }
            val clampedW = minOf(boxW, w - left.coerceAtLeast(0))
            val rows = argb.size / clampedW.coerceAtLeast(1)
            if (rows <= 0) {
                patchDiag[index] = PatchState.BAD_BITMAP
                patchDims[index] = boxW to boxH
                continue
            }
            // Jádrová barva písma (0 = nepodařilo se určit - render pak volí černou/bílou
            // podle pozadí jako dosud).
            val textArgb = textArgbOut[0].takeIf { it ushr 24 != 0 }
            patches[index] = Bitmap.createBitmap(argb, clampedW, rows, Bitmap.Config.ARGB_8888) to textArgb
            patchDiag[index] = PatchState.BUILT
            patchDims[index] = boxW to boxH
        }

        return (patches.keys + recovered.keys + patchDiag.keys + recoverable).associateWith { i ->
            BubbleOverlayFix(
                patch = patches[i]?.first,
                textArgb = patches[i]?.second,
                recovered = recovered[i],
                patchState = patchDiag[i] ?: PatchState.NOT_PLANNED,
                patchWidthPx = patchDims[i]?.first ?: 0,
                patchHeightPx = patchDims[i]?.second ?: 0,
                recoveryState = when {
                    recovered.containsKey(i) -> RecoveryState.RECOVERED
                    i in recoverable -> RecoveryState.REJECTED
                    else -> RecoveryState.NOT_CANDIDATE
                },
                recoveryReject = recoveryRejects[i],
            )
        }
    }

    /** Celá záplata jednou barvou = nepodařený after-all masking (viz [buildTextPatch]). */
    private fun IntArray.isUniform(): Boolean {
        if (isEmpty()) return true
        val first = this[0]
        for (i in 1 until size) if (this[i] != first) return false
        return true
    }

    companion object {
        /** Zmenšení zdrojové stránky pro účely záplaty - viz komentář u třídy. */
        private const val PATCH_SOURCE_MAX_DIMENSION = 1600

        /**
         * Strop plochy jedné záplaty (~2,4 MB jako ARGB). Zvednuto z 300 000 spolu s přechodem
         * na obdélník celého boxu (viz [patchPlan]): ten je u textu na kresbě znatelně větší
         * než OCR box, ze kterého se počítalo dřív, a při starém stropu by řada bloků spadla
         * zpátky na jednolitou výplň - tedy přesně na tu placku, kvůli které záplata vznikla.
         */
        private const val MAX_PATCH_PIXELS = 600_000L

        /** Strop celé vyrovnávací paměti záplat. */
        private const val CACHE_BYTES = 12 * 1024 * 1024

        /** Nominální cena obnoveného obrysu v cache (24 bodů x 3 floaty - zanedbatelné vedle bitmapy). */
        private const val SHAPE_ENTRY_BYTES = 512
    }
}
