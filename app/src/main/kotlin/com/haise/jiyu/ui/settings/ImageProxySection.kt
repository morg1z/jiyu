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
 * Úsporný režim obrázků (proxy wsrv.nl) - VÝCHOZÍ VYPNUTO. Popis výslovně říká, že služba uvidí adresy
 * stahovaných obrázků, protože jde o třetí stranu (viz `ImageProxyInterceptor`).
 */
@Composable
fun ImageProxySection(viewModel: SettingsViewModel) {
    val enabled by viewModel.imageProxyEnabled.collectAsStateWithLifecycle()
    SettingsSection(title = stringResource(R.string.settings_image_proxy_section_title)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = { viewModel.setImageProxyEnabled(it) })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_image_proxy_title), color = TextPrimary, fontSize = 14.sp)
                Text(stringResource(R.string.settings_image_proxy_desc), color = TextSecondary, fontSize = 11.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
            )
        }
    }
}
