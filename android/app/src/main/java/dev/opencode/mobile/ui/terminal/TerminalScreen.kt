package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
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
    viewModel: TerminalViewModel = hiltViewModel(key = "terminal-$worktree"),
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.appear(worktree)
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
        when (uiState) {
            TerminalUiState.Connecting ->
                OpenCodeStatusStack(
                    title = "Connecting",
                    subtitle = "Starting shell…",
                    modifier = Modifier.fillMaxSize(),
                )
            TerminalUiState.Live ->
                Box(modifier = Modifier.fillMaxSize())
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
        OpenCodeEmptyState(title = title, message = message)
        Spacer(modifier = Modifier.height(16.dp))
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Retry"),
            onClick = onRetry,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
