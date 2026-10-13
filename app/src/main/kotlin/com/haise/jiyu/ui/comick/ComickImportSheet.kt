package com.haise.jiyu.ui.comick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haise.jiyu.R
import com.haise.jiyu.data.tracking.ComickImportState
import com.haise.jiyu.data.tracking.ComickImportPreview
import com.haise.jiyu.data.tracking.ComickImportReport
import com.haise.jiyu.data.tracking.comickStatusToLocal
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet

/**
 * Sheet pro import Comick knihovny - obsah se ridi [ComickImportState]:
 * Fetching -> Preview (pocty + potvrzeni) -> Applying -> Done(report) / Failed.
 */
@Composable
fun ComickImportSheet(
    state: ComickImportState,
    onConfirmImport: (ComickImportPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.comick_import_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Violet,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            when (state) {
                is ComickImportState.Fetching -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    JiyuLoadingIndicator(size = 22.dp, strokeWidth = 2.dp)
                    Text(stringResource(R.string.comick_import_fetching, state.fetched), color = TextSecondary)
                }

                is ComickImportState.Preview -> PreviewContent(
                    preview = state.preview,
                    onConfirm = { onConfirmImport(state.preview) },
                    onCancel = onDismiss,
                )

                is ComickImportState.Applying -> Column(Modifier.padding(vertical = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        JiyuLoadingIndicator(size = 22.dp, strokeWidth = 2.dp)
                        Text(stringResource(R.string.comick_import_applying, state.done, state.total), color = TextSecondary)
                    }
                }

                is ComickImportState.Done -> DoneContent(report = state.report, onClose = onDismiss)

                is ComickImportState.Failed -> Column(Modifier.padding(vertical = 16.dp)) {
                    Text(
                        stringResource(R.string.comick_import_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Technicky detail (HTTP kod / sitova chyba) - jinak nejde poznat,
                    // jestli selhal auth (401), rate-limit, nebo je proste offline.
                    val detail = state.message ?: state.code?.let { "HTTP $it" }
                    if (detail != null) {
                        Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                        Text(stringResource(R.string.comick_import_close), color = Violet)
                    }
                }

                ComickImportState.Idle -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    JiyuLoadingIndicator(size = 22.dp, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(
    preview: ComickImportPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        SummaryLine(stringResource(R.string.comick_import_new, preview.newEntries.size))
        SummaryLine(stringResource(R.string.comick_import_matched, preview.matched.size))
        if (preview.unmatched.isNotEmpty()) {
            SummaryLine(stringResource(R.string.comick_import_unmatched, preview.unmatched.size))
        }
        if (preview.animeSkipped > 0) {
            SummaryLine(stringResource(R.string.comick_import_anime_skipped, preview.animeSkipped))
        }
        // Rozpis importovanych titulu podle Comick statusu - jinak je z celkoveho
        // poctu nepoznat, kolik je "ctu" vs. "planuji cist". Labely se resolvuji
        // pred joinToString - stringResource je composable a lambda uz ne.
        if (preview.byStatus.isNotEmpty()) {
            val labels = listOf(
                comickStatusLabel(1), comickStatusLabel(2), comickStatusLabel(3),
                comickStatusLabel(4), comickStatusLabel(5),
            )
            val rozpis = preview.byStatus.entries.sortedBy { it.key }
                .joinToString(" · ") { (status, count) ->
                    "${labels.getOrElse(status - 1) { "?" }}: $count"
                }
            Text(rozpis, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.comick_import_cancel), color = TextSecondary)
            }
            OutlinedButton(
                onClick = onConfirm,
                enabled = preview.mangaCount > 0,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
            ) {
                Text(stringResource(R.string.comick_import_apply))
            }
        }
    }
}

@Composable
private fun DoneContent(report: ComickImportReport, onClose: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.comick_import_done),
            color = Cyan,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SummaryLine(stringResource(R.string.comick_import_new, report.imported))
        SummaryLine(stringResource(R.string.comick_import_matched, report.matchedExisting))
        SummaryLine(stringResource(R.string.comick_import_report_progress, report.progressApplied, report.keptLocalProgress))
        if (report.unmatched > 0) {
            SummaryLine(stringResource(R.string.comick_import_unmatched, report.unmatched))
        }
        if (report.animeSkipped > 0) {
            SummaryLine(stringResource(R.string.comick_import_anime_skipped, report.animeSkipped))
        }
        if (report.failed > 0) {
            SummaryLine(stringResource(R.string.comick_import_failed))
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
            Text(stringResource(R.string.comick_import_close), color = Violet)
        }
    }
}

@Composable
private fun SummaryLine(text: String) {
    Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
}

/** Comick status 1-5 -> lokalizovany label (pres lokalni status enum retezec). */
@Composable
private fun comickStatusLabel(comickStatus: Int): String = when (comickStatusToLocal(comickStatus)) {
    "READING"      -> stringResource(R.string.detail_reading_status_reading)
    "COMPLETED"    -> stringResource(R.string.detail_status_completed)
    "ON_HOLD"      -> stringResource(R.string.detail_reading_status_on_hold)
    "PLAN_TO_READ" -> stringResource(R.string.detail_reading_status_plan)
    "DROPPED"      -> stringResource(R.string.detail_reading_status_dropped)
    else           -> "?"
}
