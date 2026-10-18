package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.ComposerDictationCardModel
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeDictationCardVariant
import dev.opencode.mobile.ui.components.OpenCodeFeedbackAttachment
import dev.opencode.mobile.ui.components.OpenCodeFeedbackAttachmentKind
import dev.opencode.mobile.ui.components.OpenCodeFeedbackComposer
import dev.opencode.mobile.ui.components.OpenCodeFeedbackComposerAction
import dev.opencode.mobile.ui.components.OpenCodeFeedbackComposerState
import dev.opencode.mobile.ui.components.OpenCodeFeedbackSendState

private enum class FeedbackComposerGalleryVariant(
    val label: String,
) {
    Empty("Empty"),
    Attachments("Attachments"),
    Sending("Sending"),
    Dictating("Dictating"),
}

@Composable
fun FeedbackComposerGalleryScreen(modifier: Modifier = Modifier) {
    var variantName by rememberSaveable { mutableStateOf(FeedbackComposerGalleryVariant.Attachments.name) }
    var message by rememberSaveable { mutableStateOf("") }
    var showScreenshot by rememberSaveable { mutableStateOf(true) }
    var showLogs by rememberSaveable { mutableStateOf(true) }
    val variant =
        FeedbackComposerGalleryVariant.entries.firstOrNull { it.name == variantName }
            ?: FeedbackComposerGalleryVariant.Attachments
    val withAttachments = variant != FeedbackComposerGalleryVariant.Empty
    val attachments =
        buildList {
            if (withAttachments && showScreenshot) {
                add(
                    OpenCodeFeedbackAttachment(
                        kind = OpenCodeFeedbackAttachmentKind.Screenshot,
                        presentation =
                            OpenCodeAttachmentPresentation(
                                id = "screenshot",
                                filename = "Screenshot · 1 hidden",
                                variant = OpenCodeAttachmentVariant.Image,
                            ),
                        openLabel = "Hide parts",
                    ),
                )
            }
            if (withAttachments && showLogs) {
                add(
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
                )
            }
        }
    val addable =
        buildList {
            if (!withAttachments || !showScreenshot) add(OpenCodeFeedbackAttachmentKind.Screenshot)
            if (!withAttachments || !showLogs) add(OpenCodeFeedbackAttachmentKind.Logs)
        }
    val galleryMessage =
        if (variant == FeedbackComposerGalleryVariant.Sending && message.isBlank()) {
            "The session list still shows Running after unlock."
        } else {
            message
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedbackComposerGalleryVariant.entries.forEach { value ->
                OpenCodeChoiceBadge(
                    label = value.label,
                    selected = variant == value,
                    onClick = { variantName = value.name },
                )
            }
        }
        OpenCodeFeedbackComposer(
            state =
                OpenCodeFeedbackComposerState(
                    message = galleryMessage,
                    placeholder = "What happened? What did you expect?",
                    attachments = attachments,
                    addableAttachments = addable,
                    showDictationButton = true,
                    sendState =
                        when {
                            variant == FeedbackComposerGalleryVariant.Sending -> OpenCodeFeedbackSendState.Sending
                            galleryMessage.isBlank() -> OpenCodeFeedbackSendState.Disabled
                            else -> OpenCodeFeedbackSendState.Enabled
                        },
                ),
            dictation =
                if (variant == FeedbackComposerGalleryVariant.Dictating) {
                    ComposerDictationCardModel(
                        variant = OpenCodeDictationCardVariant.Listening,
                        committed = "The approval card is missing",
                        volatileTail = " until I refresh",
                        level = 0.4f,
                        languageLabel = "English",
                        onDevice = true,
                        elapsedLabel = "0:07",
                    )
                } else {
                    null
                },
            onAction = { action ->
                when (action) {
                    is OpenCodeFeedbackComposerAction.MessageChanged -> message = action.text
                    is OpenCodeFeedbackComposerAction.RemoveAttachment ->
                        when (action.kind) {
                            OpenCodeFeedbackAttachmentKind.Screenshot -> showScreenshot = false
                            OpenCodeFeedbackAttachmentKind.Logs -> showLogs = false
                        }
                    is OpenCodeFeedbackComposerAction.AddAttachment ->
                        when (action.kind) {
                            OpenCodeFeedbackAttachmentKind.Screenshot -> showScreenshot = true
                            OpenCodeFeedbackAttachmentKind.Logs -> showLogs = true
                        }
                    OpenCodeFeedbackComposerAction.DictationCancel,
                    OpenCodeFeedbackComposerAction.DictationInsert,
                    -> variantName = FeedbackComposerGalleryVariant.Attachments.name
                    OpenCodeFeedbackComposerAction.BeginDictation ->
                        variantName = FeedbackComposerGalleryVariant.Dictating.name
                    else -> Unit
                }
            },
        )
    }
}
