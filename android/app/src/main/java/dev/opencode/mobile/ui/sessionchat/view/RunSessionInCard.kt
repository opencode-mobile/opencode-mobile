package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodePickerCard
import dev.opencode.mobile.ui.components.OpenCodePickerCardRow
import dev.opencode.mobile.ui.components.OpenCodePickerCardTile
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.compactHomePath
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.NewSessionDraftSheet
import dev.opencode.mobile.ui.sessionchat.NewSessionDraftUiState
import dev.opencode.mobile.ui.sessionchat.NewSessionWorktreeOption
import dev.opencode.mobile.ui.sessionchat.NewSessionWorktreeValue
import dev.opencode.mobile.ui.text.resolve

/** Stable test tags of the New session draft (UI tests and real-app evidence). */
object NewSessionDraftTags {
    const val CARD = "runSessionInCard"
    const val PROJECT_ROW = "runSessionInProject"
    const val WORKTREE_ROW = "runSessionInWorktree"
    const val STARTS_FROM_ROW = "runSessionInStartsFrom"
    const val PROJECT_SEARCH = "newSessionProjectSearch"
    const val ADD_PROJECT = "newSessionAddProject"
    const val LOCAL_ROW = "newSessionPlaceLocal"
    const val NEW_WORKTREE_ROW = "newSessionPlaceNewWorktree"
    const val DISCARD = "newSessionDiscardDraft"

    fun projectRow(name: String): String = "newSessionProject:$name"

    fun worktreeRow(title: String): String = "newSessionWorktree:$title"
}

/** Actions of the draft's card and sheets. */
data class NewSessionDraftCallbacks(
    val onOpenProject: () -> Unit = {},
    val onOpenWorktree: () -> Unit = {},
    val onOpenBaseBranch: () -> Unit = {},
    val onDismissSheet: () -> Unit = {},
    val onProjectQueryChange: (String) -> Unit = {},
    val onSelectProject: (String) -> Unit = {},
    val onAddProject: () -> Unit = {},
    val onSelectPlace: (NewSessionPlace) -> Unit = {},
    val onSelectBaseBranch: (String?) -> Unit = {},
)

/**
 * The "Run session in" card of the New session draft: Project, Worktree and (for a new worktree
 * on v2) Starts from, each opening its own sheet, with the checkout path under it. [compact] while
 * the keyboard is up.
 */
@Composable
fun RunSessionInCard(
    state: NewSessionDraftUiState,
    callbacks: NewSessionDraftCallbacks,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.submitting
    val rows =
        buildList {
            add(
                OpenCodePickerCardRow(
                    label = stringResource(R.string.new_session_row_project),
                    value = state.projectName,
                    tile = OpenCodePickerCardTile.Letter(state.projectLetter, state.projectAccent),
                    enabled = enabled,
                    onClick = callbacks.onOpenProject,
                    testTag = NewSessionDraftTags.PROJECT_ROW,
                ),
            )
            state.worktree?.let { worktree ->
                add(
                    OpenCodePickerCardRow(
                        label = stringResource(R.string.new_session_row_worktree),
                        value =
                            when (worktree) {
                                NewSessionWorktreeValue.Local -> stringResource(R.string.new_session_place_local)
                                is NewSessionWorktreeValue.Worktree -> worktree.title
                                NewSessionWorktreeValue.NewWorktree -> stringResource(R.string.new_session_place_new_worktree)
                                NewSessionWorktreeValue.NotRepository -> stringResource(R.string.new_session_place_not_git)
                            },
                        tile =
                            OpenCodePickerCardTile.Icon(
                                imageVector =
                                    when (worktree) {
                                        // No computer mark in the icon set yet; Terminal stands for this machine's checkout.
                                        NewSessionWorktreeValue.Local -> OpenCodeIcons.Terminal
                                        is NewSessionWorktreeValue.Worktree -> OpenCodeIcons.CallSplit
                                        NewSessionWorktreeValue.NewWorktree -> OpenCodeIcons.Add
                                        NewSessionWorktreeValue.NotRepository -> OpenCodeIcons.Folder
                                    },
                                highlighted = state.worktreeHighlighted,
                            ),
                        monospaceValue = worktree is NewSessionWorktreeValue.Worktree,
                        enabled = enabled && state.worktreeEnabled,
                        onClick = callbacks.onOpenWorktree.takeIf { state.worktreeEnabled },
                        testTag = NewSessionDraftTags.WORKTREE_ROW,
                    ),
                )
            }
            state.startsFrom?.let { base ->
                add(
                    OpenCodePickerCardRow(
                        label = stringResource(R.string.new_session_row_starts_from),
                        value = base.resolve(),
                        tile = OpenCodePickerCardTile.Icon(OpenCodeIcons.CallSplit),
                        monospaceValue = true,
                        enabled = enabled,
                        onClick = callbacks.onOpenBaseBranch,
                        testTag = NewSessionDraftTags.STARTS_FROM_ROW,
                    ),
                )
            }
        }
    val path = state.path?.resolve()
    OpenCodePickerCard(
        rows = rows,
        caption = stringResource(R.string.new_session_run_in_caption),
        footnote = path?.let(::compactHomePath),
        footnoteMonospace = path?.startsWith("/") == true || path?.startsWith("~") == true,
        compact = compact,
        modifier = modifier.testTag(NewSessionDraftTags.CARD),
    )
}

/** The draft's three sheets: Project, Worktree and Starts from. */
@Composable
fun NewSessionDraftSheets(
    state: NewSessionDraftUiState?,
    callbacks: NewSessionDraftCallbacks,
) {
    // The last shown state, so a sheet keeps its rows while it slides out.
    var shown by remember { mutableStateOf(state) }
    if (state?.sheet != null) shown = state
    val sheet = state?.sheet
    NewSessionProjectSheet(
        visible = sheet == NewSessionDraftSheet.Project,
        state = shown,
        callbacks = callbacks,
    )
    NewSessionWorktreeSheet(
        visible = sheet == NewSessionDraftSheet.Worktree,
        state = shown,
        callbacks = callbacks,
    )
    NewSessionBaseBranchSheet(
        visible = sheet == NewSessionDraftSheet.BaseBranch,
        state = shown,
        callbacks = callbacks,
    )
}

/** Project sheet: search, Home's projects in Home order, and Add project…. */
@Composable
fun NewSessionProjectSheet(
    visible: Boolean,
    state: NewSessionDraftUiState?,
    callbacks: NewSessionDraftCallbacks,
) {
    OpenCodeBottomSheet(
        visible = visible,
        title = stringResource(R.string.new_session_project_sheet_title),
        onDismissRequest = callbacks.onDismissSheet,
        height = OpenCodeBottomSheetHeight.Half,
        keyboard = OpenCodeBottomSheetKeyboard.Keep,
        pinnedTop = {
            OpenCodeTextInput(
                value = state?.projectQuery.orEmpty(),
                onValueChange = callbacks.onProjectQueryChange,
                hint = stringResource(R.string.new_session_project_search),
                kind = OpenCodeTextInputKind.Search,
                modifier =
                    Modifier
                        .openCodeBottomSheetContentInset()
                        .padding(bottom = 8.dp)
                        .testTag(NewSessionDraftTags.PROJECT_SEARCH),
            )
        },
    ) {
        val rows = state?.projectRows.orEmpty()
        if (rows.isEmpty()) {
            OpenCodeListItem(title = stringResource(R.string.new_session_project_no_match), enabled = false)
        }
        rows.forEach { row ->
            OpenCodeListItem(
                title = row.name,
                subtitle = compactHomePath(row.path),
                selected = row.selected,
                startDecorator = StartDecorator.Letter(row.letter, row.accent),
                onClick = { callbacks.onSelectProject(row.key) },
                trailing = if (row.selected) ({ SelectedCheck() }) else null,
                modifier = Modifier.testTag(NewSessionDraftTags.projectRow(row.name)),
            )
        }
        OpenCodeListItem(
            title = stringResource(R.string.new_session_add_project),
            startDecorator = StartDecorator.Icon(OpenCodeIcons.Add, null),
            onClick = callbacks.onAddProject,
            modifier = Modifier.testTag(NewSessionDraftTags.ADD_PROJECT),
        )
    }
}

/** Worktree sheet: Local, New worktree, then the project's worktrees. */
@Composable
fun NewSessionWorktreeSheet(
    visible: Boolean,
    state: NewSessionDraftUiState?,
    callbacks: NewSessionDraftCallbacks,
) {
    OpenCodeBottomSheet(
        visible = visible,
        title = state?.worktreeSheetTitle?.resolve().orEmpty(),
        onDismissRequest = callbacks.onDismissSheet,
    ) {
        state?.placeOptions.orEmpty().forEach { option ->
            when (option) {
                is NewSessionWorktreeOption.Local ->
                    OpenCodeListItem(
                        title = stringResource(R.string.new_session_place_local),
                        subtitle = option.subtitle.resolve(),
                        selected = option.selected,
                        startDecorator = StartDecorator.Icon(OpenCodeIcons.Terminal, null),
                        onClick = { callbacks.onSelectPlace(NewSessionPlace.Local) },
                        trailing = if (option.selected) ({ SelectedCheck() }) else null,
                        modifier = Modifier.testTag(NewSessionDraftTags.LOCAL_ROW),
                    )
                is NewSessionWorktreeOption.NewWorktree ->
                    OpenCodeListItem(
                        title = stringResource(R.string.new_session_place_new_worktree),
                        subtitle = stringResource(R.string.new_session_new_worktree_subtitle),
                        selected = option.selected,
                        startDecorator = StartDecorator.Icon(OpenCodeIcons.Add, null),
                        onClick = { callbacks.onSelectPlace(NewSessionPlace.NewWorktree()) },
                        trailing = if (option.selected) ({ SelectedCheck() }) else null,
                        modifier = Modifier.testTag(NewSessionDraftTags.NEW_WORKTREE_ROW),
                    )
                is NewSessionWorktreeOption.Worktree -> Unit
            }
        }
        val worktrees = state?.worktreeOptions.orEmpty()
        if (worktrees.isNotEmpty()) {
            OpenCodeSectionHeader(title = stringResource(R.string.new_session_worktrees_section))
            worktrees.forEach { option ->
                OpenCodeListItem(
                    title = option.title,
                    subtitle = option.subtitle.resolve(),
                    selected = option.selected,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
                    onClick = { callbacks.onSelectPlace(NewSessionPlace.Worktree(option.directory, option.branch)) },
                    trailing = if (option.selected) ({ SelectedCheck() }) else null,
                    modifier = Modifier.testTag(NewSessionDraftTags.worktreeRow(option.title)),
                )
            }
        }
    }
}

/** Starts from sheet: the branch a new worktree starts from (v2). */
@Composable
fun NewSessionBaseBranchSheet(
    visible: Boolean,
    state: NewSessionDraftUiState?,
    callbacks: NewSessionDraftCallbacks,
) {
    OpenCodeBottomSheet(
        visible = visible,
        title = stringResource(R.string.new_session_base_sheet_title),
        onDismissRequest = callbacks.onDismissSheet,
    ) {
        state?.baseRows.orEmpty().forEach { row ->
            OpenCodeListItem(
                title = row.title.resolve(),
                selected = row.checked,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
                onClick = { callbacks.onSelectBaseBranch(row.ref) },
                trailing = if (row.checked) ({ SelectedCheck() }) else null,
                modifier = Modifier.testTag(row.testTag),
            )
        }
        state?.baseStatus?.let { status ->
            OpenCodeListItem(title = status.resolve(), enabled = false)
        }
    }
}

@Composable
private fun SelectedCheck() {
    Icon(
        imageVector = OpenCodeIcons.Check,
        contentDescription = stringResource(R.string.common_selected),
        tint = openCodeColor("icon-strong-base"),
        modifier = Modifier.size(20.dp),
    )
}
