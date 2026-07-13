package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val updateCheckLoading by viewModel.updateCheckLoading.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateCheckedNone by viewModel.updateCheckedAndNoneFound.collectAsState()
    val updateCtx = LocalContext.current

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = "O aplikaci", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = "O aplikaci") {
                    Text(
                        text = "Verze ${viewModel.appVersion}",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    if (updateInfo != null) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "Nová verze ${updateInfo!!.version} je k dispozici",
                                color = GlowViolet,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                fontSize = 13.sp,
                            )
                            if (updateInfo!!.notes.isNotBlank()) {
                                Text(
                                    updateInfo!!.notes,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        if (updateInfo!!.apkUrl != null) {
                            Button(
                                onClick = { viewModel.downloadUpdate() },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GlowViolet),
                            ) { Text("Stáhnout a nainstalovat") }
                        }
                        OutlinedButton(
                            onClick = {
                                updateCtx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(updateInfo!!.releaseUrl))
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                        ) { Text("Otevřít stránku vydání") }
                    } else if (updateCheckedNone) {
                        Text(
                            "Máš nejnovější verzi",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.checkForUpdate() },
                        enabled = !updateCheckLoading,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        if (updateCheckLoading) JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), size = 16.dp, strokeWidth = 2.dp)
                        Text("Zkontrolovat aktualizace")
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
