package com.haise.jiyu.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary

/**
 * DNS-over-HTTPS přepínač (výchozí ZAPNUTO) - viz `DnsOverHttpsConfig`/`ToggleableDns`
 * v AppModule: zapnuto jede resolving přes Cloudflare DoH (pomáhá, když ISP/router DNS
 * zdroje blokuje), vypnuto přes systémové DNS. Přepínač platí okamžitě, bez restartu.
 */
@Composable
fun DnsOverHttpsSection(viewModel: SettingsViewModel) {
    val enabled by viewModel.dnsOverHttpsEnabled.collectAsStateWithLifecycle()
    SettingsSection(title = stringResource(R.string.settings_doh_section_title)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = { viewModel.setDnsOverHttpsEnabled(it) })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_doh_title), color = TextPrimary, fontSize = 14.sp)
                Text(stringResource(R.string.settings_doh_desc), color = TextSecondary, fontSize = 11.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
            )
        }
    }
}
