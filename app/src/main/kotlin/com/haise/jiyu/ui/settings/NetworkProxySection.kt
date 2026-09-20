package com.haise.jiyu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haise.jiyu.R
import com.haise.jiyu.source.interceptor.ProxyType
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Volitelná HTTP/SOCKS proxy pro sítě, kde weby zdrojů nejdou přímo (viz `NetworkProxyConfig`). Platí pro stahování
 * ze zdrojů i obrázků. Heslo se ukládá šifrovaně. Prázdné/vypnuté = appka nepoužívá žádnou vlastní proxy.
 */
@Composable
fun NetworkProxySection(viewModel: SettingsViewModel) {
    val stored by viewModel.proxy.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf(ProxyType.HTTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Předvyplnění z uložených hodnot (heslo se z úložiště nikdy nevrací do formuláře).
    LaunchedEffect(stored) {
        stored?.let {
            type = runCatching { ProxyType.valueOf(it.type) }.getOrDefault(ProxyType.HTTP)
            host = it.host; port = it.port.toString(); user = it.user.orEmpty()
        }
    }

    SettingsSection(title = stringResource(R.string.settings_proxy_section_title)) {
        Text(
            text = stringResource(R.string.settings_proxy_description),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyType.entries.forEach { t ->
                    OutlinedButton(onClick = { type = t }, modifier = Modifier.weight(1f)) {
                        Text(t.name, color = if (type == t) GlowViolet else TextSecondary)
                    }
                }
            }
            OutlinedTextField(
                value = host, onValueChange = { host = it; invalid = false }, singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_host)) }, isError = invalid,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it; invalid = false }, singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_port)) }, isError = invalid,
                supportingText = if (invalid) ({ Text(stringResource(R.string.settings_proxy_invalid)) }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_user)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                TextButton(onClick = {
                    scope.launch { invalid = !viewModel.saveProxy(type, host, port, user, password) }
                }) { Text(stringResource(R.string.common_save), color = GlowViolet) }
                if (stored != null) {
                    TextButton(onClick = {
                        viewModel.clearProxy()
                        host = ""; port = ""; user = ""; password = ""
                    }) { Text(stringResource(R.string.settings_proxy_disable), color = TextSecondary) }
                }
            }
        }
    }
}
