package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.source.catalog.CatalogSource
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@Composable
fun SourceCatalogScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val catalog = viewModel.getCatalog()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
            }
            Text(
                text = "Katalog zdrojů",
                style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Text(
            text = "Přednastavené Madara zdroje — jedním klepnutím přidat do svých zdrojů.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))

        if (catalog.isEmpty()) {
            Text(
                text = "Katalog je prázdný.",
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(catalog, key = { it.id }) { source ->
                    val installed = viewModel.isCatalogSourceInstalled(source)
                    CatalogSourceCard(
                        source = source,
                        installed = installed,
                        onInstall = { viewModel.installCatalogSource(source) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogSourceCard(
    source: CatalogSource,
    installed: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (source.nsfw) {
                    Text(
                        text = " 18+",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(source.description, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(
                text = source.baseUrl,
                color = Cyan.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }

        if (installed) {
            Icon(Icons.Filled.Check, contentDescription = "Nainstalováno", tint = Color(0xFF66BB6A))
        } else {
            OutlinedButton(
                onClick = onInstall,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.padding(end = 4.dp), tint = Violet)
                Text("Přidat", color = Violet, fontSize = 13.sp)
            }
        }
    }
}
