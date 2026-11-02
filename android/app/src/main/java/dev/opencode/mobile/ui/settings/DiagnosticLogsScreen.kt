package dev.opencode.mobile.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogAttachment
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.domain.feedback.FeedbackLogRedactor
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

private const val EXPORT_FILE_NAME = DiagnosticLogAttachment.FILENAME
private const val EXPORT_MIME_TYPE = DiagnosticLogAttachment.MIME

data class DiagnosticLogsUiState(
    val loading: Boolean = true,
    val entryCount: Int = 0,
    val droppedBySoftCap: Int = 0,
    val retainMinutes: Int = DiagnosticLogBuffer.RETAIN_MINUTES,
    val exportWindow: DiagnosticLogExportWindow = DiagnosticLogExportWindow.DEFAULT,
    val softCap: Int = DiagnosticLogBuffer.SOFT_CAP,
    val areaCounts: List<Pair<String, Int>> = emptyList(),
    val oldestTimestampLabel: String? = null,
    val newestTimestampLabel: String? = null,
    val shareError: UiText? = null,
    val saveMessage: UiText? = null,
) {
    val hasEntries: Boolean get() = entryCount > 0
}

@HiltViewModel
class DiagnosticLogsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val buffer: DiagnosticLogBuffer,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(DiagnosticLogsUiState())
        val state: StateFlow<DiagnosticLogsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                mutableState.value = mutableState.value.copy(loading = true)
                val snapshot = withContext(Dispatchers.Default) { buffer.snapshot() }
                val counts = linkedMapOf<String, Int>()
                for (area in DiagnosticLogArea.entries) {
                    val count = snapshot.entries.count { it.area == area.wireValue }
                    if (count > 0) counts[area.wireValue] = count
                }
                val oldest = snapshot.entries.firstOrNull()?.timestampEpochMilliseconds
                val newest = snapshot.entries.lastOrNull()?.timestampEpochMilliseconds
                mutableState.value =
                    DiagnosticLogsUiState(
                        loading = false,
                        entryCount = snapshot.entries.size,
                        droppedBySoftCap = snapshot.droppedBySoftCap,
                        retainMinutes = snapshot.retainMinutes,
                        exportWindow = mutableState.value.exportWindow,
                        softCap = snapshot.softCap,
                        areaCounts = counts.toList(),
                        oldestTimestampLabel = oldest?.let(::formatTimestamp),
                        newestTimestampLabel = newest?.let(::formatTimestamp),
                        shareError = mutableState.value.shareError,
                        saveMessage = mutableState.value.saveMessage,
                    )
            }
        }

        fun setExportWindow(window: DiagnosticLogExportWindow) {
            mutableState.value = mutableState.value.copy(exportWindow = window)
        }

        fun clear() {
            viewModelScope.launch {
                withContext(Dispatchers.Default) { buffer.clear() }
                mutableState.value = mutableState.value.copy(shareError = null, saveMessage = null)
                refresh()
            }
        }

        fun shareViaApps(onReady: (Intent) -> Unit) {
            viewModelScope.launch {
                runCatching {
                    val uri = writeCacheExport()
                    Intent(Intent.ACTION_SEND).apply {
                        type = EXPORT_MIME_TYPE
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "OpenCode diagnostic logs")
                        putExtra(Intent.EXTRA_TITLE, EXPORT_FILE_NAME)
                        clipData = ClipData.newUri(context.contentResolver, EXPORT_FILE_NAME, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }.onSuccess { intent ->
                    mutableState.value = mutableState.value.copy(shareError = null, saveMessage = null)
                    refresh()
                    onReady(Intent.createChooser(intent, "Share diagnostic logs"))
                }.onFailure {
                    mutableState.value =
                        mutableState.value.copy(
                            shareError = UiText.resource(R.string.diagnostics_error_share),
                            saveMessage = null,
                        )
                }
            }
        }

        fun saveToUri(
            uri: Uri,
            onDone: () -> Unit = {},
        ) {
            viewModelScope.launch {
                runCatching {
                    val jsonl = redactedExport()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(jsonl.toByteArray(Charsets.UTF_8))
                            output.flush()
                        } ?: error("Unable to open destination")
                    }
                }.onSuccess {
                    mutableState.value =
                        mutableState.value.copy(
                            shareError = null,
                            saveMessage = UiText.resource(R.string.diagnostics_saved),
                        )
                    refresh()
                    onDone()
                }.onFailure {
                    mutableState.value =
                        mutableState.value.copy(
                            shareError = UiText.resource(R.string.diagnostics_error_save),
                            saveMessage = null,
                        )
                    onDone()
                }
            }
        }

        /** Shared and saved logs get the same address and credential masking as a feedback upload. */
        private suspend fun redactedExport(): String =
            withContext(Dispatchers.Default) {
                FeedbackLogRedactor.redactJsonl(
                    buffer.exportJsonl(mutableState.value.exportWindow),
                    FeedbackLogRedactor.knownValues(activeConnectionManager.currentProfile()),
                )
            }

        private suspend fun writeCacheExport(): Uri {
            val jsonl = redactedExport()
            val file =
                withContext(Dispatchers.IO) {
                    File(File(context.cacheDir, "diagnostic").apply { mkdirs() }, EXPORT_FILE_NAME).also { it.writeText(jsonl) }
                }
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }

        private fun formatTimestamp(epochMilliseconds: Long): String =
            DateFormat
                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(Date(epochMilliseconds))
    }

@Composable
fun DiagnosticLogsScreen(
    state: DiagnosticLogsUiState,
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    onShareViaApps: () -> Unit,
    onSaveToUri: (Uri) -> Unit,
    onClear: () -> Unit,
    onExportWindowChange: (DiagnosticLogExportWindow) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showExportWindowPicker by remember { mutableStateOf(false) }

    val saveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
        ) { uri ->
            if (uri != null) {
                onSaveToUri(uri)
            }
        }

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = stringResource(R.string.diagnostics_title),
            leading = openCodeDetailLeading(detailControl, onNavigateBack),
            showDivider = true,
        )

        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading ->
                        OpenCodeStatusStack(
                            title = stringResource(R.string.diagnostics_loading),
                            style = OpenCodeStatusStackStyle.Spinner,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    else ->
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OpenCodeListItem(
                                title = stringResource(R.string.diagnostics_entries_title),
                                subtitle =
                                    if (state.hasEntries) {
                                        pluralStringResource(
                                            R.plurals.diagnostics_entries_count,
                                            state.entryCount,
                                            state.entryCount,
                                        )
                                    } else {
                                        stringResource(R.string.diagnostics_entries_empty)
                                    },
                                style = OpenCodeListItemStyle.Card,
                            )
                            OpenCodeListItem(
                                title = stringResource(R.string.diagnostics_kept_title),
                                subtitle =
                                    pluralStringResource(
                                        R.plurals.diagnostics_last_minutes,
                                        state.retainMinutes,
                                        state.retainMinutes,
                                    ),
                                style = OpenCodeListItemStyle.Card,
                            )
                            OpenCodeListItem(
                                title = stringResource(R.string.diagnostics_export_title),
                                subtitle = state.exportWindow.lastMinutesLabel(),
                                style = OpenCodeListItemStyle.Card,
                                onClick = { showExportWindowPicker = true },
                            )
                            OpenCodeListItem(
                                title = stringResource(R.string.diagnostics_soft_cap_title),
                                subtitle =
                                    if (state.droppedBySoftCap > 0) {
                                        stringResource(R.string.diagnostics_soft_cap_dropped, state.softCap, state.droppedBySoftCap)
                                    } else {
                                        pluralStringResource(R.plurals.diagnostics_soft_cap_none_dropped, state.softCap, state.softCap)
                                    },
                                style = OpenCodeListItemStyle.Card,
                            )
                            OpenCodeListItem(
                                title = stringResource(R.string.diagnostics_format_title),
                                subtitle = stringResource(R.string.diagnostics_format_value),
                                style = OpenCodeListItemStyle.Card,
                            )
                            val oldest = state.oldestTimestampLabel
                            val newest = state.newestTimestampLabel
                            if (oldest != null && newest != null) {
                                OpenCodeListItem(
                                    title = stringResource(R.string.diagnostics_range_title),
                                    subtitle =
                                        if (oldest == newest) {
                                            oldest
                                        } else {
                                            stringResource(R.string.diagnostics_range_value, oldest, newest)
                                        },
                                    style = OpenCodeListItemStyle.Card,
                                )
                            }
                            if (state.areaCounts.isNotEmpty()) {
                                OpenCodeListItem(
                                    title = stringResource(R.string.diagnostics_areas_title),
                                    // Area names are log wire values, shown as is.
                                    subtitle = state.areaCounts.joinToString(" · ") { "${it.first} ${it.second}" },
                                    style = OpenCodeListItemStyle.Card,
                                )
                            }
                            state.saveMessage?.let { message ->
                                Text(
                                    text = message.resolve(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = openCodeColor("text-base"),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            state.shareError?.let { error ->
                                OpenCodeInlineNotice(
                                    message = error.resolve(),
                                    tone = OpenCodeInlineNoticeTone.Error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.diagnostics_clear)),
                        contrast = OpenCodeButtonContrast.Low,
                        size = OpenCodeButtonSize.Large,
                        enabled = state.hasEntries,
                        onClick = { showClearConfirmation = true },
                    )
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.diagnostics_share)),
                        contrast = OpenCodeButtonContrast.High,
                        size = OpenCodeButtonSize.Large,
                        onClick = { showExportSheet = true },
                    )
                }
            }
        }
    }

    OpenCodeBottomSheet(
        visible = showExportWindowPicker,
        title = stringResource(R.string.diagnostics_export_window_title),
        onDismissRequest = { showExportWindowPicker = false },
    ) {
        DiagnosticLogExportWindow.entries.forEach { window ->
            OpenCodeBottomSheetEntry(
                title = window.lastMinutesLabel(),
                selected = window == state.exportWindow,
                onClick = {
                    onExportWindowChange(window)
                    showExportWindowPicker = false
                },
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showExportSheet,
        title = stringResource(R.string.diagnostics_export_sheet_title),
        onDismissRequest = { showExportSheet = false },
    ) {
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.diagnostics_share_via_apps),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Description),
            onClick = {
                showExportSheet = false
                onShareViaApps()
            },
        )
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.diagnostics_save_to_files),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder),
            onClick = {
                showExportSheet = false
                saveLauncher.launch(EXPORT_FILE_NAME)
            },
        )
    }

    OpenCodeDialog(
        visible = showClearConfirmation,
        title = stringResource(R.string.diagnostics_clear_dialog_title),
        subtitle = stringResource(R.string.diagnostics_clear_dialog_message),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.diagnostics_clear),
                onClick = {
                    showClearConfirmation = false
                    onClear()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                onClick = { showClearConfirmation = false },
            ),
        onDismissRequest = { showClearConfirmation = false },
    )
}

/** "Last 15 minutes". */
@Composable
private fun DiagnosticLogExportWindow.lastMinutesLabel(): String =
    pluralStringResource(R.plurals.diagnostics_last_minutes, minutes, minutes)

@Preview(showBackground = true)
@Composable
private fun DiagnosticLogsScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        DiagnosticLogsScreen(
            state =
                DiagnosticLogsUiState(
                    loading = false,
                    entryCount = 42,
                    droppedBySoftCap = 0,
                    areaCounts = listOf("sse" to 30, "permission" to 8, "session" to 4),
                    oldestTimestampLabel = "Aug 4, 2026, 10:40:00 AM",
                    newestTimestampLabel = "Aug 4, 2026, 10:44:12 AM",
                ),
            onNavigateBack = {},
            onShareViaApps = {},
            onSaveToUri = {},
            onClear = {},
        )
    }
}
