package dev.opencode.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** The two files a feedback report can carry. */
enum class OpenCodeFeedbackAttachmentKind(
    val addLabel: String,
) {
    Screenshot("+ Screenshot"),
    Logs("+ Logs"),
}

/** An attached file: its row presentation, opened by tap and removed with the close button. */
data class OpenCodeFeedbackAttachment(
    val kind: OpenCodeFeedbackAttachmentKind,
    val presentation: OpenCodeAttachmentPresentation,
    /** Accessibility name for opening it, e.g. "Hide parts" or "Preview log". */
    val openLabel: String,
)

enum class OpenCodeFeedbackSendState {
    Disabled,
    Enabled,

    /** Spinner in place of Send. */
    Sending,
}

data class OpenCodeFeedbackComposerState(
    val message: String,
    val placeholder: String,
    val attachments: List<OpenCodeFeedbackAttachment> = emptyList(),
    /** Missing attachments, shown as dashed "+ …" chips. */
    val addableAttachments: List<OpenCodeFeedbackAttachmentKind> = emptyList(),
    val characterLimit: Int = 5000,
    val showDictationButton: Boolean = false,
    val sendState: OpenCodeFeedbackSendState = OpenCodeFeedbackSendState.Disabled,
)

sealed interface OpenCodeFeedbackComposerAction {
    data class MessageChanged(
        val text: String,
    ) : OpenCodeFeedbackComposerAction

    data class OpenAttachment(
        val kind: OpenCodeFeedbackAttachmentKind,
    ) : OpenCodeFeedbackComposerAction

    data class RemoveAttachment(
        val kind: OpenCodeFeedbackAttachmentKind,
    ) : OpenCodeFeedbackComposerAction

    data class AddAttachment(
        val kind: OpenCodeFeedbackAttachmentKind,
    ) : OpenCodeFeedbackComposerAction

    data object BeginDictation : OpenCodeFeedbackComposerAction

    data object Send : OpenCodeFeedbackComposerAction

    data object DictationCancel : OpenCodeFeedbackComposerAction

    data object DictationInsert : OpenCodeFeedbackComposerAction

    data object DictationOpenSettings : OpenCodeFeedbackComposerAction

    data object DictationDownload : OpenCodeFeedbackComposerAction

    data object DictationRetry : OpenCodeFeedbackComposerAction
}

/** ~6 body lines before the message scrolls inside the field. */
private const val FeedbackMessageMaxLines = 6
private const val FeedbackMessageMinLines = 3

/**
 * Feedback report composer (#721): the Session Composer's floating shell with attachment
 * chips, a multiline message, the dictation mic and Send. [dictation] takes over the shell
 * with the Dictation Card while the user speaks, as in the Session Composer.
 */
@Composable
fun OpenCodeFeedbackComposer(
    state: OpenCodeFeedbackComposerState,
    onAction: (OpenCodeFeedbackComposerAction) -> Unit,
    modifier: Modifier = Modifier,
    dictation: ComposerDictationCardModel? = null,
) {
    OpenCodeFloatingChrome(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OpenCodeFloatingChromeCornerRadius),
        opaque = true,
    ) {
        AnimatedContent(
            targetState = dictation != null,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            transitionSpec = {
                (fadeIn(tween(durationMillis = 120, delayMillis = 80)) togetherWith fadeOut(tween(durationMillis = 80)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(durationMillis = 240) })
            },
            label = "feedback-composer-content",
        ) { dictating ->
            if (dictating && dictation != null) {
                FeedbackDictation(card = dictation, onAction = onAction)
            } else {
                FeedbackEntry(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun FeedbackDictation(
    card: ComposerDictationCardModel,
    onAction: (OpenCodeFeedbackComposerAction) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(Unit) { focusManager.clearFocus() }
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
        onCancel = { onAction(OpenCodeFeedbackComposerAction.DictationCancel) },
        onInsert = { onAction(OpenCodeFeedbackComposerAction.DictationInsert) },
        onOpenSettings = { onAction(OpenCodeFeedbackComposerAction.DictationOpenSettings) },
        onDownload = { onAction(OpenCodeFeedbackComposerAction.DictationDownload) },
        onRetry = { onAction(OpenCodeFeedbackComposerAction.DictationRetry) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackEntry(
    state: OpenCodeFeedbackComposerState,
    onAction: (OpenCodeFeedbackComposerAction) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // While sending, the draft is frozen: what's on screen is what gets uploaded.
    val editable = state.sendState != OpenCodeFeedbackSendState.Sending
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.attachments.forEach { attachment ->
            OpenCodeAttachmentChip(
                attachment = attachment.presentation,
                onRemove = { onAction(OpenCodeFeedbackComposerAction.RemoveAttachment(attachment.kind)) },
                onClick = { onAction(OpenCodeFeedbackComposerAction.OpenAttachment(attachment.kind)) },
                onClickLabel = attachment.openLabel,
                enabled = editable,
            )
        }
        if (state.addableAttachments.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.addableAttachments.forEach { kind ->
                    AddAttachmentChip(
                        label = kind.addLabel,
                        enabled = editable,
                        onClick = { onAction(OpenCodeFeedbackComposerAction.AddAttachment(kind)) },
                    )
                }
            }
        }
        FeedbackMessageField(
            message = state.message,
            placeholder = state.placeholder,
            characterLimit = state.characterLimit,
            enabled = editable,
            onMessageChange = { onAction(OpenCodeFeedbackComposerAction.MessageChanged(it)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (state.message.isEmpty()) "" else "${state.message.length}/${state.characterLimit}",
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                modifier = Modifier.weight(1f),
            )
            if (state.showDictationButton) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Mic, "Dictate"),
                    contrast = OpenCodeButtonContrast.Low,
                    enabled = state.sendState != OpenCodeFeedbackSendState.Sending,
                    onClick = { onAction(OpenCodeFeedbackComposerAction.BeginDictation) },
                )
            }
            if (state.sendState == OpenCodeFeedbackSendState.Sending) {
                Box(
                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Sending" },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = openCodeColor("icon-strong-base"),
                    )
                }
            } else {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send"),
                    contrast = OpenCodeButtonContrast.Accent,
                    enabled = state.sendState == OpenCodeFeedbackSendState.Enabled,
                    onClick = {
                        focusManager.clearFocus()
                        onAction(OpenCodeFeedbackComposerAction.Send)
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackMessageField(
    message: String,
    placeholder: String,
    characterLimit: Int,
    enabled: Boolean,
    onMessageChange: (String) -> Unit,
) {
    val bodyColor = openCodeColor(if (enabled) "text-strong" else "text-weak")
    BasicTextField(
        value = message,
        onValueChange = { onMessageChange(it.take(characterLimit)) },
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = bodyColor),
        cursorBrush = SolidColor(bodyColor),
        minLines = FeedbackMessageMinLines,
        maxLines = FeedbackMessageMaxLines,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .semantics { contentDescription = placeholder },
        decorationBox = { innerTextField ->
            Box {
                if (message.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = openCodeColor("text-weak"),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun AddAttachmentChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = openCodeColor("border-base")
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = openCodeColor("text-base"),
        modifier =
            Modifier
                .clip(OpenCodeSmallShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val inset = strokeWidth / 2
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(OpenCodeRadius.small.toPx()),
                        style =
                            Stroke(
                                width = strokeWidth,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                            ),
                    )
                }.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeFeedbackComposerPreview() {
    OpenCodeTheme(darkTheme = false) {
        Column(Modifier.padding(12.dp)) {
            Spacer(Modifier.size(8.dp))
            OpenCodeFeedbackComposer(
                state =
                    OpenCodeFeedbackComposerState(
                        message = "",
                        placeholder = "What happened? What did you expect?",
                        attachments =
                            listOf(
                                OpenCodeFeedbackAttachment(
                                    kind = OpenCodeFeedbackAttachmentKind.Logs,
                                    presentation =
                                        OpenCodeAttachmentPresentation(
                                            id = "logs",
                                            filename = "Logs · 15 min",
                                            variant = OpenCodeAttachmentVariant.Text,
                                        ),
                                    openLabel = "Preview log",
                                ),
                            ),
                        addableAttachments = listOf(OpenCodeFeedbackAttachmentKind.Screenshot),
                        showDictationButton = true,
                    ),
                onAction = {},
            )
        }
    }
}
