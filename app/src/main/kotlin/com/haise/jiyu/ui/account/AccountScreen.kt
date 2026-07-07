package com.haise.jiyu.ui.account

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.auth.JiyuUser
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val currentUser           by viewModel.currentUser.collectAsState()
    val authState             by viewModel.authState.collectAsState()
    val syncState             by viewModel.syncState.collectAsState()
    val isAniListConnected    by viewModel.isAniListAuthenticated.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(authState) {
        when (val s = authState) {
            is AuthUiState.Error -> {
                snackbarHost.showSnackbar("Chyba přihlášení: ${s.message}")
                viewModel.clearAuthState()
            }
            is AuthUiState.Success -> viewModel.clearAuthState()
            else -> Unit
        }
    }

    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Done -> { snackbarHost.showSnackbar(s.message); viewModel.clearSyncState() }
            is SyncState.Error -> { snackbarHost.showSnackbar("Chyba: ${s.message}"); viewModel.clearSyncState() }
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(screenGradient)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
                }
                Text(
                    text = "Účet",
                    style = TextStyle(
                        brush = titleGradient,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                    ),
                )
            }

            if (currentUser == null) {
                SignedOutContent(
                    isLoading = authState is AuthUiState.Loading,
                    onSignIn = { viewModel.signInWithGoogle(context) },
                )
            } else {
                SignedInContent(
                    user = currentUser!!,
                    isSyncing = syncState is SyncState.Syncing,
                    onSync = { viewModel.syncNow() },
                    onSignOut = { viewModel.signOut() },
                )
            }

            Spacer(Modifier.height(16.dp))
            AniListSection(
                isConnected = isAniListConnected,
                onConnect = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.aniListAuthUrl))) },
                onDisconnect = { viewModel.aniListSignOut() },
            )
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun AniListSection(
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(glassGradient)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AniList Tracking",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                if (isConnected) {
                    Spacer(Modifier.size(8.dp))
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GlowViolet, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                if (isConnected) "Připojeno — progress kapitol se automaticky synchronizuje."
                else "Připoj svůj AniList účet a progress se bude automaticky aktualizovat při čtení.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            HorizontalDivider(color = GlowViolet.copy(alpha = 0.1f))
            if (isConnected) {
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Logout, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Text("  Odpojit AniList", color = TextSecondary, fontSize = 13.sp)
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Violet.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Připojit AniList", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SignedOutContent(isLoading: Boolean, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = Violet.copy(alpha = 0.5f),
            modifier = Modifier.size(96.dp),
        )
        Text(
            "Jiyu Cloud",
            style = TextStyle(brush = titleGradient, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold),
        )
        Text(
            "Synchronizuj svou knihovnu napříč zařízeními.\nPřihlášení je zdarma.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        if (isLoading) {
            CircularProgressIndicator(color = Violet, modifier = Modifier.size(48.dp))
        } else {
            Button(
                onClick = onSignIn,
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.75f).height(52.dp),
            ) {
                Text("Přihlásit se přes Google", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SignedInContent(
    user: JiyuUser,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (user.avatarUrl != null) {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = "Profilový obrázek ${user.displayName}",
                modifier = Modifier.size(80.dp).clip(CircleShape),
            )
        } else {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = "Profilový obrázek",
                tint = Violet,
                modifier = Modifier.size(80.dp),
            )
        }
        Text(user.displayName ?: "Uživatel", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (user.email != null) {
            Text(user.email, color = TextSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))

        // Cloud sync karta
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(glassGradient)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudSync, contentDescription = null, tint = Cyan, modifier = Modifier.size(22.dp))
                    Text(
                        "  Cloud Synchronizace",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
                Text(
                    "Nahraj stav knihovny a přečtených kapitol do cloudu.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Button(
                    onClick = onSync,
                    enabled = !isSyncing,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(color = Cyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Synchronizuji...", color = Cyan)
                    } else {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Text("  Synchronizovat nyní", color = Cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Logout, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Text("  Odhlásit se", color = TextSecondary)
        }
    }
}
