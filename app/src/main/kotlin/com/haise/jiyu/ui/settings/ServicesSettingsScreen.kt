package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient

@Composable
fun ServicesSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val malIsLoggedIn     by viewModel.malIsLoggedIn.collectAsStateWithLifecycle()
    val malUsername       by viewModel.malUsername.collectAsStateWithLifecycle()
    val kitsuIsLoggedIn   by viewModel.kitsuIsLoggedIn.collectAsStateWithLifecycle()
    val kitsuUsername     by viewModel.kitsuUsername.collectAsStateWithLifecycle()
    val kitsuLoginLoading by viewModel.kitsuLoginLoading.collectAsStateWithLifecycle()
    val kitsuLoginError   by viewModel.kitsuLoginError.collectAsStateWithLifecycle()
    val muIsLoggedIn       by viewModel.muIsLoggedIn.collectAsStateWithLifecycle()
    val muUsername          by viewModel.muUsername.collectAsStateWithLifecycle()
    val muLoginLoading      by viewModel.muLoginLoading.collectAsStateWithLifecycle()
    val muLoginError        by viewModel.muLoginError.collectAsStateWithLifecycle()

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_services_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // ── MAL OAuth ────────────────────────────────────────────────
                SettingsSection(title = "MyAnimeList") {
                    if (malIsLoggedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_services_logged_in), color = Cyan, fontWeight = FontWeight.Medium)
                                if (malUsername.isNotBlank()) Text(malUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.malLogout() }) {
                                Text(stringResource(R.string.settings_services_logout), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.settings_services_mal_login_desc),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        val context = LocalContext.current
                        OutlinedButton(
                            onClick = {
                                viewModel.startMalOAuth { uri ->
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.settings_services_mal_login_button))
                        }
                    }
                    Text(
                        stringResource(R.string.settings_services_mal_client_id_hint),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Kitsu ─────────────────────────────────────────────────────
                SettingsSection(title = "Kitsu") {
                    if (kitsuIsLoggedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_services_logged_in), color = Cyan, fontWeight = FontWeight.Medium)
                                if (kitsuUsername.isNotBlank()) Text(kitsuUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.kitsuLogout() }) {
                                Text(stringResource(R.string.settings_services_logout), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        var kitsuEmail by remember { mutableStateOf("") }
                        var kitsuPass  by remember { mutableStateOf("") }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(
                                stringResource(R.string.settings_services_kitsu_login_desc),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            TextField(
                                value = kitsuEmail,
                                onValueChange = { kitsuEmail = it; viewModel.clearKitsuLoginError() },
                                label = { Text(stringResource(R.string.settings_services_email_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                            TextField(
                                value = kitsuPass,
                                onValueChange = { kitsuPass = it; viewModel.clearKitsuLoginError() },
                                label = { Text(stringResource(R.string.settings_services_password_label)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            )
                            if (kitsuLoginError != null) {
                                Text(kitsuLoginError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.kitsuLogin(kitsuEmail, kitsuPass) },
                                enabled = !kitsuLoginLoading && kitsuEmail.isNotBlank() && kitsuPass.isNotBlank(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (kitsuLoginLoading) JiyuLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                                else Text(stringResource(R.string.settings_services_kitsu_login_button))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── MangaUpdates ──────────────────────────────────────────────
                SettingsSection(title = "MangaUpdates") {
                    if (muIsLoggedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_services_logged_in), color = Cyan, fontWeight = FontWeight.Medium)
                                if (muUsername.isNotBlank()) Text(muUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.muLogout() }) {
                                Text(stringResource(R.string.settings_services_logout), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        var muUser by remember { mutableStateOf("") }
                        var muPass by remember { mutableStateOf("") }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(
                                stringResource(R.string.settings_services_mu_login_desc),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            TextField(
                                value = muUser,
                                onValueChange = { muUser = it; viewModel.clearMuLoginError() },
                                label = { Text(stringResource(R.string.settings_services_username_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                            TextField(
                                value = muPass,
                                onValueChange = { muPass = it; viewModel.clearMuLoginError() },
                                label = { Text(stringResource(R.string.settings_services_password_label)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            )
                            if (muLoginError != null) {
                                Text(muLoginError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.muLogin(muUser, muPass) },
                                enabled = !muLoginLoading && muUser.isNotBlank() && muPass.isNotBlank(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (muLoginLoading) JiyuLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                                else Text(stringResource(R.string.settings_services_mu_login_button))
                            }
                        }
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
