package com.haise.jiyu.ui.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.auth.AuthRepository
import com.haise.jiyu.auth.JiyuUser
import com.haise.jiyu.sync.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Error(val message: String) : AuthUiState
    data object Success : AuthUiState
    data object Done : AuthUiState
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Done(val message: String) : SyncState
    data class Error(val message: String) : SyncState
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val aniListRepository: AniListRepository,
) : ViewModel() {

    val currentUser: StateFlow<JiyuUser?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                val rawNonce = UUID.randomUUID().toString()
                val hashedNonce = MessageDigest.getInstance("SHA-256")
                    .digest(rawNonce.toByteArray())
                    .joinToString("") { "%02x".format(it) }

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(BuildConfig.GOOGLE_CLIENT_ID)
                    .setNonce(hashedNonce)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = CredentialManager.create(context).getCredential(context, request)
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                    authRepository.signInWithGoogle(googleCred.idToken, rawNonce)
                    _authState.value = AuthUiState.Success
                    syncNow()
                } else {
                    _authState.value = AuthUiState.Error("Nepodporovaný typ přihlášení")
                }
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error(e.message ?: "Chyba přihlášení")
            }
        }
    }

    fun signOut() = viewModelScope.launch {
        try { authRepository.signOut() } catch (_: Exception) {}
    }

    fun clearAuthState() { _authState.value = AuthUiState.Idle }

    fun syncNow() {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            try {
                syncRepository.pushToCloud()
                syncRepository.pullFromCloud()
                _syncState.value = SyncState.Done("Synchronizace dokončena")
            } catch (e: Exception) {
                _syncState.value = SyncState.Error(e.message ?: "Chyba synchronizace")
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                authRepository.signInWithEmail(email, password)
                _authState.value = AuthUiState.Success
                syncNow()
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error(e.message ?: "Chyba přihlášení")
            }
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                authRepository.signUpWithEmail(email, password)
                _authState.value = AuthUiState.Success
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error(e.message ?: "Chyba registrace")
            }
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            try {
                authRepository.resetPassword(email)
                _authState.value = AuthUiState.Done
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error("Email pro reset odeslán (pokud účet existuje)")
            }
        }
    }

    fun clearSyncState() { _syncState.value = SyncState.Idle }

    // ── AniList ───────────────────────────────────────────────────────────────

    val isAniListAuthenticated: StateFlow<Boolean> = aniListRepository.isAuthenticated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aniListAuthUrl: String get() = AniListRepository.AUTH_URL

    fun aniListSignOut() = viewModelScope.launch {
        try { aniListRepository.signOut() } catch (_: Exception) {}
    }

    fun handleAniListCallback(token: String) = viewModelScope.launch {
        try { aniListRepository.handleCallback(token) } catch (_: Exception) {}
    }
}
