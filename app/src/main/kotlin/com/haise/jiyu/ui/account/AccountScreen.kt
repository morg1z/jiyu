package com.haise.jiyu.ui.account

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
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
    var showAniListWebView by remember { mutableStateOf(false) }
    val errorPrefix = stringResource(R.string.account_error_prefix)
    val passwordResetSentText = stringResource(R.string.account_password_reset_sent)

    LaunchedEffect(authState) {
        when (val s = authState) {
            is AuthUiState.Error -> {
                snackbarHost.showSnackbar(errorPrefix.format(s.message))
                viewModel.clearAuthState()
            }
            is AuthUiState.Success -> viewModel.clearAuthState()
            is AuthUiState.Done -> {
                snackbarHost.showSnackbar(passwordResetSentText)
                viewModel.clearAuthState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Done -> { snackbarHost.showSnackbar(s.message); viewModel.clearSyncState() }
            is SyncState.Error -> { snackbarHost.showSnackbar(errorPrefix.format(s.message)); viewModel.clearSyncState() }
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
                    Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
                }
                Text(
                    text = stringResource(R.string.account_title),
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
                    onSignInGoogle = { viewModel.signInWithGoogle(context) },
                    onSignInEmail = { email, pwd -> viewModel.signInWithEmail(email, pwd) },
                    onSignUpEmail = { email, pwd -> viewModel.signUpWithEmail(email, pwd) },
                    onResetPassword = { email -> viewModel.sendPasswordReset(email) },
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
                hasClientId = viewModel.aniListHasClientId,
                onConnect = { showAniListWebView = true },
                onDisconnect = { viewModel.aniListSignOut() },
            )

            if (showAniListWebView) {
                AniListLoginDialog(
                    authUrl = viewModel.aniListAuthUrl,
                    onTokenReceived = { token ->
                        showAniListWebView = false
                        viewModel.handleAniListCallback(token)
                    },
                    onDismiss = { showAniListWebView = false },
                )
            }
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
    hasClientId: Boolean,
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
                    stringResource(R.string.account_anilist_tracking_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                if (isConnected) {
                    Spacer(Modifier.size(8.dp))
                    Icon(TablerIcons.CircleCheck, contentDescription = null, tint = GlowViolet, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                if (isConnected) stringResource(R.string.account_anilist_connected_desc)
                else stringResource(R.string.account_anilist_disconnected_desc),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            HorizontalDivider(color = GlowViolet.copy(alpha = 0.1f))
            if (isConnected) {
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(TablerIcons.Logout, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Text("  " + stringResource(R.string.account_anilist_disconnect), color = TextSecondary, fontSize = 13.sp)
                }
            } else if (!hasClientId) {
                Text(
                    stringResource(R.string.account_anilist_missing_client_id),
                    color = Color(0xFFF59E0B),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Violet.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_anilist_connect), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SignedOutContent(
    isLoading: Boolean,
    onSignInGoogle: () -> Unit,
    onSignInEmail: (String, String) -> Unit,
    onSignUpEmail: (String, String) -> Unit,
    onResetPassword: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = TablerIcons.User,
            contentDescription = null,
            tint = Violet.copy(alpha = 0.5f),
            modifier = Modifier.size(80.dp),
        )
        Text(
            stringResource(R.string.account_cloud_title),
            style = TextStyle(brush = titleGradient, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
        )
        Text(
            stringResource(R.string.account_sync_subtitle),
            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = Violet,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Google", fontSize = 13.sp) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.account_email), fontSize = 13.sp) })
        }

        Spacer(Modifier.height(4.dp))

        if (isLoading) {
            JiyuLoadingIndicator(size = 48.dp)
        } else when (selectedTab) {
            0 -> {
                Button(
                    onClick = onSignInGoogle,
                    colors = ButtonDefaults.buttonColors(containerColor = Violet),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.82f).height(52.dp),
                ) {
                    Text(stringResource(R.string.account_signin_google), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            1 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        label = { Text(stringResource(R.string.account_email)) },
                        leadingIcon = { Icon(TablerIcons.Mail, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Violet,
                            unfocusedIndicatorColor = TextSecondary.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = Violet,
                            unfocusedLabelColor = TextSecondary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.account_password)) },
                        leadingIcon = { Icon(TablerIcons.Lock, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (isSignUp) onSignUpEmail(email, password) else onSignInEmail(email, password)
                        }),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) TablerIcons.EyeOff else TablerIcons.Eye,
                                    contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Violet,
                            unfocusedIndicatorColor = TextSecondary.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = Violet,
                            unfocusedLabelColor = TextSecondary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (isSignUp) onSignUpEmail(email, password) else onSignInEmail(email, password)
                        },
                        enabled = email.isNotBlank() && password.length >= 6,
                        colors = ButtonDefaults.buttonColors(containerColor = Violet),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            if (isSignUp) stringResource(R.string.account_signup_button) else stringResource(R.string.account_signin_button),
                            color = Color.White, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = { isSignUp = !isSignUp }, contentPadding = PaddingValues(0.dp)) {
                            Text(
                                if (isSignUp) stringResource(R.string.account_have_account) else stringResource(R.string.account_new_account),
                                color = Cyan, fontSize = 12.sp,
                            )
                        }
                        if (!isSignUp) {
                            TextButton(
                                onClick = { if (email.isNotBlank()) onResetPassword(email) },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(stringResource(R.string.account_forgot_password), color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
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
                contentDescription = stringResource(R.string.account_avatar_desc_named, user.displayName ?: ""),
                modifier = Modifier.size(80.dp).clip(CircleShape),
            )
        } else {
            Icon(
                TablerIcons.User,
                contentDescription = stringResource(R.string.account_avatar_desc),
                tint = Violet,
                modifier = Modifier.size(80.dp),
            )
        }
        Text(user.displayName ?: stringResource(R.string.account_default_username), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                    Icon(TablerIcons.CloudUpload, contentDescription = null, tint = Cyan, modifier = Modifier.size(22.dp))
                    Text(
                        "  " + stringResource(R.string.account_cloud_sync_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
                Text(
                    stringResource(R.string.account_cloud_sync_desc),
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
                        JiyuLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                        Text("  " + stringResource(R.string.account_syncing), color = Cyan)
                    } else {
                        Icon(TablerIcons.CloudUpload, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Text("  " + stringResource(R.string.account_sync_now), color = Cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Icon(TablerIcons.Logout, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Text("  " + stringResource(R.string.account_sign_out), color = TextSecondary)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AniListLoginDialog(
    authUrl: String,
    onTokenReceived: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.scheme == "jiyu" && url.host == "anilist") {
                                val fragment = url.fragment ?: ""
                                val token = fragment.split('&')
                                    .firstOrNull { it.startsWith("access_token=") }
                                    ?.substringAfter('=')
                                if (token != null) onTokenReceived(token)
                                return true
                            }
                            return false
                        }
                    }
                    loadUrl(authUrl)
                }
            },
        )
    }
}
