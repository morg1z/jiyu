package com.haise.jiyu.data.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mapovani Comick <-> Jiyu stavu a normalizace titulu pro fuzzy matching
 * (ciste funkce z [ComickImportManager] - bez DB/site).
 */
class ComickMappingTest {

    @Test
    fun `comick statuses 1-5 map 1to1 to Jiyu reading statuses`() {
        assertEquals("READING", comickStatusToLocal(1))
        assertEquals("COMPLETED", comickStatusToLocal(2))
        assertEquals("ON_HOLD", comickStatusToLocal(3))
        assertEquals("DROPPED", comickStatusToLocal(4))
        assertEquals("PLAN_TO_READ", comickStatusToLocal(5))
    }

    @Test
    fun `status mapping round-trips in both directions`() {
        for (code in 1..5) {
            assertEquals(code, localStatusToComick(comickStatusToLocal(code)))
        }
        for (s in listOf("READING", "COMPLETED", "ON_HOLD", "DROPPED", "PLAN_TO_READ")) {
            assertEquals(s, comickStatusToLocal(localStatusToComick(s)))
        }
    }

    @Test
    fun `unknown or null status maps to null in both directions`() {
        assertNull(comickStatusToLocal(null))
        assertNull(comickStatusToLocal(0))
        assertNull(comickStatusToLocal(6))
        assertNull(localStatusToComick(null))
        assertNull(localStatusToComick("UNKNOWN"))
    }

    @Test
    fun `publication status maps to Jiyu status, translation_completed wins`() {
        assertEquals("ONGOING", comickPublicationStatus(1, false))
        assertEquals("COMPLETED", comickPublicationStatus(2, false))
        assertEquals("DROPPED", comickPublicationStatus(3, false))
        assertEquals("HIATUS", comickPublicationStatus(4, false))
        // Preklad dokoncen -> Jiyu ukazuje COMPLETED i u hiatus/ongoing titulu.
        assertEquals("COMPLETED", comickPublicationStatus(1, true))
        assertEquals("COMPLETED", comickPublicationStatus(4, true))
    }

    @Test
    fun `title normalization strips diacritics case and punctuation`() {
        assertEquals("sololeveling", normalizeComickTitle("Solo Leveling"))
        assertEquals("rezero", normalizeComickTitle("Re:ZERO"))
        assertEquals("crimsoneclipse", normalizeComickTitle("Crimson Éclipse"))
        // Cisty whitespace/znaky -> prazdny klic (indexTitle ho preskoci).
        assertEquals("", normalizeComickTitle("  ---  "))
    }
}
