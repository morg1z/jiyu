package com.haise.jiyu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-1: `jiyu://auth` deep link se pustí do Supabase jen se známkou recovery resetu
 * (`type=recovery` v query nebo #fragmentu, případně PKCE `?code=…`). Cizí intent
 * bez parametru by jinak mohl importovat session útočníkova účtu - viz komentář
 * u [isRecoveryAuthLink] v MainActivity.
 */
class MainActivityAuthLinkTest {

    @Test
    fun `implicit recovery fragment passes`() {
        // Typický reset link z e-mailu (implicit flow): tokeny v #fragmentu.
        assertTrue(isRecoveryAuthLink("jiyu://auth#access_token=abc123&type=recovery&expires_in=3600"))
    }

    @Test
    fun `query type=recovery passes`() {
        assertTrue(isRecoveryAuthLink("jiyu://auth?type=recovery&access_token=abc"))
    }

    @Test
    fun `pkce code passes`() {
        assertTrue(isRecoveryAuthLink("jiyu://auth?code=pkce-code-123"))
    }

    @Test
    fun `bare auth host without recovery markers is rejected`() {
        // Cizi intent s jiyu://auth a bez recovery parametru - driv by se rovnou
        // predal handleDeeplinks (a pripadne importoval cizi session).
        assertFalse(isRecoveryAuthLink("jiyu://auth"))
        assertFalse(isRecoveryAuthLink("jiyu://auth#access_token=stolen"))
        assertFalse(isRecoveryAuthLink("jiyu://auth?foo=bar"))
    }

    @Test
    fun `lookalike params do not pass`() {
        // "type" musi byt presne param s hodnotou "recovery", ne substring.
        assertFalse(isRecoveryAuthLink("jiyu://auth?type=signup"))
        assertFalse(isRecoveryAuthLink("jiyu://auth#access_token=x&type=signup"))
        assertFalse(isRecoveryAuthLink("jiyu://auth?recovery=1"))
        // code musi byt neprazdny param.
        assertFalse(isRecoveryAuthLink("jiyu://auth?code="))
        assertFalse(isRecoveryAuthLink("jiyu://auth?uncode=x"))
    }
}
