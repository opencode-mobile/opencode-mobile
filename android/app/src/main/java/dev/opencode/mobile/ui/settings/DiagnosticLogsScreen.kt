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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
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
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EXPORT_FILE_NAME = "opencode-diagnostic-logs.jsonl"
private const val EXPORT_MIME_TYPE = "text/plain"

data class DiagnosticLogsUiState(
    val loading: Boolean = true,
    val entryCount: Int = 0,
    val droppedBySoftCap: Int = 0,
    val windowMinutes: Int = DiagnosticLogBuffer.WINDOW_MINUTES,
    val softCap: Int = DiagnosticLogBuffer.SOFT_CAP,
    val areaCounts: List<Pair<String, Int>> = emptyList(),
    val oldestTimestampLabel: String? = null,
    val newestTimestampLabel: String? = null,
    val shareError: String? = null,
    val saveMessage: String? = null,
) {
    val hasEntries: Boolean get() = entryCount > 0
}

@HiltViewModel
class DiagnosticLogsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val buffer: DiagnosticLogBuffer,
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
                        windowMinutes = snapshot.windowMinutes,
                        softCap = snapshot.softCap,
                        areaCounts = counts.toList(),
                        oldestTimestampLabel = oldest?.let(::formatTimestamp),
                        newestTimestampLabel = newest?.let(::formatTimestamp),
                        shareError = mutableState.value.shareError,
                        saveMessage = mutableState.value.saveMessage,
                    )
            }
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
                            shareError = "Couldn't prepare diagnostic log for sharing.",
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
                    val jsonl = withContext(Dispatchers.Default) { buffer.exportJsonl() }
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
                            saveMessage = "Saved diagnostic log.",
                        )
                    refresh()
                    onDone()
                }.onFailure {
                    mutableState.value =
                        mutableState.value.copy(
                            shareError = "Couldn't save diagnostic log.",
                            saveMessage = null,
                        )
                    onDone()
                }
            }
        }

        private suspend fun writeCacheExport(): Uri {
            val jsonl = withContext(Dispatchers.Default) { buffer.exportJsonl() }
            val file =
                withContext(Dispatchers.IO) {
                    File(context.cacheDir, EXPORT_FILE_NAME).also { it.writeText(jsonl) }
                }
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }

        private fun formatTimestamp(epochMilliseconds: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(Date(epochMilliseconds))
    }

@Composable
fun DiagnosticLogsScreen(
    state: DiagnosticLogsUiState,
    onNavigateBack: () -> Unit,
    onShareViaApps: () -> Unit,
    onSaveToUri: (Uri) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

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
            title = "Diagnostic Logs",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )

        when {
            state.loading ->
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(24.dp)
                            .weight(1f),
                    color = openCodeColor("interactive"),
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
                        title = "Entries",
                        subtitle =
                            if (state.hasEntries) {
                                "${state.entryCount} redacted lines in memory"
                            } else {
                                "No entries in the current window yet"
                            },
                        style = OpenCodeListItemStyle.Card,
                    )
                    OpenCodeListItem(
                        title = "Window",
                        subtitle = "Last ${state.windowMinutes} minutes",
                        style = OpenCodeListItemStyle.Card,
                    )
                    OpenCodeListItem(
                        title = "Soft cap",
                        subtitle =
                            if (state.droppedBySoftCap > 0) {
                                "${state.softCap} max · ${state.droppedBySoftCap} dropped oldest"
                            } else {
                                "${state.softCap} max · none dropped"
                            },
                        style = OpenCodeListItemStyle.Card,
                    )
                    OpenCodeListItem(
                        title = "Export",
                        subtitle = "JSONL · secrets and message bodies redacted",
                        style = OpenCodeListItemStyle.Card,
                    )
                    val oldest = state.oldestTimestampLabel
                    val newest = state.newestTimestampLabel
                    if (oldest != null && newest != null) {
                        OpenCodeListItem(
                            title = "Range",
                            subtitle = if (oldest == newest) oldest else "$oldest → $newest",
                            style = OpenCodeListItemStyle.Card,
                        )
                    }
                    if (state.areaCounts.isNotEmpty()) {
                        OpenCodeListItem(
                            title = "Areas",
                            subtitle = state.areaCounts.joinToString(" · ") { "${it.first} ${it.second}" },
                            style = OpenCodeListItemStyle.Card,
                        )
                    }
                    state.saveMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = openCodeColor("text-base"),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    state.shareError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
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
                variant = OpenCodeButtonVariant.FullWidthText("Clear"),
                contrast = OpenCodeButtonContrast.Low,
                size = OpenCodeButtonSize.Large,
                enabled = state.hasEntries,
                onClick = { showClearConfirmation = true },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("Share"),
                contrast = OpenCodeButtonContrast.High,
                size = OpenCodeButtonSize.Large,
                onClick = { showExportSheet = true },
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showExportSheet,
        title = "Export diagnostic logs",
        onDismissRequest = { showExportSheet = false },
    ) {
        OpenCodeBottomSheetEntry(
            title = "Share via apps",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Description, "Share"),
            onClick = {
                showExportSheet = false
                onShareViaApps()
            },
        )
        OpenCodeBottomSheetEntry(
            title = "Save to files",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder, "Save to files"),
            onClick = {
                showExportSheet = false
                saveLauncher.launch(EXPORT_FILE_NAME)
            },
        )
    }

    OpenCodeDialog(
        visible = showClearConfirmation,
        title = "Clear diagnostic logs?",
        subtitle = "This removes the in-memory diagnostic window.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Clear",
                onClick = {
                    showClearConfirmation = false
                    onClear()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = { showClearConfirmation = false },
            ),
        onDismissRequest = { showClearConfirmation = false },
    )
}

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
