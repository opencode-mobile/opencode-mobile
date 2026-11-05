package dev.opencode.mobile.ui.worktree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderTextAction
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeLayout
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.resolve

/** User actions of the New worktree form. */
data class WorktreeCreateFormCallbacks(
    val onNameChange: (String) -> Unit = {},
    val onShowBasePicker: () -> Unit = {},
    val onHideBasePicker: () -> Unit = {},
    val onSelectBase: (String?) -> Unit = {},
    val onSubmit: () -> Unit = {},
    /** Back to the sheet's list. */
    val onBack: () -> Unit = {},
)

/** "New worktree…" at the end of a Run in or Move list; disabled rows say why. */
@Composable
fun NewWorktreeRow(
    availability: NewWorktreeAvailability,
    testTag: String,
    onClick: () -> Unit,
) {
    OpenCodeListItem(
        title = stringResource(R.string.worktree_new_row),
        subtitle = availability.subtitle?.resolve(),
        enabled = availability.enabled,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.Add, null),
        onClick = if (availability.enabled) onClick else null,
        modifier = Modifier.testTag(testTag),
    )
}

/** Header "Back": from the base list to the form, from the form to the list. */
@Composable
fun worktreeCreateBackAction(
    state: WorktreeCreateFormState,
    callbacks: WorktreeCreateFormCallbacks,
): OpenCodeBottomSheetHeaderTextAction =
    OpenCodeBottomSheetHeaderTextAction(
        title = stringResource(R.string.top_bar_back),
        onClick = if (state.basePickerVisible) callbacks.onHideBasePicker else callbacks.onBack,
        testTag = WorktreeCreateTags.BACK,
    )

/** The form (name, branch, base) or, on v2, the base list. */
@Composable
fun ColumnScope.WorktreeCreateFormBody(
    state: WorktreeCreateFormState,
    callbacks: WorktreeCreateFormCallbacks,
) {
    if (state.basePickerVisible) {
        WorktreeBaseList(state, callbacks)
        return
    }
    OpenCodeSectionHeader(title = stringResource(R.string.worktree_name))
    OpenCodeTextInput(
        value = state.name,
        onValueChange = callbacks.onNameChange,
        hint = stringResource(R.string.worktree_name),
        enabled = !state.submitting,
        errorMessage = state.nameError?.resolve(),
        onSubmit = callbacks.onSubmit,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = OpenCodeLayout.ScreenGutter)
                .testTag(WorktreeCreateTags.NAME),
    )
    OpenCodeSectionHeader(title = stringResource(R.string.worktree_branch))
    OpenCodeListItem(
        title = state.branchValue.resolve(),
        subtitle = state.branchNote.resolve(),
        enabled = false,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
        modifier = Modifier.testTag(WorktreeCreateTags.BRANCH),
    )
    OpenCodeSectionHeader(title = stringResource(R.string.worktree_base))
    OpenCodeListItem(
        title = state.baseValue.resolve(),
        subtitle = state.baseNote.resolve(),
        enabled = state.baseSelectable && !state.submitting,
        accessory = if (state.baseSelectable) OpenCodeListItemAccessory.Disclosure else null,
        onClick = if (state.baseSelectable && !state.submitting) callbacks.onShowBasePicker else null,
        modifier = Modifier.testTag(WorktreeCreateTags.BASE),
    )
}

/** Pinned under the form: the create error, then the submit button. Nothing under the base list. */
@Composable
fun WorktreeCreateFormActions(
    state: WorktreeCreateFormState,
    callbacks: WorktreeCreateFormCallbacks,
) {
    if (state.basePickerVisible) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.errorMessage?.let { message ->
            OpenCodeInlineNotice(
                message = message.resolve(),
                tone = OpenCodeInlineNoticeTone.Error,
                variant = OpenCodeInlineNoticeVariant.Banner,
                modifier = Modifier.testTag(WorktreeCreateTags.ERROR),
            )
        }
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText(state.submitLabel.resolve()),
            onClick = callbacks.onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.testTag(WorktreeCreateTags.SUBMIT),
        )
    }
}

@Composable
private fun WorktreeBaseList(
    state: WorktreeCreateFormState,
    callbacks: WorktreeCreateFormCallbacks,
) {
    state.baseRows.forEach { row ->
        OpenCodeListItem(
            title = row.title.resolve(),
            selected = row.checked,
            startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
            onClick = { callbacks.onSelectBase(row.ref) },
            modifier = Modifier.testTag(row.testTag),
            trailing =
                if (row.checked) {
                    {
                        Icon(
                            imageVector = OpenCodeIcons.Check,
                            contentDescription = stringResource(R.string.common_selected),
                            tint = openCodeColor("icon-strong-base"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
        )
    }
    state.baseStatus?.let { status ->
        OpenCodeListItem(title = status.resolve(), enabled = false)
    }
}
