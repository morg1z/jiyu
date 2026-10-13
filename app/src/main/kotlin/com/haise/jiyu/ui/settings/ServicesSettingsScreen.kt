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
import androidx.compose.material3.AlertDialog
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
import com.haise.jiyu.settings.ComickSyncMode
import com.haise.jiyu.ui.comick.ComickImportSheet
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
    val comickIsConnected    by viewModel.comickIsConnected.collectAsStateWithLifecycle()
    val comickUsername       by viewModel.comickUsername.collectAsStateWithLifecycle()
    val comickNeedsReconnect by viewModel.comickNeedsReconnect.collectAsStateWithLifecycle()
    val comickHasWriteScope  by viewModel.comickHasWriteScope.collectAsStateWithLifecycle()
    val comickSyncMode       by viewModel.comickSyncMode.collectAsStateWithLifecycle()
    val comickLastSyncAt     by viewModel.comickLastSyncAt.collectAsStateWithLifecycle()
    val comickImportState    by viewModel.comickImportState.collectAsStateWithLifecycle()
    var showComickImport     by remember { mutableStateOf(false) }
    var showComickDisconnect by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

                Spacer(Modifier.height(12.dp))

                // ── Comick ────────────────────────────────────────────────────
                SettingsSection(title = "Comick") {
                    if (comickIsConnected) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_services_logged_in), color = Cyan, fontWeight = FontWeight.Medium)
                                if (comickUsername.isNotBlank()) Text(comickUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                if (comickLastSyncAt > 0) {
                                    Text(
                                        stringResource(
                                            R.string.settings_services_comick_last_sync,
                                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                                .format(java.util.Date(comickLastSyncAt)),
                                        ),
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            TextButton(onClick = { showComickDisconnect = true }) {
                                Text(stringResource(R.string.settings_services_logout), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (comickNeedsReconnect) {
                            Text(
                                stringResource(R.string.settings_services_comick_reconnect),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.settings_services_comick_sync_label),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        GlassRadioRow(
                            label = stringResource(R.string.settings_services_comick_mode_off),
                            selected = comickSyncMode == ComickSyncMode.OFF,
                            onClick = { viewModel.setComickSyncMode(ComickSyncMode.OFF) },
                        )
                        GlassRadioRow(
                            label = stringResource(R.string.settings_services_comick_mode_pull),
                            selected = comickSyncMode == ComickSyncMode.PULL,
                            onClick = { viewModel.setComickSyncMode(ComickSyncMode.PULL) },
                        )
                        GlassRadioRow(
                            label = stringResource(R.string.settings_services_comick_mode_twoway),
                            selected = comickSyncMode == ComickSyncMode.TWOWAY,
                            onClick = {
                                viewModel.setComickSyncMode(ComickSyncMode.TWOWAY)
                                if (!comickHasWriteScope) {
                                    val ctx = context
                                    viewModel.startComickOAuth(includeWrite = true) { uri ->
                                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                    }
                                }
                            },
                        )
                        OutlinedButton(
                            onClick = { viewModel.resetComickImport(); showComickImport = true; viewModel.startComickImport() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.settings_services_comick_import_button))
                        }
                    } else {
                        Text(
                            stringResource(R.string.settings_services_comick_desc),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        if (comickNeedsReconnect) {
                            Text(
                                stringResource(R.string.settings_services_comick_reconnect),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.startComickOAuth { uri ->
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                }
                            },
                            enabled = viewModel.comickHasClientId,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.settings_services_comick_connect))
                        }
                        if (!viewModel.comickHasClientId) {
                            Text(
                                "Comick Client ID: přidej do local.properties jako COMICK_CLIENT_ID=xxx",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                            )
                        }
                    }
                }

                if (showComickImport) {
                    ComickImportSheet(
                        state = comickImportState,
                        onConfirmImport = { preview -> viewModel.confirmComickImport(preview) },
                        onDismiss = { showComickImport = false; viewModel.resetComickImport() },
                    )
                }

                if (showComickDisconnect) {
                    AlertDialog(
                        onDismissRequest = { showComickDisconnect = false },
                        title = { Text("Comick") },
                        text = { Text(stringResource(R.string.settings_services_comick_disconnect_confirm)) },
                        confirmButton = {
                            TextButton(onClick = { showComickDisconnect = false; viewModel.comickDisconnect() }) {
                                Text(stringResource(R.string.settings_services_logout), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showComickDisconnect = false }) {
                                Text(stringResource(R.string.comick_import_cancel), color = TextSecondary)
                            }
                        },
                    )
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
