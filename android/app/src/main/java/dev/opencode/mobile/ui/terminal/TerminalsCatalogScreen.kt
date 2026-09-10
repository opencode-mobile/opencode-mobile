package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun TerminalsCatalogScreen(
    onNavigateBack: () -> Unit,
    onOpenTerminal: (worktree: String, projectTitle: String, ptyId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalsCatalogViewModel = hiltViewModel(),
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.appear()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Terminals",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
        if (uiState.isLoading) {
            OpenCodeStatusStack(
                title = "Loading terminals",
                subtitle = "Checking server…",
                modifier = Modifier.fillMaxSize(),
            )
        } else if (uiState.rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeEmptyState(
                    title = "No running shells",
                    message = "Open a session and use / terminal to start one for that project.",
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.rows, key = { "${it.worktree}:${it.id}" }) { row ->
                    OpenCodeListItem(
                        title = row.title,
                        subtitle = row.projectTitle,
                        startDecorator =
                            StartDecorator.Letter(
                                letterCharacterForProject(row.projectTitle),
                                letterAccentForWorktree(row.worktree),
                            ),
                        onClick = { onOpenTerminal(row.worktree, row.projectTitle, row.id) },
                        trailing = {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, "Close"),
                                contrast = OpenCodeButtonContrast.Low,
                                onClick = { viewModel.requestClose(row) },
                            )
                        },
                    )
                }
            }
        }
        }
    }
    val pending = uiState.pendingClose
    OpenCodeDialog(
        visible = pending != null,
        title = pending?.let { "Close “${it.title}”?" } ?: "Close this shell?",
        subtitle = "This ends the shell on the server.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Close",
                destructive = true,
                onClick = viewModel::confirmClose,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = viewModel::dismissClose,
            ),
        onDismissRequest = viewModel::dismissClose,
    )
}
