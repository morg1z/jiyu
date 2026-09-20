package com.haise.jiyu.ui.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.R
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.auth.AuthRepository
import com.haise.jiyu.auth.JiyuUser
import com.haise.jiyu.sync.SyncOutcome
import com.haise.jiyu.sync.SyncRepository
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toFriendlyMessage
import com.haise.jiyu.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val appContext: android.content.Context,
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val aniListRepository: AniListRepository,
) : ViewModel() {

    /** Naplánuje periodickou synchronizaci na pozadí - viz [SyncScheduler] (sdíleno i s [com.haise.jiyu.JiyuApp]). */
    private fun scheduleBackgroundSync() = SyncScheduler.schedule(appContext)

    private fun cancelBackgroundSync() = SyncScheduler.cancel(appContext)

    val currentUser: StateFlow<JiyuUser?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** Lokální knihovna patří jinému účtu - synchronizace čeká na rozhodnutí uživatele (viz [SyncRepository.sync]). */
    private val _ownerConflict = MutableStateFlow(false)
    val ownerConflict: StateFlow<Boolean> = _ownerConflict.asStateFlow()

    fun dismissOwnerConflict() { _ownerConflict.value = false }

    fun resolveOwnerConflictKeepLocal() = runSync { syncRepository.syncClaimingLocalData() }

    fun resolveOwnerConflictUseCloud() = runSync { syncRepository.syncDiscardingLocalData() }

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
                    // Drive se planovalo jen ze signInWithEmail - Google prihlaseni tak nikdy
                    // nemelo periodickou synchronizaci na pozadi, jen jednorazovy syncNow() tady
                    // (nahlaseno v auditu).
                    scheduleBackgroundSync()
                    syncNow()
                } else {
                    _authState.value = AuthUiState.Error(appContext.getString(R.string.account_error_unsupported_credential))
                }
            } catch (e: GetCredentialCancellationException) {
                // Uzivatel jen zavrel vyber uctu (tap mimo / zpet) - to neni chyba,
                // jen se vrat do klidoveho stavu beze slova. Driv se to hlasilo
                // stejne jako skutecna chyba a vyskocila zbytecna "Chyba:" snackbar
                // za to, ze si to uzivatel rozmyslel.
                _authState.value = AuthUiState.Idle
            } catch (e: NoCredentialException) {
                // Nejcastejsi pricina: na telefonu neni pridany zadny Google ucet,
                // nebo OAuth klient v Google Cloud Console nema spravne
                // zaregistrovany balicek + SHA-1 appky. Syrova SDK hlaska
                // ("No credentials available") byla nesrozumitelna a neakcni
                // (uzivatelsky report se screenshotem).
                e.report("account:signInWithGoogle:noCredential")
                _authState.value = AuthUiState.Error(appContext.getString(R.string.account_error_no_google_account))
            } catch (e: GetCredentialException) {
                e.report("account:signInWithGoogle")
                _authState.value = AuthUiState.Error(appContext.getString(R.string.account_error_login_generic))
            } catch (e: Exception) {
                e.report("account:signInWithGoogle")
                _authState.value = AuthUiState.Error(e.toFriendlyMessage())
            }
        }
    }

    fun signOut() = viewModelScope.launch {
        try { authRepository.signOut() } catch (e: Exception) { e.report("account:signOut") }
        cancelBackgroundSync()
    }

    fun clearAuthState() { _authState.value = AuthUiState.Idle }

    fun syncNow() = runSync { syncRepository.sync() }

    private fun runSync(block: suspend () -> SyncOutcome) {
        viewModelScope.launch {
            _ownerConflict.value = false
            _syncState.value = SyncState.Syncing
            try {
                when (block()) {
                    SyncOutcome.SYNCED -> _syncState.value = SyncState.Done(appContext.getString(R.string.account_sync_done))
                    SyncOutcome.OWNER_CONFLICT -> {
                        _ownerConflict.value = true
                        _syncState.value = SyncState.Idle
                    }
                }
            } catch (e: Exception) {
                e.report("account:syncNow")
                _syncState.value = SyncState.Error(e.toFriendlyMessage())
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                authRepository.signInWithEmail(email, password)
                _authState.value = AuthUiState.Success
                scheduleBackgroundSync()
                syncNow()
            } catch (e: Exception) {
                e.report("account:signInWithEmail")
                _authState.value = AuthUiState.Error(e.toFriendlyMessage())
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
                e.report("account:signUpWithEmail")
                _authState.value = AuthUiState.Error(e.toFriendlyMessage())
            }
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            try {
                authRepository.resetPassword(email)
                _authState.value = AuthUiState.Done
            } catch (e: Exception) {
                // Chybová větev dřív vypisovala hlášku o ÚSPĚŠNÉM odeslání. Obrazovka stav
                // Error vykresluje přes "Chyba: %1$s", takže z toho vylezlo
                // "Chyba: Email pro reset odeslán" - věta, která si protiřečí - a skutečná
                // příčina se zahodila. Kdo resetoval heslo bez signálu, čekal na e-mail,
                // který nikdy neodešel. Anti-enumeration to nebylo: Supabase u
                // resetPasswordForEmail existenci účtu neprozrazuje ani při úspěchu, takže
                // sem doputují jen opravdové chyby (síť, rate limit, špatný formát).
                e.report("AccountViewModel.sendPasswordReset")
                _authState.value = AuthUiState.Error(e.toFriendlyMessage())
            }
        }
    }

    fun clearSyncState() { _syncState.value = SyncState.Idle }

    // ── AniList ───────────────────────────────────────────────────────────────

    val isAniListAuthenticated: StateFlow<Boolean> = aniListRepository.isAuthenticated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aniListHasClientId: Boolean get() = aniListRepository.hasClientId
    val aniListAuthUrl: String get() = aniListRepository.authUrl

    fun aniListSignOut() = viewModelScope.launch {
        try { aniListRepository.signOut() } catch (e: Exception) { e.report("account:anilist:signOut") }
    }

    fun handleAniListCallback(token: String, state: String?) = viewModelScope.launch {
        try {
            val success = aniListRepository.handleCallback(token, state)
            // Nejcastejsi prakticka pricina neuspechu: state neshoda (CSRF ochrana) - bez
            // hlasky appka jen tise "nic neudela" a uzivatel netusi, ze ma zkusit znovu.
            if (!success) _syncState.value = SyncState.Error(appContext.getString(R.string.account_anilist_login_failed))
        } catch (e: Exception) {
            e.report("account:anilist:handleCallback")
            _syncState.value = SyncState.Error(appContext.getString(R.string.account_anilist_login_failed))
        }
    }
}
