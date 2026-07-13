package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.Book
import compose.icons.tablericons.Database
import compose.icons.tablericons.Download
import compose.icons.tablericons.History
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Palette
import compose.icons.tablericons.Puzzle
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Stack
import compose.icons.tablericons.Target

private data class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenReaderSettings: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenDownloadsSettings: () -> Unit,
    onOpenUpdateCheck: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val categories = listOf(
        SettingsCategory(stringResource(R.string.settings_main_appearance_title), stringResource(R.string.settings_main_appearance_subtitle), TablerIcons.Palette, onOpenAppearance),
        SettingsCategory(stringResource(R.string.settings_main_sources_title), stringResource(R.string.settings_main_sources_subtitle), TablerIcons.Stack, onOpenSources),
        SettingsCategory(stringResource(R.string.settings_main_reader_title), stringResource(R.string.settings_main_reader_subtitle), TablerIcons.Book, onOpenReaderSettings),
        SettingsCategory(stringResource(R.string.settings_main_storage_title), stringResource(R.string.settings_main_storage_subtitle), TablerIcons.Database, onOpenStorage),
        SettingsCategory(stringResource(R.string.settings_main_downloads_title), stringResource(R.string.settings_main_downloads_subtitle), TablerIcons.Download, onOpenDownloadsSettings),
        SettingsCategory(stringResource(R.string.settings_main_update_check_title), stringResource(R.string.settings_main_update_check_subtitle), TablerIcons.Refresh, onOpenUpdateCheck),
        SettingsCategory(stringResource(R.string.settings_main_services_title), stringResource(R.string.settings_main_services_subtitle), TablerIcons.Puzzle, onOpenServices),
        SettingsCategory(stringResource(R.string.settings_main_backup_title), stringResource(R.string.settings_main_backup_subtitle), TablerIcons.History, onOpenBackup),
        SettingsCategory(stringResource(R.string.settings_main_reading_title), stringResource(R.string.settings_main_reading_subtitle), TablerIcons.Target, onOpenReading),
        SettingsCategory(stringResource(R.string.settings_main_about_title), stringResource(R.string.settings_main_about_subtitle), TablerIcons.InfoCircle, onOpenAbout),
    )

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = category.onClick)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            category.icon,
                            contentDescription = null,
                            tint = GlowViolet,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(category.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                category.subtitle,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Column(modifier = Modifier.height(24.dp + navBottom)) {}
            }
        }
    }
}
