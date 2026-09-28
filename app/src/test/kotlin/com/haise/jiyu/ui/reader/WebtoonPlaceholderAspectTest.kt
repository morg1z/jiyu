package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Odhad poměru stran pro placeholder nenactenych webtoon stranek (viz
 * [medianPlaceholderAspect] / WebtoonReader.pageAspectSamples).
 *
 * Proč to existuje: fixní poměr 0.7 se minej realnemu pomeru stranek zdroje
 * (VIZBIG ~0.66, manhwa ~0.55, barevne dvoustranky i 1.4) a kazda stranka po
 * doloadovani zmenila vysku - LazyColumn pak "sedal" pod obsahem behem scrolu.
 * Median namerenych pomeru tlumi outliery a rychle se splete na realny pomer
 * aktualni kapitoly.
 */
class WebtoonPlaceholderAspectTest {

    @Test
    fun `empty samples fall back to the fixed estimate`() {
        assertEquals(0.7f, medianPlaceholderAspect(emptyList()))
    }

    @Test
    fun `single sample is used directly when inside bounds`() {
        assertEquals(0.66f, medianPlaceholderAspect(listOf(0.66f)), 0.0001f)
    }

    @Test
    fun `median dampens a wide-spread outlier among normal pages`() {
        // Jeden barevny spread (sirsi nez vyssi) uvnitr normalnich stranek nesmi
        // posunout placeholder - median ho ignoruje.
        val samples = listOf(0.66f, 0.67f, 0.66f, 1.45f, 0.65f, 0.67f, 0.66f)
        val result = medianPlaceholderAspect(samples)
        assertEquals(0.66f, result, 0.001f)
    }

    @Test
    fun `pathological ratios are clamped to sane bounds`() {
        // Rozbita stranka/mereni by bez orezu vytvorila placeholder vysky 0 nebo
        // monstrum - orez drzi 0.5..1.6.
        assertEquals(0.5f, medianPlaceholderAspect(listOf(0.05f, 0.1f, 0.2f)), 0.0001f)
        assertEquals(1.6f, medianPlaceholderAspect(listOf(4f, 5f, 6f)), 0.0001f)
    }

    @Test
    fun `manhwa-style tall pages converge to their own ratio`() {
        val samples = List(20) { 0.55f } + List(3) { 0.7f }
        val result = medianPlaceholderAspect(samples)
        assertEquals(0.55f, result, 0.001f)
    }
}
