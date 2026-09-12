package com.haise.jiyu.ui.resolver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nahlášený bug: obecná slova jako "scans" by fuzzy `contains` matchem (viz
 * matchesPreferredGroup) matchla libovolný zdroj se stejným obecným slovem ve jméně,
 * i když jde o úplně jinou překladatelskou skupinu.
 */
class SourceResolverGroupTokenTest {

    @Test
    fun `generic scanlation words are recognized as too generic to be a group signal`() {
        assertTrue(isGenericGroupToken("scans"))
        assertTrue(isGenericGroupToken("scan"))
        assertTrue(isGenericGroupToken("manga"))
        assertTrue(isGenericGroupToken("team"))
        assertTrue(isGenericGroupToken("group"))
    }

    @Test
    fun `a specific group name is not treated as generic`() {
        assertFalse(isGenericGroupToken("asura"))
        assertFalse(isGenericGroupToken("asurascans"))
        assertFalse(isGenericGroupToken("thunderscans"))
    }
}
