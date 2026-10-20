package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeRadius
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
        val showPendingRevert: Boolean = false,
        val selectedModelLabel: String,
        val selectedModeLabel: String,
        val selectedVariantLabel: String,
        val showVariant: Boolean,
        val sendEnabled: Boolean,
        val attachEnabled: Boolean = true,
        val showStop: Boolean,
        val stopEnabled: Boolean,
        val followUpDelivery: FollowUpDelivery? = null,
        val showDictationButton: Boolean = false,
        /**
         * The draft starts with `!` and Send runs it as a shell command. The `!` is highlighted
         * and a selected Shell badge replaces the model and agent chips; tapping it sends
         * [OpenCodeSessionComposerAction.ExitShellMode].
         */
        val shellMode: Boolean = false,
    ) : OpenCodeSessionComposerState

    /** A file an edit/write permission would change, with its diff size. */
    data class PermissionFile(
        val path: String,
        val additions: Int,
        val deletions: Int,
    )

    data class Permission(
        val requestId: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String>,
        /** Edit/write targets; shown as `path +N −N` instead of [patterns] when present. */
        val files: List<PermissionFile> = emptyList(),
        /** Patterns "Always" would save; the Always action shows only when non-empty. */
        val alwaysPatterns: List<String> = emptyList(),
        val queuePosition: Int = 1,
        val queueCount: Int = 1,
        val submitting: Boolean = false,
        val actionsEnabled: Boolean = true,
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
        /** Multiselect bounds from the form field; null means unbounded. */
        val minSelections: Int? = null,
        val maxSelections: Int? = null,
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

    data class ChildLockout(
        val selectedModelLabel: String,
        val selectedVariantLabel: String,
        val showVariant: Boolean,
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

    data object CancelRevert : OpenCodeSessionComposerAction

    /** Drop the leading `!` so the draft is a prompt again. */
    data object ExitShellMode : OpenCodeSessionComposerAction

    data object Send : OpenCodeSessionComposerAction

    data object ToggleFollowUp : OpenCodeSessionComposerAction

    data object Stop : OpenCodeSessionComposerAction

    data class AllowPermissionOnce(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class DenyPermission(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    data class AlwaysAllowPermission(
        val requestId: String,
        val sessionId: String,
    ) : OpenCodeSessionComposerAction

    /** Collapse the permission dock without answering it. */
    data class HidePermission(
        val requestId: String,
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

    data object BackToMainSession : OpenCodeSessionComposerAction

    data object BeginDictation : OpenCodeSessionComposerAction

    data object DictationCancel : OpenCodeSessionComposerAction

    data object DictationInsert : OpenCodeSessionComposerAction

    data object DictationOpenSettings : OpenCodeSessionComposerAction

    data object DictationDownload : OpenCodeSessionComposerAction

    data object DictationRetry : OpenCodeSessionComposerAction
}

/**
 * Focus handle for the prompt text input of [OpenCodeSessionComposer]. Hoist it with
 * [rememberOpenCodeSessionComposerFocus] to move the caret into the prompt (for example from a
 * hardware keyboard shortcut) or to observe whether the prompt is focused.
 */
@Stable
class OpenCodeSessionComposerFocus {
    internal val requester = FocusRequester()
    private var attachedPromptInputs by mutableIntStateOf(0)
    private var showsPrompt by mutableStateOf(false)

    /** True while the prompt text input holds focus. */
    var isFocused by mutableStateOf(false)
        private set

    /** True while the composer shows a prompt text input that can take focus. */
    val canRequestFocus: Boolean
        get() = showsPrompt && attachedPromptInputs > 0

    /**
     * Focuses the prompt text input and returns true. A no-op returning false when the composer
     * cannot take input: permission, question, or child-session lockout content, or no composer.
     */
    fun requestFocus(): Boolean {
        if (!canRequestFocus) return false
        requester.requestFocus()
        return true
    }

    internal fun updateShowsPrompt(value: Boolean) {
        showsPrompt = value
    }

    internal fun attachPromptInput() {
        attachedPromptInputs += 1
    }

    internal fun detachPromptInput() {
        attachedPromptInputs = (attachedPromptInputs - 1).coerceAtLeast(0)
        if (attachedPromptInputs == 0) isFocused = false
    }

    internal fun updateFocused(focused: Boolean) {
        isFocused = focused
    }
}

@Composable
fun rememberOpenCodeSessionComposerFocus(): OpenCodeSessionComposerFocus = remember { OpenCodeSessionComposerFocus() }

/** ~5 body lines — enough for a long prompt without swallowing the chat (OPE-127). */
private const val PromptMaxVisibleLines = 5

@Composable
fun OpenCodeSessionComposer(
    state: OpenCodeSessionComposerState,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
    modifier: Modifier = Modifier,
    focus: OpenCodeSessionComposerFocus? = null,
    status: (@Composable () -> Unit)? = null,
    dictation: ComposerDictationCardModel? = null,
) {
    val composerFocus = focus ?: rememberOpenCodeSessionComposerFocus()
    val showsPrompt = state is OpenCodeSessionComposerState.Prompt
    SideEffect { composerFocus.updateShowsPrompt(showsPrompt) }
    val outline =
        when (state) {
            is OpenCodeSessionComposerState.Permission ->
                BorderStroke(
                    1.dp,
                    if (state.errorMessage == null) openCodeColor("warning") else openCodeColor("error"),
                )
            is OpenCodeSessionComposerState.Question,
            is OpenCodeSessionComposerState.ChildLockout,
            is OpenCodeSessionComposerState.Prompt,
            -> null
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
            border = outline,
            // Opaque: at 95% the timeline rows behind show through as faint rectangles.
            opaque = true,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (status != null) {
                    status()
                    HorizontalDivider(color = openCodeColor("border-weak-base"))
                }
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
                                if (dictation != null) {
                                    DictationTakeover(
                                        card = dictation,
                                        onAction = onAction,
                                    )
                                } else {
                                    PromptEntryContent(
                                        state = state,
                                        onAction = onAction,
                                        focus = composerFocus,
                                    )
                                }

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

                            is OpenCodeSessionComposerState.ChildLockout ->
                                ChildLockoutContent(
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
}

@Composable
private fun DictationTakeover(
    card: ComposerDictationCardModel,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        enter.animateTo(1f, animationSpec = tween(durationMillis = 240))
    }
    OpenCodeDictationCard(
        variant = card.variant,
        committed = card.committed,
        volatileTail = card.volatileTail,
        level = card.level,
        languageLabel = card.languageLabel,
        onDevice = card.onDevice,
        elapsedLabel = card.elapsedLabel,
        unavailableReason = card.unavailableReason,
        errorMessage = card.errorMessage,
        downloadProgress = card.downloadProgress,
        onCancel = { onAction(OpenCodeSessionComposerAction.DictationCancel) },
        onInsert = { onAction(OpenCodeSessionComposerAction.DictationInsert) },
        onOpenSettings = { onAction(OpenCodeSessionComposerAction.DictationOpenSettings) },
        onDownload = { onAction(OpenCodeSessionComposerAction.DictationDownload) },
        onRetry = { onAction(OpenCodeSessionComposerAction.DictationRetry) },
        modifier =
            Modifier.graphicsLayer {
                alpha = enter.value
                translationY = with(density) { (12.dp * (1f - enter.value)).toPx() }
            },
    )
}

@Composable
private fun PromptEntryContent(
    state: OpenCodeSessionComposerState.Prompt,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
    focus: OpenCodeSessionComposerFocus,
) {
    val focusManager = LocalFocusManager.current
    val reportFocus = rememberOpenCodeTextInputFocusReport(OpenCodeFocusedTextInput.SessionComposer)
    DisposableEffect(focus) {
        focus.attachPromptInput()
        onDispose { focus.detachPromptInput() }
    }
    if (state.showPendingRevert || state.attachments.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.showPendingRevert) {
                PendingRevertChip(onCancel = { onAction(OpenCodeSessionComposerAction.CancelRevert) })
            }
            state.attachments.forEach { attachment ->
                OpenCodeAttachmentChip(
                    attachment = attachment,
                    onRemove = { onAction(OpenCodeSessionComposerAction.RemoveAttachment(it)) },
                )
            }
        }
    }

    val mentionColor = openCodeColor("syntax-type")
    val bodyColor = openCodeColor("text-strong")
    // The shell prefix shares the mention highlight so the mode reads in the text itself.
    val highlightRanges =
        remember(state.mentionRanges, state.shellMode) {
            if (state.shellMode) listOf(0..0) + state.mentionRanges else state.mentionRanges
        }
    val mentionTransformation =
        remember(highlightRanges, mentionColor) {
            MentionVisualTransformation(mentionRanges = highlightRanges, mentionColor = mentionColor)
        }

    // Own TextFieldValue so typing keeps the caret, while programmatic text
    // updates (slash/mention insert, atomic delete, restore, clear) sync selection.
    // Mention chrome is applied via VisualTransformation so it always tracks
    // state.mentionRanges (AnnotatedString on TextFieldValue is dropped by IME updates).
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = state.text, selection = TextRange(state.text.length)))
    }
    var suppressImeEcho by remember { mutableStateOf(false) }
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
            suppressImeEcho = true
        }
    }

    // Cap growth so chips/send stay above the keyboard; further text scrolls
    // inside the field (OPE-127). Matches iOS OpenCodeMentionTextView (~5 body lines).
    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            if (newValue.text != state.text && textFieldValue.text != state.text) {
                return@BasicTextField
            }
            if (suppressImeEcho) {
                suppressImeEcho = false
                if (newValue.text != state.text) return@BasicTextField
            }
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
        // A shell command must reach the server as typed: no autocorrect or auto-capitalization.
        keyboardOptions =
            if (state.shellMode) {
                KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            } else {
                KeyboardOptions.Default
            },
        maxLines = PromptMaxVisibleLines,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .focusRequester(focus.requester)
                .onFocusChanged {
                    focus.updateFocused(it.isFocused)
                    reportFocus(it.isFocused)
                },
        decorationBox = { innerTextField ->
            Box {
                if (state.text.isBlank()) {
                    Text(
                        text = "Ask anything...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = openCodeColor("text-weak"),
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
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Slash, "Open commands"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = { onAction(OpenCodeSessionComposerAction.OpenCommands) },
            )
            if (state.showDictationButton) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Mic, "Dictate"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = { onAction(OpenCodeSessionComposerAction.BeginDictation) },
                )
            }
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
                variant =
                    OpenCodeButtonVariant.IconCircle(
                        imageVector =
                            if (state.followUpDelivery == FollowUpDelivery.Steer) {
                                OpenCodeIcons.ArrowWarmUp
                            } else {
                                OpenCodeIcons.ArrowUpward
                            },
                        contentDescription =
                            sessionComposerSendLabel(
                                followUpDelivery = state.followUpDelivery,
                                hasActiveWork = state.showStop,
                                shellMode = state.shellMode,
                            ),
                    ),
                enabled = state.sendEnabled,
                contrast = OpenCodeButtonContrast.Accent,
                onLongClick =
                    state.followUpDelivery?.let {
                        { onAction(OpenCodeSessionComposerAction.ToggleFollowUp) }
                    },
                onClick = {
                    // OPE-138: send leaves editing intent; clear focus so the IME dismisses.
                    focusManager.clearFocus()
                    onAction(OpenCodeSessionComposerAction.Send)
                },
            )
        }
    }
}

/** Selection hint under a question; reflects a multiselect's `minItems` / `maxItems`. */
internal fun questionSelectionHint(
    multiple: Boolean,
    minSelections: Int? = null,
    maxSelections: Int? = null,
): String {
    if (!multiple || maxSelections == 1) return "Select one"
    val min = minSelections?.takeIf { it > 1 }
    return when {
        min != null && maxSelections != null && maxSelections > min -> "Select $min to $maxSelections"
        min != null && maxSelections != null -> "Select $min"
        min != null -> "Select at least $min"
        maxSelections != null -> "Select up to $maxSelections"
        else -> "Select all that apply"
    }
}

/**
 * Send button label. Steer/Queue only describe what Send does while work is running;
 * an idle session just sends. A shell command always runs now.
 */
internal fun sessionComposerSendLabel(
    followUpDelivery: FollowUpDelivery?,
    hasActiveWork: Boolean,
    shellMode: Boolean = false,
): String =
    when (followUpDelivery.takeIf { hasActiveWork && !shellMode }) {
        FollowUpDelivery.Steer -> "Send as Steer"
        FollowUpDelivery.Queue -> "Send as Queue"
        null -> if (shellMode) "Run shell command" else "Send"
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

@Composable
private fun PendingRevertChip(onCancel: () -> Unit) {
    val chipShape = RoundedCornerShape(OpenCodeRadius.small)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, openCodeColor("border-weaker-base"), chipShape)
                .background(openCodeColor("surface-raised-base"), chipShape)
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                OpenCodeIcons.Undo,
                contentDescription = null,
                tint = openCodeColor("icon-base"),
            )
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Reverting this message, REVERT" },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Reverting this message",
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "REVERT",
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-base"),
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(
                OpenCodeIcons.Close,
                contentDescription = "Cancel revert",
                tint = openCodeColor("icon-strong-base"),
            )
        }
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
                if (state.shellMode) {
                    // Like the TUI, shell mode shows "Shell" in place of the agent and model.
                    OpenCodeChoiceBadge(
                        label = "Shell",
                        selected = true,
                        onClick = { onAction(OpenCodeSessionComposerAction.ExitShellMode) },
                        variant = OpenCodeChoiceBadgeVariant.Glass,
                        modifier = Modifier.semantics { contentDescription = "Shell mode. Leave shell mode" },
                    )
                } else {
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
}

@Composable
private fun ChildLockoutContent(
    state: OpenCodeSessionComposerState.ChildLockout,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    val lockoutLabel =
        if (state.showVariant) {
            "Model ${state.selectedModelLabel}, effort ${state.selectedVariantLabel}"
        } else {
            "Model ${state.selectedModelLabel}"
        }
    val textStrong = openCodeColor("text-strong")
    val ctaFill = textStrong.copy(alpha = 0.14f)
    val ctaShape = RoundedCornerShape(percent = 50)
    val chipShape = RoundedCornerShape(percent = 50)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = lockoutLabel
                },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LockoutChip(label = state.selectedModelLabel, fill = ctaFill, textColor = textStrong, shape = chipShape)
        if (state.showVariant) {
            LockoutChip(label = state.selectedVariantLabel, fill = ctaFill, textColor = textStrong, shape = chipShape)
        }
    }
    Text(
        text = "Child sessions cannot be prompted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(ctaShape)
                .background(textStrong.copy(alpha = 0.05f))
                .clickable { onAction(OpenCodeSessionComposerAction.BackToMainSession) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Back to main session",
            color = textStrong,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun LockoutChip(
    label: String,
    fill: Color,
    textColor: Color,
    shape: RoundedCornerShape,
) {
    Text(
        text = label,
        color = textColor,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        modifier =
            Modifier
                .background(fill, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PermissionApprovalContent(
    state: OpenCodeSessionComposerState.Permission,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    val actionsEnabled = state.actionsEnabled && !state.submitting
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission requested", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.queueCount > 1) {
                Text("${state.queuePosition} of ${state.queueCount}", style = MaterialTheme.typography.bodySmall)
            }
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.KeyboardArrowDown, "Hide permission request"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = { onAction(OpenCodeSessionComposerAction.HidePermission(state.requestId)) },
            )
        }
        Text(state.permission.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.files.isNotEmpty()) {
                    state.files.forEach { file -> PermissionFileLine(file) }
                } else {
                    state.patterns.forEach { Text(it, style = PermissionCodeStyle) }
                }
            }
        }
        if (state.alwaysPatterns.isNotEmpty()) {
            Text(
                text = "Always allows: ${state.alwaysPatterns.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
            )
        }
        state.errorMessage?.let {
            Text(it, color = openCodeColor("error"), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Deny"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.DenyPermission(state.requestId, state.sessionId))
            },
            enabled = actionsEnabled,
            contrast = OpenCodeButtonContrast.Low,
        )
        Spacer(Modifier.weight(1f))
        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp), strokeWidth = 2.dp)
        }
        if (state.alwaysPatterns.isNotEmpty()) {
            OpenCodeButton(
                OpenCodeButtonVariant.CompactText("Always"),
                onClick = {
                    onAction(OpenCodeSessionComposerAction.AlwaysAllowPermission(state.requestId, state.sessionId))
                },
                enabled = actionsEnabled,
                contrast = OpenCodeButtonContrast.Low,
            )
        }
        OpenCodeButton(
            OpenCodeButtonVariant.CompactText("Allow once"),
            onClick = {
                onAction(OpenCodeSessionComposerAction.AllowPermissionOnce(state.requestId, state.sessionId))
            },
            enabled = actionsEnabled,
        )
    }
}

/** Commands and paths read as code (iOS: `OpenCodeTypography.code`). */
private val PermissionCodeStyle = CodeTextStyle.copy(fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun PermissionFileLine(file: OpenCodeSessionComposerState.PermissionFile) {
    Text(
        text =
            buildAnnotatedString {
                append(file.path)
                if (file.additions > 0 || file.deletions > 0) {
                    append("  ")
                    withStyle(SpanStyle(color = openCodeColor("diff-add"))) { append("+${file.additions}") }
                    append(" ")
                    withStyle(SpanStyle(color = openCodeColor("diff-delete"))) { append("\u2212${file.deletions}") }
                }
            },
        style = PermissionCodeStyle,
    )
}

@Composable
private fun QuestionRequestContent(
    state: OpenCodeSessionComposerState.Question,
    onAction: (OpenCodeSessionComposerAction) -> Unit,
) {
    val showBack = state.questionIndex > 0
    val showNext = state.questionIndex < state.questionCount - 1
    val selectionHint =
        questionSelectionHint(
            multiple = state.multiple,
            minSelections = state.minSelections,
            maxSelections = state.maxSelections,
        )
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

@Preview(showBackground = true)
@Composable
private fun PendingRevertComposerPreview() {
    PendingRevertComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PendingRevertComposerDarkPreview() {
    PendingRevertComposerPreviewContent()
}

@Composable
private fun PendingRevertComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.Prompt(
                    text = "Add a dark mode toggle to Settings",
                    showPendingRevert = true,
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
private fun ShellModeComposerPreview() {
    ShellModeComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShellModeComposerDarkPreview() {
    ShellModeComposerPreviewContent()
}

@Composable
private fun ShellModeComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.Prompt(
                    text = "!git status",
                    selectedModelLabel = "Claude Sonnet 4",
                    selectedModeLabel = "Build",
                    selectedVariantLabel = "Default",
                    showVariant = true,
                    sendEnabled = true,
                    attachEnabled = false,
                    showStop = false,
                    stopEnabled = false,
                    shellMode = true,
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

@Preview(showBackground = true)
@Composable
private fun ChildLockoutComposerPreview() {
    ChildLockoutComposerPreviewContent()
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChildLockoutComposerDarkPreview() {
    ChildLockoutComposerPreviewContent()
}

@Composable
private fun ChildLockoutComposerPreviewContent() {
    OpenCodeTheme {
        OpenCodeSessionComposer(
            state =
                OpenCodeSessionComposerState.ChildLockout(
                    selectedModelLabel = "Claude Sonnet 4",
                    selectedVariantLabel = "High",
                    showVariant = true,
                ),
            onAction = {},
        )
    }
}
