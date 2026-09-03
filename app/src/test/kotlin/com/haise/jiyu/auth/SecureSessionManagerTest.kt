package com.haise.jiyu.auth

import com.haise.jiyu.security.SecureCredentialStore
import io.github.jan.supabase.gotrue.user.UserSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SecureSessionManager] je jediné místo, kudy Supabase session (access/refresh token
 * k účtu Jiyu) prochází - viz komentář v souboru proč nejde o defaultní
 * SettingsSessionManager (nešifrované SharedPreferences). Testuje se serializace/
 * deserializace přes [SecureCredentialStore] a hlavně obranné chování při poškozených
 * datech, ne samotný `SecureCredentialStore` (ten má vlastní testy).
 */
class SecureSessionManagerTest {

    private lateinit var store: SecureCredentialStore
    private lateinit var manager: SecureSessionManager

    private val session = UserSession(
        accessToken = "access-123",
        refreshToken = "refresh-456",
        expiresIn = 3600L,
        tokenType = "bearer",
        user = null,
    )

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        manager = SecureSessionManager(store)
    }

    @Test
    fun `saveSession serializes the session and stores it under the fixed key`() = runBlocking {
        val slot = slot<String>()
        every { store.set("supabase_user_session", capture(slot)) } returns Unit

        manager.saveSession(session)

        assertTrue(slot.captured.contains("access-123"))
        assertTrue(slot.captured.contains("refresh-456"))
    }

    @Test
    fun `loadSession returns null when nothing was ever stored`() = runBlocking {
        every { store.get(any()) } returns null

        assertNull(manager.loadSession())
    }

    @Test
    fun `loadSession round-trips a session saved through the same manager`() = runBlocking {
        var stored: String? = null
        every { store.set(any(), any()) } answers { stored = secondArg() }
        every { store.get(any()) } answers { stored }

        manager.saveSession(session)
        val loaded = manager.loadSession()

        assertEquals(session.accessToken, loaded?.accessToken)
        assertEquals(session.refreshToken, loaded?.refreshToken)
        assertEquals(session.tokenType, loaded?.tokenType)
    }

    @Test
    fun `loadSession returns null instead of throwing on corrupted stored JSON`() = runBlocking {
        // Klicovy obranny test: poskozeny/nekompatibilni JSON (napr. po zmene formatu
        // session mezi verzemi appky) by bez try-catch v loadSession shodil appku hned
        // pri startu - Auth inicializace vola loadSession() jako jednu z prvnich veci.
        every { store.get(any()) } returns "{ not valid json at all"

        assertNull(manager.loadSession())
    }

    @Test
    fun `deleteSession removes the stored key`() = runBlocking {
        manager.deleteSession()

        verify { store.remove("supabase_user_session") }
    }
}
