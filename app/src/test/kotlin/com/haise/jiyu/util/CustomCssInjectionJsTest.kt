package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SET-1: customCss z nastaveni se injektuje do stranek webovych zdroju
 * (SourceWebScreen). Helper musi vratit bezpecne quotovany JS - CSS je v JSON
 * stringu prirazovanem do textContent, takze `</style>` ani uvozovky nemuzou
 * "vylezt" z kontextu.
 */
class CustomCssInjectionJsTest {

    @Test
    fun `blank css produces no script`() {
        assertNull(customCssInjectionJs(""))
        assertNull(customCssInjectionJs("   \n  "))
    }

    @Test
    fun `css is embedded as a quoted textContent assignment`() {
        val js = customCssInjectionJs(".ad-banner { display: none !important; }")!!

        assertTrue(js.contains("document.createElement('style')"))
        assertTrue(js.contains("jiyu-custom-css"))
        // JSONObject.quote() obali do uvozovek - textContent dostane literal, ne HTML.
        assertTrue(js.contains("s.textContent=\".ad-banner { display: none !important; }\""))
    }

    @Test
    fun `quotes and closing style tag in css are escaped so they cannot break out`() {
        val js = customCssInjectionJs("a::after { content: \"</style><script>x()</script>\"; }")!!

        // JSONObject.quote escapuje \" AND "</" -> "<\/" (ochrana proti </script> v HTML
        // kontextu) - ve vyslednem JS je tedy \"<\/style>.
        assertTrue(js.contains("\\\"<\\/style>"))
        assertTrue(!js.contains("</style>"))
        // Cely skript je jedna IIFE - zadne top-level prikazy.
        assertTrue(js.startsWith("(function(){"))
        assertTrue(js.endsWith("})()"))
    }

    @Test
    fun `newlines in css are escaped into a single-line JS string`() {
        val js = customCssInjectionJs("a { color: red; }\n.b { color: blue; }")!!

        // JSONObject.quote dela z newline \n escape - cely skript zustane jednolinkovy.
        assertTrue(js.contains("\\n"))
        assertTrue(!js.contains("\n"))
    }
}
