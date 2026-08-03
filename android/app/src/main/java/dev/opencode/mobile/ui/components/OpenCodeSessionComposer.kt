package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

sealed interface OpenCodeSessionComposerState {
    data class Prompt(
        val text: String,
        val mentionRanges: List<IntRange> = emptyList(),
        val selectionStart: Int? = null,
        val selectionEnd: Int? = null,
        val selectionRevision: Int = 0,
        val attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
        val selectedModelLabel: String,
        val selectedModeLabel: String,
        val selectedVariantLabel: String,
        val showVariant: Boolean,
        val sendEnabled: Boolean,
        val attachEnabled: Boolean = true,
        val showStop: Boolean,
        val stopEnabled: Boolean,
    ) : OpenCodeSessionComposerState

    data class Permission(
        val requestId: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String>,
        val queuePosition: Int = 1,
        val queueCount: Int = 1,
        val submitting: Boolean = false,
        val errorMessage: String? = null,
    ) : OpenCodeSessionComposerState

    data class QuestionOption(
        val label: String,
        val description: String,
    )

    data class Question(
        val requestId: String,
        val sessionId: String,
        val questionIndex: Int,
        val questionCount: Int,
        val header: String,
        val question: String,
        val options: List<QuestionOption>,
        val multiple: Boolean = false,
        val customAllowed: Boolean = true,
        val selectedLabels: List<String> = emptyList(),
        val customText: String = "",
        val customSelected: Boolean = false,
        val backEnabled: Boolean = false,
        val nextEnabled: Boolean = false,
        val submitEnabled: Boolean = false,
        val submitting: Boolean = false,
        val errorMessage: String? = null,
    ) : OpenCodeSessionComposerState
}

sealed interface OpenCodeSessionComposerAction {
    data class TextChanged(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int = selectionStart,
    ) : OpenCodeSessionComposerAction

    data object SelectModel : OpenCodeSessionComposerAction

    data object SelectMode : OpenCodeSessionComposerAction

    data object SelectVariant : OpenCodeSessionComposerAction

    data object Attach : OpenCodeSessionComposerAction

    data object OpenCommands : OpenCodeSessionComposerAction

    data class RemoveAttachment(
        val id: String,
    ) : OpenCodeSessionComposerAction

    data object Send : OpenCodeSessionComposerAction

    data object Stop : OpenCodeSessionComposerAction

    data class AllowPermissionOnce(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class DenyPermission(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class ToggleQuestionOption(
        val requestId: String,
        val sessionId: String,
        val label: String,
    ) : OpenCodeSessionComposerAction

    data class ToggleQuestionCustom(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class QuestionCustomTextChanged(
        val requestId: String,
        val sessionId: String,
        val text: String,
    ) : OpenCodeSessionComposerAction

    data class QuestionBack(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class QuestionNext(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class SubmitQuestion(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class DismissQuestion(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction
}

@Composable
fun OpenCodeSessionComposer(
    state: OpenCodeSessionComposerState,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outlineColor =
        when (state) {
            is OpenCodeSessionComposerState.Permission ->
                if (state.errorMessage == null) openCodeColor("warning") else openCodeColor("error")
            is OpenCodeSessionComposerState.Question,
            is OpenCodeSessionComposerState.Prompt,
            -> MaterialTheme.colorScheme.outlineVariant
        }
    val announceBlocking =
        state is OpenCodeSessionComposerState.Permission || state is OpenCodeSessionComposerState.Question
    val shellShape = RoundedCornerShape(OpenCodeFloatingChromeCornerRadius)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp),
    ) {
        OpenCodeFloatingChrome(
            modifier = Modifier.fillMaxWidth(),
            shape = shellShape,
            border = BorderStroke(1.dp, outlineColor),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .then(
                            if (announceBlocking) {
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            } else {
                                Modifier
                            },
                        ),
            ) {
                AnimatedContent(
                    targetState = state,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (
                            fadeIn(
                                animationSpec = tween(durationMillis = 120, delayMillis = 80),
                            ) togetherWith
                                fadeOut(
                                    animationSpec = tween(durationMillis = 80),
                                )
                        ).using(
                            SizeTransform(clip = true) { _, _ ->
                                tween(durationMillis = 240)
                            },
                        )
                    },
                    contentKey = { it::class },
                    label = "composer-content",
                ) { state ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (state) {
                            is OpenCodeSessionComposerState.Prompt ->
                                PromptEntryContent(
                                    state = state,
                                    onAction = onAction,
                                )

                            is OpenCodeSessionComposerState.Permission ->
                                PermissionApprovalContent(
                                    state = state,
                                    onAction = onAction,
                                )

                            is OpenCodeSessionComposerState.Question ->
                                QuestionRequestContent(
                                    state = state,
                                    onAction = onAction,
                                )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptEntryContent(
    state: OpenCodeSessionComposerState.Prompt,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    if (state.attachments.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.attachments.forEach { attachment ->
                OpenCodeAttachmentChip(
                    attachment = attachment,
                    onRemove = { onAction(OpenCodeSessionComposerAction.RemoveAttachment(it)) },
                )
            }
        }
    }

    val mentionColor = openCodeColor("syntax-type")
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val mentionTransformation =
        remember(state.mentionRanges, mentionColor) {
            MentionVisualTransformation(mentionRanges = state.mentionRanges, mentionColor = mentionColor)
        }

    // Own TextFieldValue so typing keeps the caret, while programmatic text
    // updates (slash/mention insert, atomic delete, restore, clear) sync selection.
    // Mention chrome is applied via VisualTransformation so it always tracks
    // state.mentionRanges (AnnotatedString on TextFieldValue is dropped by IME updates).
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = state.text, selection = TextRange(state.text.length)))
    }
    LaunchedEffect(state.text, state.selectionRevision) {
        val selectionStart = (state.selectionStart ?: state.text.length).coerceIn(0, state.text.length)
        val selectionEnd = (state.selectionEnd ?: selectionStart).coerceIn(0, state.text.length)
        if (textFieldValue.text != state.text ||
            textFieldValue.selection != TextRange(selectionStart, selectionEnd)
        ) {
            textFieldValue =
                TextFieldValue(
                    text = state.text,
                    selection = TextRange(selectionStart, selectionEnd),
                )
        }
    }

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            onAction(
                OpenCodeSessionComposerAction.TextChanged(
                    text = newValue.text,
                    selectionStart = newValue.selection.start,
                    selectionEnd = newValue.selection.end,
                ),
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = bodyColor),
        cursorBrush = SolidColor(bodyColor),
        visualTransformation = mentionTransformation,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        decorationBox = { innerTextField ->
            Box {
                if (state.text.isBlank()) {
                    Text(
                        text = "Ask anything...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )

    PickerChipsRow(state = state, onAction = onAction)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Add attachment"),
                enabled = state.attachEnabled,
                contrast = OpenCodeButtonContrast.Low,
                onClick = { onAction(OpenCodeSessionComposerAction.Attach) },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircleText("/", "Open commands"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = { onAction(OpenCodeSessionComposerAction.OpenCommands) },
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.showStop) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Stop, "Abort active work"),
                    enabled = state.stopEnabled,
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = { onAction(OpenCodeSessionComposerAction.Stop) },
                )
            }
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send prompt"),
                enabled = state.sendEnabled,
                contrast = OpenCodeButtonContrast.Low,
                onClick = { onAction(OpenCodeSessionComposerAction.Send) },
            )
        }
    }
}

private class MentionVisualTransformation(
    private val mentionRanges: List<IntRange>,
    private val mentionColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (mentionRanges.isEmpty() || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val styled =
            buildAnnotatedString {
                append(text)
                mentionRanges.forEach { range ->
                    val start = range.first.coerceIn(0, text.length)
                    val end = (range.last + 1).coerceIn(0, text.length)
                    if (start < end) {
                        addStyle(SpanStyle(color = mentionColor), start, end)
                    }
                }
            }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * Horizontal model / mode / variant chips. Enables horizontal scroll only when
 * the measured chip row is wider than the available composer width.
 */
@Composable
private fun PickerChipsRow(
    state: OpenCodeSessionComposerState.Prompt,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    val scrollState = rememberScrollState()
    var contentWidthPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val canScroll = contentWidthPx > maxWidthPx

        LaunchedEffect(canScroll) {
            if (!canScroll && scrollState.value != 0) {
                scrollState.scrollTo(0)
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(scrollState, enabled = canScroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .wrapContentWidth()
                        .onSizeChanged { contentWidthPx = it.width },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeChoiceBadge(
                    label = state.selectedModelLabel,
                    selected = false,
                    onClick = { onAction(OpenCodeSessionComposerAction.SelectModel) },
                    variant = OpenCodeChoiceBadgeVariant.Glass,
                )
                OpenCodeChoiceBadge(
                    label = state.selectedModeLabel,
                    selected = false,
                    onClick = { onAction(OpenCodeSessionComposerAction.SelectMode) },
                    variant = OpenCodeChoiceBadgeVariant.Glass,
                )
                if (state.showVariant) {
                    OpenCodeChoiceBadge(
                        label = state.selectedVariantLabel,
                        selected = false,
                        onClick = { onAction(OpenCodeSessionComposerAction.SelectVariant) },
                        variant = OpenCodeChoiceBadgeVariant.Glass,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionApprovalContent(
    state: OpenCodeSessionComposerState.Permission,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission requested", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.queueCount > 1) {
                Text("${state.queuePosition} of ${state.queueCount}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(state.permission.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.patterns.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        state.errorMessage?.let {
            Text(it, color = openCodeColor("error"), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Deny"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.DenyPermission(state.requestId, state.sessionId))
            },
            enabled = !state.submitting,
            contrast = OpenCodeButtonContrast.Low,
        )
        Spacer(Modifier.weight(1f))
        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp), strokeWidth = 2.dp)
        }
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Allow once"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.AllowPermissionOnce(state.requestId, state.sessionId))
            },
            enabled = !state.submitting,
        )
    }
}

@Composable
private fun QuestionRequestContent(
    state: OpenCodeSessionComposerState.Question,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    val showBack = state.questionIndex > 0
    val showNext = state.questionIndex < state.questionCount - 1
    val selectionHint =
        when {
            state.multiple -> "Select all that apply"
            else -> "Select one"
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = state.header.ifBlank { "Question" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.questionCount > 1) {
                Text(
                    "${state.questionIndex + 1} of ${state.questionCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(state.question, style = MaterialTheme.typography.bodyMedium)
        Text(
            selectionHint,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.options.forEach { option ->
                val optionSelected = option.label in state.selectedLabels
                OpenCodeListItem(
                    title = option.label,
                    subtitle = option.description.takeIf { it.isNotBlank() },
                    selected = optionSelected,
                    enabled = !state.submitting,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = questionSelectionDecorator(selected = optionSelected),
                    onClick = {
                        onAction(
                            OpenCodeSessionComposerAction.ToggleQuestionOption(
                                requestId = state.requestId,
                                sessionId = state.sessionId,
                                label = option.label,
                            ),
                        )
                    },
                )
            }
            if (state.customAllowed) {
                OpenCodeListItem(
                    title = "Type your own answer",
                    subtitle =
                        if (!state.customSelected) {
                            state.customText.takeIf { it.isNotBlank() }
                        } else {
                            null
                        },
                    selected = state.customSelected,
                    enabled = !state.submitting,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = questionSelectionDecorator(selected = state.customSelected),
                    onClick = {
                        onAction(
                            OpenCodeSessionComposerAction.ToggleQuestionCustom(
                                requestId = state.requestId,
                                sessionId = state.sessionId,
                            ),
                        )
                    },
                )
                if (state.customSelected) {
                    OpenCodeTextInput(
                        value = state.customText,
                        onValueChange = { text ->
                            onAction(
                                OpenCodeSessionComposerAction.QuestionCustomTextChanged(
                                    requestId = state.requestId,
                                    sessionId = state.sessionId,
                                    text = text,
                                ),
                            )
                        },
                        hint = "Enter a custom answer",
                        enabled = !state.submitting,
                    )
                }
            }
        }
        state.errorMessage?.let {
            Text(it, color = openCodeColor("error"), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Dismiss"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.DismissQuestion(state.requestId, state.sessionId))
            },
            enabled = !state.submitting,
            contrast = OpenCodeButtonContrast.Low,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.submitting) {
                CircularProgressIndicator(modifier = Modifier.padding(horizontal = 4.dp), strokeWidth = 2.dp)
            }
            if (showBack) {
                OpenCodeButton(
                    OpenCodeButtonVariant.CompactText("Back"),
                    onClick = {
                        onAction(OpenCodeSessionComposerAction.QuestionBack(state.requestId, state.sessionId))
                    },
                    enabled = state.backEnabled && !state.submitting,
                    contrast = OpenCodeButtonContrast.Low,
                )
            }
            if (showNext) {
                OpenCodeButton(
                    OpenCodeButtonVariant.CompactText("Next"),
                    onClick = {
                        onAction(OpenCodeSessionComposerAction.QuestionNext(state.requestId, state.sessionId))
                    },
                    enabled = state.nextEnabled && !state.submitting,
                )
            } else {
                OpenCodeButton(
                    OpenCodeButtonVariant.CompactText("Submit"),
                    onClick = {
                        onAction(OpenCodeSessionComposerAction.SubmitQuestion(state.requestId, state.sessionId))
                    },
                    enabled = state.submitEnabled && !state.submitting,
                )
            }
        }
    }
}

private fun questionSelectionDecorator(selected: Boolean): StartDecorator =
    StartDecorator.Icon(
        imageVector = if (selected) OpenCodeIcons.RadioButtonChecked else OpenCodeIcons.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
    )

@Preview(showBackground = true)
@Composable
private fun PermissionComposerPreview() {
    PermissionComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PermissionComposerDarkPreview() {
    PermissionComposerPreviewContent()
}

@Preview(showBackground = true)
@Composable
private fun MentionComposerPreview() {
    MentionComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MentionComposerDarkPreview() {
    MentionComposerPreviewContent()
}

@Composable
private fun PermissionComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.Permission(
                    requestId = "permission-preview",
                    sessionId = "session-preview",
                    permission = "bash",
                    patterns = listOf("git status", "git diff --stat"),
                    queueCount = 2,
                ),
            onAction = {},
        )
    }
}

@Composable
private fun MentionComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.Prompt(
                    text = "Ask @explore about this",
                    mentionRanges = listOf(4 until 12),
                    selectedModelLabel = "Claude Sonnet 4",
                    selectedModeLabel = "Build",
                    selectedVariantLabel = "Default",
                    showVariant = true,
                    sendEnabled = true,
                    showStop = false,
                    stopEnabled = false,
                ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuestionComposerPreview() {
    QuestionComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuestionComposerDarkPreview() {
    QuestionComposerPreviewContent()
}

@Composable
private fun QuestionComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.Question(
                    requestId = "question-preview",
                    sessionId = "session-preview",
                    questionIndex = 0,
                    questionCount = 2,
                    header = "Approach",
                    question = "How should we implement this change?",
                    options =
                        listOf(
                            OpenCodeSessionComposerState.QuestionOption(
                                label = "Minimal patch",
                                description = "Touch only the failing path",
                            ),
                            OpenCodeSessionComposerState.QuestionOption(
                                label = "Broader refactor",
                                description = "Clean up nearby callers too",
                            ),
                        ),
                    selectedLabels = listOf("Minimal patch"),
                    submitEnabled = false,
                    nextEnabled = true,
                ),
            onAction = {},
        )
    }
}
