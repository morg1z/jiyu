package com.haise.jiyu.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Řádkový profil obrázku stránky pro hledání obsahu napříč různě řezanými zdroji
 * (viz [com.haise.jiyu.source.PageGapFiller] - cross-source gap fill).
 *
 * Bitmapa se zmenší na pevnou šířku [PROFILE_WIDTH] (zachován poměr stran) a pro
 * každý řádek se spočítají 3 příznaky: průměr jasu, průměr levé a pravé poloviny.
 * Pás K řádků profilu ("jehla") se pak klouzavě hledá v profilu jiné stránky
 * ("sena") - přes MAD podobnost - a vrátí řádek, kde pás nejspíš leží. Profil je
 * odolný vůči rekompresi/přebarvení (webp vs jpg, jiný scanlator), protože
 * vychází z hrubé struktury řádků, ne z jednotlivých pixelů.
 *
 * Čistá JVM matematika + tenký Android adaptér [of] - testy jedou na syntetických
 * profilech bez bitmap.
 */
class RowSignature(
    /** Počet řádků profilu (= výška zmenšeného obrázku). */
    val rows: Int,
    /** width×3 hodnot: [mean, meanLeft, meanRight] na řádek, 0..255. */
    val data: FloatArray,
) {
    init { require(data.size == rows * 3) { "RowSignature data size mismatch" } }

    fun feature(row: Int, col: Int): Float = data[row * 3 + col]

    /**
     * Je pás informativní? Skoro jednolitý pás (čistá černá/bílá široká plocha,
     * typicky mezery mezi panely webtoonu) se "hodí" všude - takový výsledek by
     * byl falešný pozitiv, proto se odmítá (variací hodnot přes řádky i přes
     * pixely uvnitř řádků - odhad přes levá/pravá polarita).
     */
    fun isInformative(from: Int, count: Int): Boolean {
        val to = min(rows, from + count)
        if (to - from < 4) return false
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var spreadSum = 0f
        for (r in from until to) {
            val m = feature(r, 0)
            minV = min(minV, m); maxV = max(maxV, m)
            spreadSum += abs(feature(r, 1) - feature(r, 2))
        }
        // Rozptyl průměrů řádků (obsah se po ose Y mění) NEBO průměrná vnitřní
        // asymetrie řádků (kresba v řádku) - jedno z nich musí být nenulové.
        return (maxV - minV) >= MIN_PROFILE_RANGE || (spreadSum / (to - from)) >= MIN_ROW_ASYMMETRY
    }

    companion object {
        /** Rozptyl řádkových jasů, pod kterým je pás "holý" (stejná plocha všude). */
        const val MIN_PROFILE_RANGE = 14f
        /** Průměrná asymetrie levá/pravá půlka řádku pro uznaní struktury. */
        const val MIN_ROW_ASYMMETRY = 10f
    }
}

object RowProfileMatcher {

    const val PROFILE_WIDTH = 64
    /** Výška jehly ve zmenšených řádcích - ~¼ typického 1280px slici. */
    const val NEEDLE_ROWS = 26
    /** Minimální podobnost pro přijetí shody (0..1) - pod ní odmítnout. */
    const val MIN_MATCH_SCORE = 0.78f
    /** Minimální náskok nejlepší shody nad druhou nejlepší - jinak nejednoznačné. */
    const val MIN_MATCH_MARGIN = 0.05f

    class Match(val row: Int, val score: Float, val margin: Float)

    /**
     * Klouzavě hledá [needle] (pás řádků) v [haystack]. Vrací nejlepší pozici
     * s docíleným skóre a náskokem, nebo null když nic nepřesáhne práh.
     * O(X×Y×3) MAD - profily jsou malé (desítky řádků), tj. rychlé.
     */
    fun findStrip(needle: RowSignature, haystack: RowSignature): Match? {
        val n = needle.rows
        val h = haystack.rows
        if (n == 0 || h < n) return null
        var best = -1
        var bestScore = -1f
        var secondBest = -1f
        for (off in 0..(h - n)) {
            var mad = 0f
            for (r in 0 until n) {
                for (c in 0 until 3) {
                    mad += abs(needle.feature(r, c) - haystack.feature(off + r, c))
                }
            }
            mad /= (n * 3)
            val score = max(0f, 1f - mad / 96f)
            if (score > bestScore) {
                secondBest = bestScore
                bestScore = score
                best = off
            } else if (score > secondBest) {
                secondBest = score
            }
        }
        if (best < 0 || bestScore < MIN_MATCH_SCORE) return null
        val margin = if (secondBest < 0) bestScore else bestScore - secondBest
        return Match(best, bestScore, margin)
    }

    /**
     * Vezme [count] řádků od okraje [signature] (top = začátek, bottom = konec),
     * přeskočí [skipEdge] krajních řádků (rozmazané okraje řezu) a případně jehlu
     * prodlouží víc dovnitř, dokud není informativní (viz [RowSignature.isInformative])
     * - max do [maxCount]. Null = stránka nemá použitelný okrajový pás.
     */
    fun edgeStrip(signature: RowSignature, top: Boolean, count: Int = NEEDLE_ROWS, skipEdge: Int = 2, maxCount: Int = NEEDLE_ROWS * 4): RowSignature? {
        var rows = count.coerceAtMost(signature.rows - skipEdge)
        while (rows > 0) {
            val from = if (top) skipEdge else signature.rows - skipEdge - rows
            if (from < 0) return null
            if (signature.isInformative(from, rows)) return slice(signature, from, rows)
            rows += 8
            if (rows > maxCount) return null
        }
        return null
    }

    private fun slice(sig: RowSignature, from: Int, rows: Int): RowSignature {
        val out = FloatArray(rows * 3)
        System.arraycopy(sig.data, from * 3, out, 0, rows * 3)
        return RowSignature(rows, out)
    }

    /** Převede řádek profilu na řádek zdrojového obrázku. */
    fun rowToSource(row: Int, signature: RowSignature, sourceHeight: Int): Int =
        (row.toFloat() * sourceHeight / signature.rows).roundToInt().coerceIn(0, sourceHeight)

    /**
     * Android adaptér: bitmapa → profil. Šířka se normalizuje na [PROFILE_WIDTH]
     * (řádky různě širokých stránek pak mají srovnatelný význam), výška drží poměr.
     * Jen luma kanál (R+G+B)/3 - profil chce strukturu, ne barvu.
     */
    fun of(bitmap: Bitmap): RowSignature {
        val w = bitmap.width
        val h = bitmap.height
        val scale = PROFILE_WIDTH.toFloat() / w
        val scaledW = PROFILE_WIDTH
        val scaledH = max(1, (h * scale).roundToInt())
        val small = if (w == scaledW && h == scaledH) bitmap else Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val px = IntArray(scaledW * scaledH)
        small.getPixels(px, 0, scaledW, 0, 0, scaledW, scaledH)
        val data = FloatArray(scaledH * 3)
        for (y in 0 until scaledH) {
            var sumL = 0f
            var sumR = 0f
            val half = scaledW / 2
            for (x in 0 until scaledW) {
                val c = px[y * scaledW + x]
                val luma = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3f
                if (x < half) sumL += luma else sumR += luma
            }
            val mean = (sumL + sumR) / scaledW
            data[y * 3] = mean
            data[y * 3 + 1] = sumL / half
            data[y * 3 + 2] = sumR / (scaledW - half)
        }
        return RowSignature(scaledH, data)
    }
}
