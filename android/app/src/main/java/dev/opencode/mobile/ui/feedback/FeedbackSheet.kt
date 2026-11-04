package dev.opencode.mobile.ui.feedback

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.domain.feedback.FeedbackKind
import dev.opencode.mobile.domain.feedback.FeedbackReport
import dev.opencode.mobile.domain.feedback.FeedbackScreen
import dev.opencode.mobile.domain.feedback.FeedbackSharePayload
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.ComposerDictationCardModel
import dev.opencode.mobile.ui.components.OpenCodeAnchoredOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeAttachmentChip
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeDictationCard
import dev.opencode.mobile.ui.components.OpenCodeDictationCardActions
import dev.opencode.mobile.ui.components.OpenCodeDictationCardVariant
import dev.opencode.mobile.ui.components.OpenCodeDictationUnavailableReason
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeLayout
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import dev.opencode.mobile.ui.sessionchat.ComposerDictationUiState
import dev.opencode.mobile.ui.sessionchat.dictation.ComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.DictationModelDownloader
import dev.opencode.mobile.ui.sessionchat.dictation.DictationSupport
import dev.opencode.mobile.ui.sessionchat.dictation.SystemComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.cardPhase
import dev.opencode.mobile.ui.sessionchat.dictation.dictationLanguageLabel
import dev.opencode.mobile.ui.sessionchat.dictation.formatDictationElapsed
import dev.opencode.mobile.ui.sessionchat.dictation.probeDictationSupport
import dev.opencode.mobile.ui.text.ResourcesUiTextLookup
import dev.opencode.mobile.ui.text.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.opencode.kit.R as KitR

/** Opens the feedback sheet. Hoist with [rememberFeedbackSheetState] next to the entry point. */
@Stable
class FeedbackSheetState {
    internal var visible by mutableStateOf(false)
    internal var openRequests by mutableIntStateOf(0)

    fun open() {
        openRequests += 1
    }
}

@Composable
fun rememberFeedbackSheetState(): FeedbackSheetState = remember { FeedbackSheetState() }

/**
 * Report a problem sheet (#721). With [captureScreen], the window is captured before the
 * sheet opens: the sheet is an overlay on the decor view and would cover the screenshot.
 */
@Composable
fun FeedbackSheet(
    sheetState: FeedbackSheetState,
    screen: FeedbackScreen,
    captureScreen: Boolean,
    viewModel: FeedbackViewModel = hiltViewModel(key = "feedback"),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(sheetState.openRequests) {
        if (sheetState.openRequests == 0) return@LaunchedEffect
        val capture =
            if (captureScreen) {
                // Let the overflow menu's dismissal settle so it is not in the capture.
                withFrameNanos { }
                withFrameNanos { }
                context.findActivity()?.let { FeedbackScreenCapture.capture(it) }
            } else {
                null
            }
        viewModel.open(screen, capture)
        sheetState.visible = true
    }

    LaunchedEffect(state.pendingShare) {
        val payload = state.pendingShare ?: return@LaunchedEffect
        // Consume last: it changes this effect's key and would cancel the file writes.
        runCatching { shareIntent(context, payload) }.onSuccess { context.startActivity(it) }
        viewModel.consumeShare()
    }

    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                coroutineScope.launch { FeedbackPhotoImport.load(context, uri)?.let(viewModel::attachScreenshot) }
            }
        }

    val dictation = rememberFeedbackDictation(viewModel, state.dictation, active = sheetState.visible)

    fun dismiss() {
        sheetState.visible = false
        viewModel.dismiss()
    }

    val page = if (state.status is FeedbackSendStatus.Sent) null else state.page
    val listeningCard =
        if (state.dictationTakesOverCompose) state.dictation?.toCardModel(dictation.elapsedLabel) else null
    OpenCodeBottomSheet(
        visible = sheetState.visible,
        title = state.sheetTitle.resolve(),
        onDismissRequest = {
            if (page == FeedbackPage.HideParts || page == FeedbackPage.LogPreview) {
                viewModel.closePage()
            } else {
                dismiss()
            }
        },
        headerLeadingAction =
            if (page == FeedbackPage.HideParts || page == FeedbackPage.LogPreview) {
                OpenCodeBottomSheetHeaderAction(
                    OpenCodeIcons.ArrowBack,
                    stringResource(KitR.string.top_bar_back),
                    viewModel::closePage,
                )
            } else {
                null
            },
        height = OpenCodeBottomSheetHeight.Half,
        keyboard = if (page == FeedbackPage.Compose) OpenCodeBottomSheetKeyboard.Expand else OpenCodeBottomSheetKeyboard.Dismiss,
        pinnedTop = {
            when (page) {
                FeedbackPage.Compose ->
                    if (!state.dictationTakesOverCompose) {
                        KindSwitch(state.kind, viewModel::setKind)
                    }
                FeedbackPage.LogPreview -> LogWindowSwitch(state.logWindow, viewModel::setLogWindow)
                FeedbackPage.HideParts, null -> Unit
            }
        },
        pinnedBottom =
            if (listeningCard != null) {
                {
                    // Pinned so a long transcript never scrolls Insert out of reach.
                    OpenCodeDictationCardActions(
                        variant = listeningCard.variant,
                        committed = listeningCard.committed,
                        volatileTail = listeningCard.volatileTail,
                        unavailableReason = listeningCard.unavailableReason,
                        onCancel = viewModel::dictationCancel,
                        onInsert = viewModel::dictationInsert,
                        onOpenSettings = { openAppSettings(context) },
                        onDownload = dictation.download,
                        onRetry = dictation.retry,
                    )
                }
            } else {
                {
                    when (page) {
                        FeedbackPage.Compose ->
                            FeedbackActions(
                                state = state,
                                showsMic = dictation.showsMic,
                                onAddScreenshot = {
                                    if (state.addScreenshotPicksPhoto) {
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    } else {
                                        viewModel.addCapturedScreenshot()
                                    }
                                },
                                onAddLogs = { viewModel.setIncludeLogs(true) },
                                onDictate = dictation.begin,
                                onSend = viewModel::send,
                            )
                        FeedbackPage.HideParts ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OpenCodeButton(
                                    variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.feedback_undo)),
                                    contrast = OpenCodeButtonContrast.Low,
                                    enabled = state.screenshot?.hiddenRects?.isNotEmpty() == true,
                                    onClick = viewModel::undoHiddenArea,
                                    modifier = Modifier.weight(1f),
                                )
                                OpenCodeButton(
                                    variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.feedback_done)),
                                    onClick = viewModel::closePage,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        FeedbackPage.LogPreview -> Unit
                        null ->
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.feedback_done)),
                                onClick = ::dismiss,
                            )
                    }
                }
            },
    ) {
        AnimatedContent(
            targetState = listeningCard,
            transitionSpec = {
                fadeIn(tween(durationMillis = 240)) togetherWith fadeOut(tween(durationMillis = 160))
            },
            contentKey = { it != null },
            label = DICTATION_MORPH_ANIMATION_LABEL,
        ) { card ->
            if (card != null) {
                FeedbackDictation(
                    card = card,
                    onCancel = viewModel::dictationCancel,
                    onInsert = viewModel::dictationInsert,
                    onOpenSettings = { openAppSettings(context) },
                    onDownload = dictation.download,
                    onRetry = dictation.retry,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset)
                            .padding(top = 8.dp, bottom = 12.dp),
                )
            } else {
                when (page) {
                    FeedbackPage.Compose -> ComposeBody(state, viewModel)
                    FeedbackPage.HideParts -> HidePartsBody(state, viewModel)
                    FeedbackPage.LogPreview -> LogPreviewBody(state)
                    null -> SentBody(state)
                }
            }
        }
    }
}

@Composable
private fun KindSwitch(
    kind: FeedbackKind,
    onKindChange: (FeedbackKind) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FeedbackKind.entries.forEach { value ->
            OpenCodeChoiceBadge(
                label = stringResource(value.badgeLabelRes()),
                selected = kind == value,
                onClick = { onKindChange(value) },
            )
        }
    }
}

@Composable
private fun LogWindowSwitch(
    window: DiagnosticLogExportWindow,
    onWindowChange: (DiagnosticLogExportWindow) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiagnosticLogExportWindow.entries.forEach { value ->
            OpenCodeChoiceBadge(
                label = pluralStringResource(R.plurals.feedback_log_window_minutes, value.minutes, value.minutes),
                selected = window == value,
                onClick = { onWindowChange(value) },
            )
        }
    }
}

@Composable
private fun ComposeBody(
    state: FeedbackUiState,
    viewModel: FeedbackViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.status.errorNotice(onTryAgain = viewModel::send, onShareInstead = viewModel::shareInstead)?.let { notice ->
            OpenCodeInlineNotice(
                message = notice.first,
                tone = OpenCodeInlineNoticeTone.Error,
                variant = OpenCodeInlineNoticeVariant.Banner,
                action = notice.second,
            )
        }
        // While sending, the draft is frozen: what's on screen is what gets uploaded.
        val editable = state.status != FeedbackSendStatus.Sending
        if (state.screenshot != null || state.includeLogs) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.screenshot?.let { shot ->
                    val hidden = shot.hiddenRects.size
                    OpenCodeAttachmentChip(
                        attachment =
                            OpenCodeAttachmentPresentation(
                                id = "screenshot",
                                filename =
                                    if (hidden == 0) {
                                        stringResource(R.string.feedback_attachment_screenshot)
                                    } else {
                                        pluralStringResource(R.plurals.feedback_attachment_screenshot_hidden, hidden, hidden)
                                    },
                                variant = OpenCodeAttachmentVariant.Image,
                                imageBytes = shot.jpeg,
                            ),
                        onRemove = { viewModel.removeScreenshot() },
                        onClick = viewModel::openHideParts,
                        onClickLabel = stringResource(R.string.feedback_title_hide_parts),
                        enabled = editable,
                    )
                }
                if (state.includeLogs) {
                    OpenCodeAttachmentChip(
                        attachment =
                            OpenCodeAttachmentPresentation(
                                id = "logs",
                                filename =
                                    pluralStringResource(
                                        R.plurals.feedback_attachment_logs_minutes,
                                        state.logWindow.minutes,
                                        state.logWindow.minutes,
                                    ),
                                variant = OpenCodeAttachmentVariant.Text,
                            ),
                        onRemove = { viewModel.setIncludeLogs(false) },
                        onClick = viewModel::openLogPreview,
                        onClickLabel = stringResource(R.string.feedback_title_log_preview),
                        enabled = editable,
                    )
                }
            }
        }
        OpenCodeTextInput(
            value = state.message,
            onValueChange = viewModel::updateMessage,
            hint = state.placeholder.resolve(),
            enabled = editable,
            minLines = FEEDBACK_MESSAGE_MIN_LINES,
            maxLines = FEEDBACK_MESSAGE_MAX_LINES,
            maxLength = FeedbackReport.MAX_MESSAGE_LENGTH,
        )
        Text(
            text = stringResource(R.string.feedback_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
        )
    }
}

private const val DICTATION_MORPH_ANIMATION_LABEL = "feedback-dictation-morph"
private const val FEEDBACK_MESSAGE_MIN_LINES = 4
private const val FEEDBACK_MESSAGE_MAX_LINES = 8

/** Add attachment and dictation on the left, Send on the right. */
@Composable
private fun FeedbackActions(
    state: FeedbackUiState,
    showsMic: Boolean,
    onAddScreenshot: () -> Unit,
    onAddLogs: () -> Unit,
    onDictate: () -> Unit,
    onSend: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val sending = state.status == FeedbackSendStatus.Sending
    var addMenuOpen by remember { mutableStateOf(false) }
    val sendingLabel = stringResource(R.string.feedback_sending)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeAnchoredOverflowHost(
            expanded = addMenuOpen,
            onDismissRequest = { addMenuOpen = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = stringResource(R.string.feedback_attachment_screenshot),
                        icon = null,
                        onClick = onAddScreenshot,
                        enabled = state.screenshot == null,
                    ),
                    OpenCodeOverflowMenuEntry(
                        title = stringResource(R.string.feedback_attachment_logs),
                        icon = null,
                        onClick = onAddLogs,
                        enabled = !state.includeLogs,
                    ),
                ),
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, stringResource(R.string.feedback_add_attachment)),
                contrast = OpenCodeButtonContrast.Low,
                enabled = !sending && (state.screenshot == null || !state.includeLogs),
                onClick = { addMenuOpen = true },
            )
        }
        if (showsMic) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Mic, stringResource(R.string.feedback_dictate)),
                contrast = OpenCodeButtonContrast.Low,
                enabled = !sending,
                onClick = onDictate,
            )
        }
        Spacer(Modifier.weight(1f))
        if (sending) {
            Box(
                modifier = Modifier.size(40.dp).semantics { contentDescription = sendingLabel },
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
                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, stringResource(R.string.feedback_send)),
                contrast = OpenCodeButtonContrast.Accent,
                enabled = state.canSend,
                onClick = {
                    focusManager.clearFocus()
                    onSend()
                },
            )
        }
    }
}

/** The Dictation Card replaces the compose form, so listening is the sheet rather than a second popup. */
@Composable
private fun FeedbackDictation(
    card: ComposerDictationCardModel,
    onCancel: () -> Unit,
    onInsert: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus() }
    OpenCodeDictationCard(
        modifier = modifier,
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
        onCancel = onCancel,
        onInsert = onInsert,
        onOpenSettings = onOpenSettings,
        onDownload = onDownload,
        onRetry = onRetry,
        showsActions = false,
    )
}

@Composable
private fun HidePartsBody(
    state: FeedbackUiState,
    viewModel: FeedbackViewModel,
) {
    val screenshot = state.screenshot ?: return
    val bitmap =
        remember(screenshot.jpeg) {
            BitmapFactory.decodeByteArray(screenshot.jpeg, 0, screenshot.jpeg.size)?.asImageBitmap()
        } ?: return
    val hidden = screenshot.hiddenRects.size
    val imageLabel = pluralStringResource(R.plurals.feedback_hide_parts_image_hidden, hidden, hidden)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.feedback_hide_parts_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-base"),
        )
        Box(
            modifier =
                Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                    .clip(OpenCodeSmallShape)
                    .semantics { contentDescription = imageLabel }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            viewModel.hideArea(offset.x / size.width, offset.y / size.height)
                        }
                    },
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Canvas(modifier = Modifier.matchParentSize()) {
                screenshot.hiddenRects.forEach { rect ->
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(rect.left * size.width, rect.top * size.height),
                        size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogPreviewBody(state: FeedbackUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                pluralStringResource(
                    R.plurals.feedback_log_preview_summary,
                    state.logWindow.minutes,
                    state.logWindow.minutes,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
        )
        val lines = state.logPreviewLines
        Text(
            text =
                when {
                    lines == null -> stringResource(R.string.feedback_log_preview_loading)
                    lines.isEmpty() ->
                        pluralStringResource(
                            R.plurals.feedback_log_preview_empty_message,
                            state.logWindow.minutes,
                            state.logWindow.minutes,
                        )
                    // Redacted log lines are log content, shown as is.
                    else -> lines.joinToString("\n")
                },
            style = CodeTextStyle,
            color = openCodeColor("text-strong"),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(OpenCodeSmallShape)
                    .background(openCodeColor("surface-raised-base"))
                    .padding(12.dp),
        )
    }
}

@Composable
private fun SentBody(state: FeedbackUiState) {
    val reference = (state.status as? FeedbackSendStatus.Sent)?.reference.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(openCodeColor("surface-raised-base")),
            contentAlignment = Alignment.Center,
        ) {
            Icon(OpenCodeIcons.Check, contentDescription = null, tint = openCodeColor("icon-strong-base"))
        }
        Text(
            text = stringResource(R.string.feedback_sent_title),
            style = MaterialTheme.typography.titleMedium,
            color = openCodeColor("text-strong"),
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                stringResource(
                    when (state.kind) {
                        FeedbackKind.Bug -> R.string.feedback_sent_bug
                        FeedbackKind.Feedback -> R.string.feedback_sent_idea
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-base"),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.feedback_sent_reference, reference),
            style = CodeTextStyle,
            color = openCodeColor("text-strong"),
            modifier =
                Modifier
                    .clip(OpenCodeSmallShape)
                    .background(openCodeColor("surface-raised-base"))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Bug / Idea switch label. The domain label stays English: it names the report in the share subject. */
private fun FeedbackKind.badgeLabelRes(): Int =
    when (this) {
        FeedbackKind.Bug -> R.string.feedback_kind_bug
        FeedbackKind.Feedback -> R.string.feedback_kind_idea
    }

@Composable
private fun FeedbackSendStatus.errorNotice(
    onTryAgain: () -> Unit,
    onShareInstead: () -> Unit,
): Pair<String, OpenCodeInlineNoticeAction?>? {
    val tryAgain = stringResource(R.string.feedback_action_try_again)
    return when (this) {
        FeedbackSendStatus.Offline ->
            stringResource(R.string.feedback_error_offline) to OpenCodeInlineNoticeAction(tryAgain, onTryAgain)
        is FeedbackSendStatus.RateLimited ->
            pluralStringResource(R.plurals.feedback_error_rate_limited, retryAfterSeconds, retryAfterSeconds) to null
        FeedbackSendStatus.Unverified ->
            stringResource(R.string.feedback_error_unverified) to
                OpenCodeInlineNoticeAction(stringResource(R.string.feedback_action_share_instead), onShareInstead)
        FeedbackSendStatus.Failed ->
            stringResource(R.string.feedback_error_failed) to OpenCodeInlineNoticeAction(tryAgain, onTryAgain)
        FeedbackSendStatus.Idle, FeedbackSendStatus.Sending, is FeedbackSendStatus.Sent -> null
    }
}

private fun ComposerDictationUiState.toCardModel(elapsedLabel: String): ComposerDictationCardModel {
    val (variant, reason) =
        when (phase) {
            ComposerDictationPhase.Listening -> OpenCodeDictationCardVariant.Listening to null
            ComposerDictationPhase.Stopped -> OpenCodeDictationCardVariant.Stopped to null
            ComposerDictationPhase.Finishing -> OpenCodeDictationCardVariant.Finishing to null
            ComposerDictationPhase.PermissionDenied ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.PermissionDenied
            ComposerDictationPhase.ModelNotInstalled ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelNotInstalled
            ComposerDictationPhase.ModelDownloading ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelDownloading
            ComposerDictationPhase.ModelDownloadQueued ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelDownloadQueued
            ComposerDictationPhase.ModelDownloadFailed ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelDownloadFailed
            ComposerDictationPhase.RecognizerError ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.RecognizerError
        }
    return ComposerDictationCardModel(
        variant = variant,
        committed = committed,
        volatileTail = volatile,
        level = level,
        languageLabel = languageLabel,
        onDevice = onDevice,
        elapsedLabel = elapsedLabel,
        unavailableReason = reason,
        errorMessage = errorMessage,
        downloadProgress = downloadProgress,
    )
}

/** Dictation glue for the sheet: the same engine and permission flow as Session Chat. */
private class FeedbackDictationHandle(
    val showsMic: Boolean,
    val elapsedLabel: String,
    val begin: () -> Unit,
    val download: () -> Unit,
    val retry: () -> Unit,
)

@Composable
private fun rememberFeedbackDictation(
    viewModel: FeedbackViewModel,
    dictation: ComposerDictationUiState?,
    active: Boolean,
): FeedbackDictationHandle {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val session = remember(context) { SystemComposerDictationSession(context) }
    val downloader = remember(context, coroutineScope) { DictationModelDownloader.system(context, coroutineScope) }
    var support by remember { mutableStateOf<DictationSupport>(DictationSupport.Hidden) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val phase = dictation?.phase

    fun startReady(ready: DictationSupport.Ready) {
        viewModel.beginDictation(ready.languageLabel, ready.onDevice)
        if (viewModel.state.value.dictation
                ?.phase != ComposerDictationPhase.Listening
        ) {
            return
        }
        session.start(
            object : ComposerDictationSession.Listener {
                override fun onPartial(
                    committed: String,
                    volatile: String,
                ) = viewModel.dictationPartial(committed, volatile)

                override fun onLevel(level: Float) = viewModel.dictationLevel(level)

                override fun onStopped() = viewModel.dictationStopped()

                override fun onUnavailable(
                    phase: ComposerDictationPhase,
                    message: String?,
                ) = viewModel.beginDictationUnavailable(phase, message)
            },
            languageTag = ready.languageTag,
        )
    }

    fun showModelCard(downloading: Boolean) {
        viewModel.beginDictationUnavailable(
            if (downloading) ComposerDictationPhase.ModelDownloading else ComposerDictationPhase.ModelNotInstalled,
            languageLabel = dictationLanguageLabel(),
        )
    }

    // Assigned below: an installed model re-enters applyProbe, which can start a watch.
    lateinit var startModelDownload: (Boolean) -> Unit

    fun applyProbe(probed: DictationSupport) {
        support = probed
        when (probed) {
            is DictationSupport.Ready -> startReady(probed)
            is DictationSupport.ModelNotInstalled -> showModelCard(downloading = false)
            is DictationSupport.ModelDownloading -> {
                showModelCard(downloading = true)
                startModelDownload(true)
            }
            DictationSupport.PermissionDenied -> viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied)
            DictationSupport.Hidden -> viewModel.dictationCancel()
        }
    }

    startModelDownload = { alreadyPending ->
        downloader.start(support.languageTag, alreadyPending) { update ->
            val card = update.cardPhase()
            if (card != null) {
                viewModel.dictationModelDownload(card.first, card.second)
            } else if (viewModel.state.value.dictation
                    ?.phase
                    ?.isModelPhase == true
            ) {
                coroutineScope.launch { applyProbe(probeDictationSupport(context)) }
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                coroutineScope.launch { applyProbe(probeDictationSupport(context)) }
            } else {
                viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied)
            }
        }

    fun begin() {
        when (val current = support) {
            DictationSupport.Hidden -> Unit
            is DictationSupport.ModelNotInstalled -> showModelCard(downloading = false)
            is DictationSupport.ModelDownloading -> {
                showModelCard(downloading = true)
                startModelDownload(true)
            }
            DictationSupport.PermissionDenied, is DictationSupport.Ready -> {
                val granted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                when {
                    !granted -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    current is DictationSupport.Ready -> startReady(current)
                    else -> coroutineScope.launch { applyProbe(probeDictationSupport(context)) }
                }
            }
        }
    }

    LaunchedEffect(active) {
        if (active) support = probeDictationSupport(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.dictationCancel() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { downloader.setHostPaused(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { downloader.setHostPaused(false) }
    LaunchedEffect(dictation == null, phase, active) {
        if (!active || dictation == null || phase != ComposerDictationPhase.Listening) session.stop()
        if (!active) downloader.close()
    }
    val ticking = phase == ComposerDictationPhase.Listening || phase == ComposerDictationPhase.Finishing
    LaunchedEffect(dictation?.startedAtEpochMs, ticking) {
        if (!ticking) return@LaunchedEffect
        nowMs = System.currentTimeMillis()
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    return FeedbackDictationHandle(
        showsMic = support.showsMic,
        elapsedLabel = formatDictationElapsed(dictation?.startedAtEpochMs ?: nowMs, nowMs),
        begin = ::begin,
        download = { startModelDownload(false) },
        retry = {
            coroutineScope.launch {
                val probed = probeDictationSupport(context)
                support = probed
                when (probed) {
                    is DictationSupport.Ready, DictationSupport.PermissionDenied -> begin()
                    is DictationSupport.ModelNotInstalled -> showModelCard(downloading = false)
                    is DictationSupport.ModelDownloading -> {
                        showModelCard(downloading = true)
                        startModelDownload(true)
                    }
                    DictationSupport.Hidden -> viewModel.dictationCancel()
                }
            }
        },
    )
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )
}

/** Writes the report files to the cache FileProvider path and builds the share chooser. */
private suspend fun shareIntent(
    context: Context,
    payload: FeedbackSharePayload,
): Intent {
    val uris =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "feedback").apply { mkdirs() }
            payload.attachments.map { attachment ->
                val file = File(directory, attachment.name).apply { writeBytes(attachment.bytes) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
    val send =
        if (uris.isEmpty()) {
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (uris.size == 1) payload.attachments.first().contentType else "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData =
                    ClipData.newUri(context.contentResolver, payload.attachments.first().name, uris.first()).also { clip ->
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    send.putExtra(Intent.EXTRA_SUBJECT, payload.subject.resolve(ResourcesUiTextLookup(context.resources)))
    send.putExtra(Intent.EXTRA_TEXT, payload.text)
    return Intent.createChooser(send, context.getString(R.string.feedback_share_chooser_title))
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
