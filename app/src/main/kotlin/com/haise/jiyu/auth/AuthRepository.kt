package com.haise.jiyu.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class JiyuUser(
    val id: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
)

@Singleton
class AuthRepository @Inject constructor(private val supabase: SupabaseClient) {

    val currentUser: Flow<JiyuUser?> = supabase.auth.sessionStatus.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> {
                val user = status.session.user ?: return@map null
                JiyuUser(
                    id = user.id,
                    email = user.email,
                    displayName = user.userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull,
                    avatarUrl = user.userMetadata?.get("avatar_url")?.jsonPrimitive?.contentOrNull,
                )
            }
            else -> null
        }
    }

    fun isSignedIn(): Boolean = supabase.auth.currentSessionOrNull() != null

    fun currentUserId(): String? = supabase.auth.currentSessionOrNull()?.user?.id

    suspend fun signInWithGoogle(idToken: String, nonce: String) {
        supabase.auth.signInWith(IDToken) {
            this.idToken = idToken
            this.nonce = nonce
            provider = Google
        }
    }

    suspend fun signOut() = supabase.auth.signOut()

    suspend fun signInWithEmail(email: String, password: String) {
        supabase.auth.signInWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        supabase.auth.signUpWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun resetPassword(email: String) {
        supabase.auth.resetPasswordForEmail(email)
    }
}
