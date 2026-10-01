package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowProfileMatcherTest {

    /** Profil s `rows` řádky; řádek = trojice [mean, left, right]. */
    private fun sig(rows: Int, value: (Int) -> Triple<Float, Float, Float>): RowSignature {
        val d = FloatArray(rows * 3)
        for (r in 0 until rows) {
            val (m, l, rr) = value(r)
            d[r * 3] = m; d[r * 3 + 1] = l; d[r * 3 + 2] = rr
        }
        return RowSignature(rows, d)
    }

    /** Deterministický "obsahový" řádek - měnící se struktura po ose i v řádku. */
    private fun contentRow(r: Int): Triple<Float, Float, Float> {
        val m = 40f + ((r * 37 + r * r * 7) % 170)
        return Triple(m, (m + (r % 5) * 30) % 256f, (m * 0.7f + (r % 3) * 40) % 256f)
    }

    @Test
    fun `jehla se najde na spravnem offsetu v delsim senu`() {
        // Sena = 300 radku obsahu; jehla = rady 120..145 téhož obsahu (simulace:
        // spodek stranky A lezi uvnitr vysoké donor stranky).
        val hay = sig(300) { contentRow(it) }
        val needle = sig(26) { contentRow(120 + it) }
        val m = RowProfileMatcher.findStrip(needle, hay)
        assertEquals(120, m?.row)
        assertTrue((m?.score ?: 0f) > 0.95f)
    }

    @Test
    fun `jehla se najde i se summem - rekompresni sum znackuje jen malo`() {
        val hay = sig(300) { contentRow(it) }
        val needle = sig(26) {
            val (m, l, r) = contentRow(200 + it)
            Triple(m + 6f, l - 4f, r + 3f) // jpg vs webp sum
        }
        val m = RowProfileMatcher.findStrip(needle, hay)
        assertEquals(200, m?.row)
        assertTrue((m?.score ?: 0f) >= RowProfileMatcher.MIN_MATCH_SCORE)
    }

    @Test
    fun `cizi obsah se nenajde - pod prahem`() {
        val hay = sig(200) { contentRow(it) }
        // Jehla z jine "stranky" - jiny generator (contentRow je mod-170 periodicky,
        // posunuta varianta teze funkce by se nasla jako shoda).
        val needle = sig(26) {
            val m = 190f - ((it * 53 + it * it * 11) % 150)
            Triple(m, (m * 0.4f + (it % 7) * 20) % 256f, (m * 1.3f + (it % 4) * 35) % 256f)
        }
        assertNull(RowProfileMatcher.findStrip(needle, hay))
    }

    @Test
    fun `jehla delsi nez sena vraci null`() {
        assertNull(RowProfileMatcher.findStrip(sig(30) { contentRow(it) }, sig(10) { contentRow(it) }))
    }

    @Test
    fun `edgeStrip preskoci okraj a vrati informativni pas`() {
        // Stranka: 3 bile okrajove radky, pak obsah
        val page = sig(60) { if (it < 3) Triple(255f, 255f, 255f) else contentRow(it) }
        val top = RowProfileMatcher.edgeStrip(page, top = true, skipEdge = 1)
        // strip zacina radek 1 (preskoceny okraj) a natahne se az po informativni cast
        assertTrue(top != null)
        // od r.1 obsah zacina na r.3 -> prvni 2 radky strip jsou bile, zbytek obsah
        assertTrue(top!!.isInformative(0, top.rows))
    }

    @Test
    fun `edgeStrip zdola bere spodni pas`() {
        val page = sig(60) { contentRow(it) }
        val bottom = RowProfileMatcher.edgeStrip(page, top = false, count = 20, skipEdge = 2)
        assertTrue(bottom != null)
        assertEquals(20, bottom!!.rows)
        // over obsah = slice radku 38..57 (posledni radek slice = index 57)
        assertEquals(contentRow(57).first, bottom.feature(19, 0), 0.01f)
    }

    @Test
    fun `edgeStrip na holou stranku vrati null - bile stranky se nehledaji`() {
        val blank = sig(80) { Triple(250f, 250f, 250f) }
        assertNull(RowProfileMatcher.edgeStrip(blank, top = true, maxCount = 40))
    }

    @Test
    fun `rowToSource mapuje radek profilu na radek obrazku`() {
        val sig = RowSignature(100, FloatArray(300))
        assertEquals(0, RowProfileMatcher.rowToSource(0, sig, 1000))
        assertEquals(500, RowProfileMatcher.rowToSource(50, sig, 1000))
        assertEquals(1000, RowProfileMatcher.rowToSource(100, sig, 1000))
    }

    @Test
    fun `isInformative rozlisi obsah od plochy`() {
        val content = sig(30) { contentRow(it) }
        val blank = sig(30) { Triple(255f, 255f, 255f) }
        assertTrue(content.isInformative(0, 30))
        assertTrue(!blank.isInformative(0, 30))
        // kratasi pas nez 4 radky nikdy neinformativni
        assertTrue(!content.isInformative(0, 3))
    }
}
