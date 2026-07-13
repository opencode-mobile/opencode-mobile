package dev.opencode.mobile.ui.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff

@Composable
fun ChangesScreen(
    sessionId: String,
    worktree: String,
    messageId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangesViewModel = hiltViewModel(key = "$sessionId-$worktree-$messageId"),
) {
    LaunchedEffect(sessionId, worktree, messageId) { viewModel.bind(sessionId, worktree, messageId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        OpenCodeTopBar(title = "Changes", leading = OpenCodeTopBarLeading.Back(onNavigateBack), showDivider = true)
        when (val current = state) {
            ChangesUiState.Loading -> Centered { CircularProgressIndicator() }
            ChangesUiState.NoTurn -> EmptyMessage("No completed turn", "Send a prompt and wait for it to complete, then try again.")
            ChangesUiState.NoChanges -> EmptyMessage("No changes", "The latest completed turn did not change any files.")
            is ChangesUiState.UnavailablePatch -> EmptyMessage(current.file.path, "A text preview is unavailable for this file.")
            is ChangesUiState.Content ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { OpenCodeUnifiedDiff(current.file) }
            is ChangesUiState.Error ->
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyMessage("Unable to load changes", current.message)
                        OpenCodeButton(
                            OpenCodeButtonVariant.CompactText("Retry"),
                            onClick = viewModel::retry,
                            contrast = OpenCodeButtonContrast.Low,
                        )
                    }
                }
        }
    }
}

@Composable private fun Centered(content: @Composable () -> Unit) =
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }

@Composable private fun EmptyMessage(
    title: String,
    detail: String,
) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
