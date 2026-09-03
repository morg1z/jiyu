package com.haise.jiyu.auth

import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testy na [toJiyuUser] - mapovani Supabase [SessionStatus] na domenovy [JiyuUser].
 * Vytazeno z [AuthRepository.currentUser] jako cista funkce prave proto, aby slo
 * otestovat bez mockovani `SupabaseClient.auth` extension property (Kotlin
 * Multiplatform DSL treti strany, kterou MockK neumi spolehlive mockovat).
 */
class AuthRepositoryTest {

    private fun userInfo(
        id: String = "u1",
        email: String? = "user@example.com",
        metadata: Map<String, String> = emptyMap(),
    ) = UserInfo(
        aud = "authenticated",
        id = id,
        email = email,
        userMetadata = if (metadata.isEmpty()) null else buildJsonObject {
            metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        },
    )

    private fun session(user: UserInfo?) = UserSession(
        accessToken = "token",
        refreshToken = "refresh",
        expiresIn = 3600L,
        tokenType = "bearer",
        user = user,
    )

    @Test
    fun `authenticated status with full metadata maps to a complete JiyuUser`() {
        val info = userInfo(id = "u1", email = "user@example.com", metadata = mapOf(
            "full_name" to "Radim Test",
            "avatar_url" to "https://example.com/avatar.png",
        ))
        val status = SessionStatus.Authenticated(session(info))

        val result = status.toJiyuUser()

        assertEquals("u1", result?.id)
        assertEquals("user@example.com", result?.email)
        assertEquals("Radim Test", result?.displayName)
        assertEquals("https://example.com/avatar.png", result?.avatarUrl)
    }

    @Test
    fun `authenticated status with no metadata leaves displayName and avatarUrl null instead of crashing`() {
        val status = SessionStatus.Authenticated(session(userInfo(metadata = emptyMap())))

        val result = status.toJiyuUser()

        assertEquals("u1", result?.id)
        assertNull(result?.displayName)
        assertNull(result?.avatarUrl)
    }

    @Test
    fun `authenticated status with a null user in the session maps to null`() {
        // Obranna vetev v puvodnim kodu (status.session.user ?: return@map null) - Supabase
        // session teoreticky muze existovat bez plne nactenego uzivatele.
        val status = SessionStatus.Authenticated(session(user = null))

        assertNull(status.toJiyuUser())
    }

    @Test
    fun `not authenticated maps to null`() {
        assertNull(SessionStatus.NotAuthenticated.toJiyuUser())
    }

    @Test
    fun `loading from storage maps to null, not to a stale user`() {
        assertNull(SessionStatus.LoadingFromStorage.toJiyuUser())
    }

    @Test
    fun `network error maps to null`() {
        assertNull(SessionStatus.NetworkError.toJiyuUser())
    }
}
