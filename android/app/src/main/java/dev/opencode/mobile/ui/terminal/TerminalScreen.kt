package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.ThemeResolver
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleLeading

@Composable
fun TerminalScreen(
    worktree: String,
    projectTitle: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    ptyId: String? = null,
    viewModel: TerminalViewModel = hiltViewModel(key = "terminal-$worktree-${ptyId.orEmpty()}"),
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.appear(worktree, ptyId)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.disappear()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Terminal",
            subtitle = projectTitle,
            subtitleLeading =
                OpenCodeTopBarSubtitleLeading.Letter(
                    letterCharacterForProject(projectTitle),
                    letterAccentForWorktree(worktree),
                ),
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            when (uiState) {
                TerminalUiState.Connecting ->
                    OpenCodeStatusStack(
                        title = "Connecting",
                        subtitle = "Starting shell…",
                        modifier = Modifier.fillMaxSize(),
                    )
                is TerminalUiState.Live,
                is TerminalUiState.Reconnecting,
                ->
                    TerminalLivePane(viewModel = viewModel)
                TerminalUiState.ConnectFailed ->
                    TerminalErrorContent(
                        title = "Connect failed",
                        message = "Could not start a shell for this project.",
                        onRetry = viewModel::retry,
                    )
                TerminalUiState.Exited ->
                    TerminalErrorContent(
                        title = "Shell ended",
                        message = "Shell ended",
                        onRetry = viewModel::retry,
                    )
            }
        }
    }
}

@Composable
private fun TerminalLivePane(viewModel: TerminalViewModel) {
    val isDark = LocalOpenCodeDarkTheme.current
    val tokens = if (isDark) ThemeManager.resolvedDark else ThemeManager.resolvedLight
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        TerminalEmulator(
            output = viewModel.output,
            backgroundHex = tokens["background-base"],
            foregroundHex = tokens["text-strong"],
            onEvent = { event ->
                when (event) {
                    TerminalEmulatorEvent.Ready -> Unit
                    is TerminalEmulatorEvent.Data -> viewModel.sendInput(event.text)
                    is TerminalEmulatorEvent.Resize -> viewModel.resize(event.cols, event.rows)
                }
            },
            modifier =
                Modifier
                    .weight(1f)
                    .background(ThemeResolver.parseHexColor(tokens["background-base"])),
        )
        TerminalControlStrip(onSend = viewModel::sendInput)
    }
}

@Composable
private fun TerminalErrorContent(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OpenCodeEmptyState(
            title = title,
            message = message.ifBlank { "The terminal didn't open." },
            primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = onRetry),
        )
    }
}
