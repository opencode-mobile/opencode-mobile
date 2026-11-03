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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.currentOpenCodeUsesSplitLayout
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.resolve
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
                            title = stringResource(R.string.terminal_select_title),
                            message = stringResource(R.string.terminal_select_message),
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
            title = stringResource(R.string.terminal_catalog_title),
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        val catalogBody: @Composable BoxScope.() -> Unit = {
            if (uiState.isLoading) {
                OpenCodeStatusStack(
                    title = stringResource(R.string.terminal_catalog_loading),
                    subtitle = stringResource(R.string.common_checking_server),
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
                                title = stringResource(R.string.terminal_catalog_empty_title),
                                message = stringResource(R.string.terminal_catalog_empty_message),
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
                                            variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, stringResource(R.string.terminal_close)),
                                            contrast = OpenCodeButtonContrast.Low,
                                            onClick = { viewModel.requestClose(row) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.terminal_new_shell)),
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
        title =
            pending?.let { stringResource(R.string.terminal_close_dialog_title_named, it.title) }
                ?: stringResource(R.string.terminal_close_dialog_title),
        subtitle = stringResource(R.string.terminal_close_dialog_message),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.terminal_close),
                destructive = true,
                onClick = viewModel::confirmClose,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                onClick = viewModel::dismissClose,
            ),
        onDismissRequest = viewModel::dismissClose,
    )
    OpenCodeBottomSheet(
        visible = uiState.isProjectPickerVisible,
        title = stringResource(R.string.terminal_new_shell),
        onDismissRequest = viewModel::dismissProjectPicker,
    ) {
        uiState.createError?.let { error ->
            Text(
                text = error.resolve(),
                color = openCodeColor("text-weak"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.openCodeBottomSheetContentInset().padding(bottom = 8.dp),
            )
        }
        if (uiState.projects.isEmpty()) {
            OpenCodeEmptyState(
                title = stringResource(R.string.terminal_projects_empty_title),
                message = stringResource(R.string.terminal_projects_empty_message),
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
