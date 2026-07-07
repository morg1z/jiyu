package com.haise.jiyu.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.haise.jiyu.BuildConfig
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

sealed interface AuthState {
    data object Idle : AuthState
    data object Loading : AuthState
    data class Error(val message: String) : AuthState
    data object Success : AuthState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val currentUser: StateFlow<JiyuUser?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
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
                    _authState.value = AuthState.Success
                } else {
                    _authState.value = AuthState.Error("Nepodporovaný typ přihlášení")
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error(e.message ?: "Chyba přihlášení")
            }
        }
    }

    fun signOut() = viewModelScope.launch {
        try {
            authRepository.signOut()
        } catch (_: Exception) {}
    }

    fun clearState() { _authState.value = AuthState.Idle }
}
