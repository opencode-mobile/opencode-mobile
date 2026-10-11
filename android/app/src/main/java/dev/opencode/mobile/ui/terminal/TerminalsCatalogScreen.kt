package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.currentOpenCodeUsesSplitLayout
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()
    val useSplitLayout = currentOpenCodeUsesSplitLayout()
    var selectedPtyId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWorktree by rememberSaveable { mutableStateOf("") }
    var selectedTitle by rememberSaveable { mutableStateOf("") }
    val openShell = { worktree: String, projectTitle: String, ptyId: String ->
        if (useSplitLayout) {
            selectedWorktree = worktree
            selectedTitle = projectTitle
            selectedPtyId = ptyId
        } else {
            onOpenTerminal(worktree, projectTitle, ptyId)
        }
    }
    LaunchedEffect(useSplitLayout) {
        val ptyId = selectedPtyId
        if (!useSplitLayout && ptyId != null) {
            selectedPtyId = null
            onOpenTerminal(selectedWorktree, selectedTitle, ptyId)
        }
    }
    LaunchedEffect(uiState.rows, selectedPtyId) {
        val ptyId = selectedPtyId ?: return@LaunchedEffect
        if (uiState.rows.none { it.id == ptyId }) selectedPtyId = null
    }
    if (useSplitLayout) {
        OpenCodeSplitPane(
            screen = OpenCodeSplitPaneScreen.Terminals,
            modifier = modifier,
            leading = {
                CatalogColumn(
                    uiState = uiState,
                    viewModel = viewModel,
                    onNavigateBack = onNavigateBack,
                    onOpenTerminal = openShell,
                    selectedPtyId = selectedPtyId,
                    constrainToReadableColumn = false,
                    coroutineScope = coroutineScope,
                )
            },
            trailing = { control ->
                val ptyId = selectedPtyId
                if (ptyId != null) {
                    TerminalScreen(
                        worktree = selectedWorktree,
                        projectTitle = selectedTitle,
                        ptyId = ptyId,
                        onNavigateBack = { selectedPtyId = null },
                        detailControl = control,
                        usesReadableColumn = false,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        OpenCodeTopBar(leading = openCodeDetailLeading(control) {})
                        OpenCodeEmptyState(
                            title = "Select a shell",
                            message = "Choose a shell from the list.",
                            icon = OpenCodeIcons.Terminal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
        return
    }
    CatalogColumn(
        uiState = uiState,
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onOpenTerminal = openShell,
        selectedPtyId = null,
        constrainToReadableColumn = true,
        coroutineScope = coroutineScope,
        modifier = modifier,
    )
}

@Composable
private fun CatalogColumn(
    uiState: TerminalsCatalogUiState,
    viewModel: TerminalsCatalogViewModel,
    onNavigateBack: () -> Unit,
    onOpenTerminal: (String, String, String) -> Unit,
    selectedPtyId: String?,
    constrainToReadableColumn: Boolean,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Terminals",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        val catalogBody: @Composable BoxScope.() -> Unit = {
            if (uiState.isLoading) {
                OpenCodeStatusStack(
                    title = "Loading terminals",
                    subtitle = "Checking server…",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.rows.isEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 88.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpenCodeEmptyState(
                                title = "No running shells",
                                message = "Start one for a project. It is not tied to a chat session.",
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            items(uiState.rows, key = { it.key }) { row ->
                                OpenCodeListItem(
                                    title = row.title,
                                    subtitle = row.projectTitle,
                                    startDecorator =
                                        StartDecorator.Letter(
                                            letterCharacterForProject(row.projectTitle),
                                            letterAccentForWorktree(row.worktree),
                                        ),
                                    selected = row.id == selectedPtyId,
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
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("New shell"),
                        onClick = viewModel::showProjectPicker,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
        }
        if (constrainToReadableColumn) {
            OpenCodeReadableColumn(modifier = Modifier.weight(1f), content = catalogBody)
        } else {
            Box(Modifier.weight(1f), content = catalogBody)
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
    OpenCodeBottomSheet(
        visible = uiState.isProjectPickerVisible,
        title = "New shell",
        onDismissRequest = viewModel::dismissProjectPicker,
    ) {
        uiState.createError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (uiState.projects.isEmpty()) {
            OpenCodeEmptyState(
                title = "No projects yet",
                message = "Add a project on home, then start a shell here.",
            )
        } else {
            uiState.projects.forEach { project ->
                OpenCodeListItem(
                    title = project.title,
                    startDecorator =
                        StartDecorator.Letter(
                            letterCharacterForProject(project.title),
                            letterAccentForWorktree(project.worktree),
                        ),
                    onClick = {
                        coroutineScope.launch {
                            val row = viewModel.createShell(project.worktree)
                            if (row != null) {
                                onOpenTerminal(row.worktree, row.projectTitle, row.id)
                            }
                        }
                    },
                )
            }
        }
    }
}
