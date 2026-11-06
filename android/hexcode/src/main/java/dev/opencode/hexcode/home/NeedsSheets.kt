package dev.opencode.hexcode.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonSize
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexCode
import dev.opencode.hexcode.designsystem.components.HexCodeLine
import dev.opencode.hexcode.designsystem.components.HexCodeLineKind
import dev.opencode.hexcode.designsystem.components.HexCodeText
import dev.opencode.hexcode.designsystem.components.HexOption
import dev.opencode.hexcode.designsystem.components.HexOptionMark
import dev.opencode.hexcode.designsystem.components.HexOptionTake
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetRequest
import dev.opencode.hexcode.session.AlwaysScope
import dev.opencode.hexcode.session.ApprovalDecision
import dev.opencode.hexcode.session.ApprovalLines
import dev.opencode.hexcode.session.ApprovalRequest
import dev.opencode.hexcode.session.CodeLine
import dev.opencode.hexcode.session.PendingItem
import dev.opencode.hexcode.session.QuestionAnswers

/**
 * The approval and question sheets (app-plan.md, Needs you and control): the open item from [HomeUiState.needs], or
 * nothing. The item shown stays on screen while the sheet slides away, so its content doesn't blank out.
 */
@Composable
internal fun BoxScope.NeedsSheets(
    state: HomeUiState,
    vm: HomeViewModel,
    /** Asks for the microphone when it isn't allowed yet; true when a take can start. */
    micReady: () -> Boolean,
) {
    val open = state.needsItem
    var shown by remember { mutableStateOf<PendingItem?>(null) }
    if (open != null) shown = open
    val item = shown
    val visible = open != null
    val pending = state.today.pending
    val position = item?.let { pending.indexOfFirst { p -> p.id == it.id } } ?: -1
    val approval = item?.approval
    HexSheet(
        visible = visible && approval != null,
        onDismiss = vm::hideNeeds,
        title = stringResource(R.string.needs_approval_title),
        request =
            HexSheetRequest(
                stringResource(R.string.needs_hide),
                vm::hideNeeds,
                count = if (pending.size > 1 && position >= 0) stringResource(R.string.needs_count, position + 1, pending.size) else null,
            ),
        amberEdge = true,
        footer = { if (item != null && approval != null) ApprovalButtons(state, item, vm) },
    ) {
        if (item != null && approval != null) ApprovalBody(state, item, approval, vm, micReady)
    }
    val answers = item?.let { state.answers[it.id] }
    val form = item?.form
    val question = answers?.takeIf { form?.answerable == true }?.question
    HexSheet(
        visible = visible && form != null,
        onDismiss = vm::hideNeeds,
        title =
            question?.title
                ?: form?.title?.takeIf { form.answerable.not() }
                ?: stringResource(R.string.needs_question_title),
        request =
            HexSheetRequest(
                stringResource(R.string.needs_hide),
                vm::hideNeeds,
                count =
                    answers?.takeIf { it.form.fields.size > 1 && form?.answerable == true }?.let {
                        stringResource(R.string.needs_count, it.page + 1, it.form.fields.size)
                    },
            ),
        footer = { if (item != null && form != null) QuestionButtons(state, answers?.takeIf { form.answerable }, vm) },
    ) {
        if (item != null && answers != null && form?.answerable == true) {
            QuestionPage(state, item, answers, vm, micReady)
        } else if (form != null) {
            Text(stringResource(R.string.needs_question_unsupported), style = HexTheme.type.body, color = HexTheme.colors.text2)
        }
    }
}

/** What it is, from which subagent, its message, the command or diff, the Always line, and Say why. */
@Composable
private fun ApprovalBody(
    state: HomeUiState,
    item: PendingItem,
    approval: ApprovalRequest,
    vm: HomeViewModel,
    micReady: () -> Boolean,
) {
    val colors = HexTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(actionTitle(approval.action), style = HexTheme.type.body, color = colors.text2)
            if (item.sessionId != state.today.rootId) {
                val child = state.today.children[item.sessionId]
                val name = child?.title?.ifBlank { null } ?: child?.agent?.ifBlank { null } ?: item.sessionId
                Text(stringResource(R.string.needs_approval_from_subagent, name), style = HexTheme.type.caption, color = colors.text3)
            }
        }
        approval.message?.let { Text(it, style = HexTheme.type.body, color = colors.text2) }
        val lines = ApprovalLines.of(approval)
        if (lines.isNotEmpty()) HexCode(lines.map { codeLine(it) })
        AlwaysScope.of(approval, state.today.projectId)?.let { scope ->
            val text =
                when (scope) {
                    is AlwaysScope.Patterns ->
                        summaryTextIn(
                            stringResource(R.string.needs_approval_always_line, SUMMARY_MARK),
                            scope.patterns.joinToString(", ") { "`$it`" },
                        )
                    is AlwaysScope.Any -> summaryText(stringResource(R.string.needs_approval_always_any, scope.action))
                }
            HexCodeText(text, style = HexTheme.type.caption, color = colors.text3)
        }
        val take = state.sheetTake?.takeIf { it.target.itemId == item.id }
        val target = SheetTarget.Note(item.id)
        HexOption(
            stringResource(R.string.needs_approval_say_why),
            HexOptionMark.Mic,
            selected = state.notes[item.id] != null,
            modifier = Modifier.padding(top = 4.dp),
            detail = state.notes[item.id],
            take = take?.let { if (it.transcribing) HexOptionTake.Transcribing else HexOptionTake.Listening },
            takeLabel = take?.let { stringResource(if (it.transcribing) R.string.needs_transcribing else R.string.needs_listening) },
            onHoldStart = { micReady() && vm.sheetTakeStart(target) },
            onHoldEnd = vm::sheetTakeEnd,
        )
    }
}

/** Deny on the left; Always (when offered) and Allow once on the right. Each shows its spinner while it runs. */
@Composable
private fun ApprovalButtons(
    state: HomeUiState,
    item: PendingItem,
    vm: HomeViewModel,
) {
    val busy = state.needs?.busy
    val taking = state.sheetTake?.target?.itemId == item.id
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HexButton(
            onClick = { vm.approve(ApprovalDecision.Reject) },
            text = stringResource(R.string.needs_approval_deny),
            style = HexButtonStyle.Danger,
            size = HexButtonSize.Regular,
            loading = busy == NeedsAction.Deny,
            // Deny sends what was said: it waits for the take's text.
            enabled = !taking,
        )
        Spacer(Modifier.weight(1f))
        if (item.approval?.let { AlwaysScope.of(it, state.today.projectId) } != null) {
            HexButton(
                onClick = { vm.approve(ApprovalDecision.Always) },
                text = stringResource(R.string.needs_approval_always),
                size = HexButtonSize.Regular,
                loading = busy == NeedsAction.Always,
            )
        }
        HexButton(
            onClick = { vm.approve(ApprovalDecision.Once) },
            text = stringResource(R.string.needs_approval_once),
            style = HexButtonStyle.Primary,
            size = HexButtonSize.Regular,
            loading = busy == NeedsAction.Once,
        )
    }
}

/** One field: the question, Select one or any, its options, and Say your own answer. */
@Composable
private fun QuestionPage(
    state: HomeUiState,
    item: PendingItem,
    answers: QuestionAnswers,
    vm: HomeViewModel,
    micReady: () -> Boolean,
) {
    val colors = HexTheme.colors
    val field = answers.question
    val answer = answers.answer
    val mark = if (field.multiple) HexOptionMark.Checkbox else HexOptionMark.Radio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            field.description?.let { Text(it, style = HexTheme.type.body, color = colors.text1) }
            Text(
                stringResource(if (field.multiple) R.string.needs_question_select_any else R.string.needs_question_select_one),
                style = HexTheme.type.caption,
                color = colors.text2,
            )
        }
        field.options.forEach { option ->
            HexOption(
                option.label,
                mark,
                selected = option.value in answer.chosen,
                detail = option.description,
                onClick = { vm.chooseOption(option.value) },
            )
        }
        if (field.custom) {
            val take = state.sheetTake?.takeIf { it.target == SheetTarget.Own(item.id, field.key) }
            val target = SheetTarget.Own(item.id, field.key)
            HexOption(
                stringResource(R.string.needs_question_own),
                mark,
                selected = answer.ownChosen,
                detail = answer.own,
                take = take?.let { if (it.transcribing) HexOptionTake.Transcribing else HexOptionTake.Listening },
                takeLabel = take?.let { stringResource(if (it.transcribing) R.string.needs_transcribing else R.string.needs_listening) },
                onClick = vm::toggleOwnAnswer,
                onHoldStart = { micReady() && vm.sheetTakeStart(target) },
                onHoldEnd = vm::sheetTakeEnd,
            )
        }
    }
}

/** Dismiss on the left; Back from the second page; Next, or Submit on the last page. */
@Composable
private fun QuestionButtons(
    state: HomeUiState,
    answers: QuestionAnswers?,
    vm: HomeViewModel,
) {
    val busy = state.needs?.busy
    val taking = state.sheetTake != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HexButton(
            onClick = vm::dismissQuestion,
            text = stringResource(R.string.needs_question_dismiss),
            size = HexButtonSize.Regular,
            loading = busy == NeedsAction.Dismiss,
        )
        Spacer(Modifier.weight(1f))
        if (answers == null) return@Row
        if (answers.page > 0) {
            HexButton(onClick = vm::previousQuestion, text = stringResource(R.string.common_back), size = HexButtonSize.Regular)
        }
        HexButton(
            onClick = if (answers.last) vm::submitAnswers else vm::nextQuestion,
            text = stringResource(if (answers.last) R.string.needs_question_submit else R.string.needs_question_next),
            style = HexButtonStyle.Primary,
            size = HexButtonSize.Regular,
            enabled = answers.answer.answered && !taking,
            loading = busy == NeedsAction.Submit,
        )
    }
}

@Composable
private fun actionTitle(action: String): String =
    when (action) {
        "shell" -> stringResource(R.string.needs_approval_shell)
        "edit" -> stringResource(R.string.needs_approval_edit)
        "webfetch" -> stringResource(R.string.needs_approval_webfetch)
        "subagent" -> stringResource(R.string.needs_approval_subagent)
        else -> action
    }

@Composable
private fun codeLine(line: CodeLine): HexCodeLine =
    when (line.kind) {
        CodeLine.Kind.Plain -> HexCodeLine(line.text)
        CodeLine.Kind.Added -> HexCodeLine(line.text, HexCodeLineKind.Added)
        CodeLine.Kind.Removed -> HexCodeLine(line.text, HexCodeLineKind.Removed)
        CodeLine.Kind.More -> HexCodeLine(stringResource(R.string.needs_approval_more_lines, line.text.toInt()), HexCodeLineKind.More)
    }
