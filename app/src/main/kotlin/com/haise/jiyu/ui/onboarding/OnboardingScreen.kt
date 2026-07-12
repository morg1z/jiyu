package com.haise.jiyu.ui.onboarding

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step            by viewModel.step.collectAsState()
    val selectedLang    by viewModel.selectedLanguage.collectAsState()
    val readingDir      by viewModel.readingDir.collectAsState()
    val readingMode     by viewModel.readingMode.collectAsState()
    val downloadFolder  by viewModel.downloadFolderUri.collectAsState()

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadFolderUri(uri.toString())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        // Gradient blob pozadí
        Box(
            modifier = Modifier
                .size(400.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(listOf(GlowViolet.copy(alpha = 0.18f), Color.Transparent))
                )
        )

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Progress indikátor ────────────────────────────────────────────
            StepIndicator(current = step, total = viewModel.totalSteps)

            Spacer(Modifier.height(32.dp))

            // ── Obsah kroku (animovaný přechod) ──────────────────────────────
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it * dir } + fadeOut())
                },
                label = "onboarding_step",
            ) { s ->
                when (s) {
                    0 -> LanguageStep(
                        selectedLang = selectedLang,
                        onSelect = viewModel::setLanguage,
                    )
                    1 -> ReadingStep(
                        readingDir = readingDir,
                        readingMode = readingMode,
                        onDirSelect = viewModel::setReadingDir,
                        onModeSelect = viewModel::setReadingMode,
                    )
                    2 -> StorageStep(
                        folderUri = downloadFolder,
                        onPickFolder = { folderPicker.launch(null) },
                        onClearFolder = { viewModel.setDownloadFolderUri(null) },
                    )
                    3 -> DoneStep()
                }
            }

            Spacer(Modifier.height(40.dp))

            // ── Navigační tlačítka ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = viewModel::prevStep,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlowViolet.copy(alpha = 0.4f)),
                    ) { Text("←") }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Button(
                    onClick = {
                        if (step < viewModel.totalSteps - 1) viewModel.nextStep()
                        else viewModel.complete(onDone = onFinish)
                    },
                    modifier = Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet),
                ) {
                    Text(
                        text = if (step < viewModel.totalSteps - 1)
                            stringResource(R.string.onb_btn_next)
                        else
                            stringResource(R.string.onb_btn_start),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Krok 1: Jazyk ─────────────────────────────────────────────────────────────
@Composable
private fun LanguageStep(selectedLang: String, onSelect: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepIcon(TablerIcons.Language)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onb_welcome_title),
            color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onb_welcome_subtitle),
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        val languages = listOf(
            "cs" to "🇨🇿  Čeština",
            "en" to "🇬🇧  English",
            "fr" to "🇫🇷  Français",
            "es" to "🇪🇸  Español",
        )
        languages.forEach { (tag, label) ->
            ChoiceRow(
                label = label,
                selected = selectedLang == tag,
                onClick = { onSelect(tag) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Krok 2: Styl čtení ────────────────────────────────────────────────────────
@Composable
private fun ReadingStep(
    readingDir: String,
    readingMode: String,
    onDirSelect: (String) -> Unit,
    onModeSelect: (String) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepIcon(TablerIcons.Book)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onb_reading_title),
            color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onb_reading_subtitle),
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        Text("Směr čtení", color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp))
        listOf(
            ReadingDirection.LTR  to stringResource(R.string.onb_reading_opt_ltr),
            ReadingDirection.RTL  to stringResource(R.string.onb_reading_opt_rtl),
        ).forEach { (dir, label) ->
            ChoiceRow(label = label, selected = readingDir == dir, onClick = { onDirSelect(dir) })
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Režim čtení", color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp))
        ChoiceRow(
            label = stringResource(R.string.onb_reading_opt_webtoon),
            selected = readingMode == ReadingMode.WEBTOON,
            onClick = {
                onModeSelect(if (readingMode == ReadingMode.WEBTOON) ReadingMode.MANGA else ReadingMode.WEBTOON)
            },
        )
    }
}

// ── Krok 3: Úložiště ──────────────────────────────────────────────────────────
@Composable
private fun StorageStep(
    folderUri: String?,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepIcon(TablerIcons.Folder)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onb_storage_title),
            color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onb_storage_subtitle),
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        ChoiceRow(
            label = stringResource(R.string.onb_storage_default),
            selected = folderUri == null,
            onClick = onClearFolder,
        )
        Spacer(Modifier.height(8.dp))

        // Vlastní složka
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (folderUri != null) GlowViolet.copy(alpha = 0.15f)
                    else Color.White.copy(alpha = 0.05f)
                )
                .border(
                    1.dp,
                    if (folderUri != null) Violet else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onPickFolder)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TablerIcons.Folder, contentDescription = null,
                tint = if (folderUri != null) Violet else TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (folderUri != null) stringResource(R.string.onb_storage_selected)
                    else stringResource(R.string.onb_storage_pick),
                    color = if (folderUri != null) TextPrimary else TextSecondary,
                    fontWeight = if (folderUri != null) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 14.sp,
                )
                if (folderUri != null) {
                    Text(
                        text = Uri.parse(folderUri).lastPathSegment ?: folderUri,
                        color = TextSecondary, fontSize = 12.sp, maxLines = 1,
                    )
                }
            }
            if (folderUri != null) {
                Icon(TablerIcons.Check, contentDescription = null, tint = Violet, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Krok 4: Dokončení ─────────────────────────────────────────────────────────
@Composable
private fun DoneStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Brush.radialGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.6f))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TablerIcons.Book, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onb_done_title),
            color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onb_done_subtitle),
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
    }
}

// ── Sdílené komponenty ────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { (current + 1).toFloat() / total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = Violet,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${current + 1} / $total",
            color = TextSecondary, fontSize = 12.sp,
        )
    }
}

@Composable
private fun StepIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(GlowViolet.copy(alpha = 0.15f), CircleShape)
            .border(1.dp, GlowViolet.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Violet, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) GlowViolet.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (selected) Violet else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Violet, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TablerIcons.Check, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}
