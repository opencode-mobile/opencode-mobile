package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShellOutputUiState
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShellsCopy
import kotlinx.coroutines.delay

/**
 * One shell's output at half height: the command, a live status line, the output following
 * the bottom while the reader stays there, then Copy and (where the server can) Stop shell.
 * The sheet stays open when the shell ends and shows how it ended.
 *
 * [onObservedChange] reports whether the sheet is up while the app is in the foreground; the
 * output is read only then.
 */
@Composable
internal fun SessionChatShellOutputSheet(
    sheet: SessionChatShellOutputUiState?,
    onCopy: () -> Unit,
    onStop: (String) -> Unit,
    onObservedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val observed = sheet != null && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val currentOnObservedChange by rememberUpdatedState(onObservedChange)
    LaunchedEffect(observed) { currentOnObservedChange(observed) }
    DisposableEffect(Unit) { onDispose { currentOnObservedChange(false) } }

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_SHELL_OUTPUT,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = sheet != null,
    )

    val clipboard = LocalClipboardManager.current
    OpenCodeBottomSheet(
        visible = sheet != null,
        title = SHELL_OUTPUT_TITLE,
        onDismissRequest = onDismiss,
        height = OpenCodeBottomSheetHeight.Half,
        scrollTargetKey = sheet?.shell?.id,
        followsBottom = true,
        pinnedTop = { if (sheet != null) SessionChatShellOutputHeader(sheet) },
        pinnedBottom = {
            if (sheet != null) {
                SessionChatShellOutputActions(
                    sheet = sheet,
                    onCopy = {
                        clipboard.setText(AnnotatedString(sheet.output?.text.orEmpty()))
                        onCopy()
                    },
                    onStop = { onStop(sheet.shell.id) },
                )
            }
        },
    ) {
        if (sheet != null) SessionChatShellOutputBody(sheet)
    }
}

@Composable
private fun SessionChatShellOutputHeader(sheet: SessionChatShellOutputUiState) {
    val running = sheet.shell.isRunning
    val nowMs by produceState(initialValue = System.currentTimeMillis(), running) {
        value = System.currentTimeMillis()
        while (running) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .openCodeBottomSheetContentInset()
                .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = sheet.shell.command,
            style = CodeTextStyle,
            color = openCodeColor("text-strong"),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = SessionChatShellsCopy.status(sheet.shell, nowMs),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (SessionChatShellsCopy.isProblem(sheet.shell.status)) {
                    MaterialTheme.colorScheme.error
                } else {
                    openCodeColor("text-weak")
                },
            maxLines = 1,
        )
    }
}

@Composable
private fun SessionChatShellOutputBody(sheet: SessionChatShellOutputUiState) {
    val output = sheet.output
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .openCodeBottomSheetContentInset()
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (output?.isTruncated == true) {
            Text(
                text = SessionChatShellsCopy.TRUNCATED,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
            )
        }
        val text = output?.text.orEmpty()
        if (text.isEmpty()) {
            Text(
                text = SessionChatShellsCopy.NO_OUTPUT,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-weak"),
            )
        } else {
            Text(
                text = text.trimEnd('\n'),
                style = CodeTextStyle,
                color = openCodeColor("text-base"),
            )
        }
    }
}

@Composable
private fun SessionChatShellOutputActions(
    sheet: SessionChatShellOutputUiState,
    onCopy: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sheet.stopError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText(SessionChatShellsCopy.COPY),
                contrast = OpenCodeButtonContrast.Low,
                enabled = !sheet.output?.text.isNullOrEmpty(),
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
            if (sheet.showsStop) {
                OpenCodeButton(
                    variant =
                        OpenCodeButtonVariant.FullWidthText(
                            if (sheet.isStopping) SessionChatShellsCopy.STOPPING else SessionChatShellsCopy.STOP,
                        ),
                    contrast = OpenCodeButtonContrast.Destructive,
                    enabled = !sheet.isStopping,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal const val SHELL_OUTPUT_TITLE = "Shell"
