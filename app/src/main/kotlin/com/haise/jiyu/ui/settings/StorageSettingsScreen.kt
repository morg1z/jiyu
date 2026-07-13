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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.Trash

@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val cacheCount by viewModel.cacheCount.collectAsState()

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_main_storage_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(R.string.settings_storage_translation_cache_title)) {
                    Text(
                        text = stringResource(R.string.settings_storage_translation_cache_count, cacheCount),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    OutlinedButton(
                        onClick = { viewModel.clearTranslationCache() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(TablerIcons.Trash, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.settings_storage_clear_cache_button))
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(R.string.settings_storage_image_cache_title)) {
                    val imgContext = LocalContext.current
                    var diskCacheSize by remember { mutableStateOf(0L) }
                    LaunchedEffect(Unit) {
                        diskCacheSize = coil.Coil.imageLoader(imgContext).diskCache?.size ?: 0L
                    }
                    val sizeStr = when {
                        diskCacheSize >= 1_048_576L -> "%.1f MB".format(diskCacheSize / 1_048_576.0)
                        diskCacheSize >= 1_024L -> "%.0f KB".format(diskCacheSize / 1_024.0)
                        else -> "$diskCacheSize B"
                    }
                    Text(
                        text = stringResource(R.string.settings_storage_disk_cache_size, sizeStr),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            coil.Coil.imageLoader(imgContext).diskCache?.clear()
                            diskCacheSize = 0L
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(TablerIcons.Trash, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.settings_storage_clear_image_cache_button))
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
