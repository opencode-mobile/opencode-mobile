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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.domain.feedback.FeedbackKind
import dev.opencode.mobile.domain.feedback.FeedbackScreen
import dev.opencode.mobile.domain.feedback.FeedbackSharePayload
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.ComposerDictationCardModel
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
import dev.opencode.mobile.ui.components.OpenCodeDictationCardVariant
import dev.opencode.mobile.ui.components.OpenCodeDictationUnavailableReason
import dev.opencode.mobile.ui.components.OpenCodeFeedbackAttachment
import dev.opencode.mobile.ui.components.OpenCodeFeedbackAttachmentKind
import dev.opencode.mobile.ui.components.OpenCodeFeedbackComposer
import dev.opencode.mobile.ui.components.OpenCodeFeedbackComposerAction
import dev.opencode.mobile.ui.components.OpenCodeFeedbackComposerState
import dev.opencode.mobile.ui.components.OpenCodeFeedbackSendState
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import dev.opencode.mobile.ui.sessionchat.ComposerDictationUiState
import dev.opencode.mobile.ui.sessionchat.dictation.ComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.DictationModelDownloader
import dev.opencode.mobile.ui.sessionchat.dictation.DictationSupport
import dev.opencode.mobile.ui.sessionchat.dictation.SystemComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.formatDictationElapsed
import dev.opencode.mobile.ui.sessionchat.dictation.probeDictationSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    OpenCodeBottomSheet(
        visible = sheetState.visible,
        title =
            when (page) {
                FeedbackPage.HideParts -> "Hide parts"
                FeedbackPage.LogPreview -> "Diagnostic log"
                FeedbackPage.Compose, null -> "Report a problem"
            },
        onDismissRequest = {
            if (page == FeedbackPage.HideParts || page == FeedbackPage.LogPreview) {
                viewModel.closePage()
            } else {
                dismiss()
            }
        },
        headerLeadingAction =
            if (page == FeedbackPage.HideParts || page == FeedbackPage.LogPreview) {
                OpenCodeBottomSheetHeaderAction(OpenCodeIcons.ArrowBack, "Back", viewModel::closePage)
            } else {
                null
            },
        height = OpenCodeBottomSheetHeight.Half,
        keyboard = if (page == FeedbackPage.Compose) OpenCodeBottomSheetKeyboard.Expand else OpenCodeBottomSheetKeyboard.Dismiss,
        pinnedTop = {
            when (page) {
                FeedbackPage.Compose -> KindSwitch(state.kind, viewModel::setKind)
                FeedbackPage.LogPreview -> LogWindowSwitch(state.logWindow, viewModel::setLogWindow)
                FeedbackPage.HideParts, null -> Unit
            }
        },
        pinnedBottom = {
            when (page) {
                FeedbackPage.Compose ->
                    OpenCodeFeedbackComposer(
                        state = state.toComposerState(dictation.showsMic),
                        dictation = state.dictation?.toCardModel(dictation.elapsedLabel),
                        onAction = { action ->
                            when (action) {
                                is OpenCodeFeedbackComposerAction.MessageChanged -> viewModel.updateMessage(action.text)
                                is OpenCodeFeedbackComposerAction.OpenAttachment ->
                                    when (action.kind) {
                                        OpenCodeFeedbackAttachmentKind.Screenshot -> viewModel.openHideParts()
                                        OpenCodeFeedbackAttachmentKind.Logs -> viewModel.openLogPreview()
                                    }
                                is OpenCodeFeedbackComposerAction.RemoveAttachment ->
                                    when (action.kind) {
                                        OpenCodeFeedbackAttachmentKind.Screenshot -> viewModel.removeScreenshot()
                                        OpenCodeFeedbackAttachmentKind.Logs -> viewModel.setIncludeLogs(false)
                                    }
                                is OpenCodeFeedbackComposerAction.AddAttachment ->
                                    when (action.kind) {
                                        OpenCodeFeedbackAttachmentKind.Screenshot ->
                                            if (state.addScreenshotPicksPhoto) {
                                                photoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            } else {
                                                viewModel.addCapturedScreenshot()
                                            }
                                        OpenCodeFeedbackAttachmentKind.Logs -> viewModel.setIncludeLogs(true)
                                    }
                                OpenCodeFeedbackComposerAction.Send -> viewModel.send()
                                OpenCodeFeedbackComposerAction.BeginDictation -> dictation.begin()
                                OpenCodeFeedbackComposerAction.DictationCancel -> viewModel.dictationCancel()
                                OpenCodeFeedbackComposerAction.DictationInsert -> viewModel.dictationInsert()
                                OpenCodeFeedbackComposerAction.DictationOpenSettings -> openAppSettings(context)
                                OpenCodeFeedbackComposerAction.DictationDownload -> dictation.download()
                                OpenCodeFeedbackComposerAction.DictationRetry -> dictation.retry()
                            }
                        },
                    )
                FeedbackPage.HideParts ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OpenCodeButton(
                            variant = OpenCodeButtonVariant.FullWidthText("Undo"),
                            contrast = OpenCodeButtonContrast.Low,
                            enabled = state.screenshot?.hiddenRects?.isNotEmpty() == true,
                            onClick = viewModel::undoHiddenArea,
                            modifier = Modifier.weight(1f),
                        )
                        OpenCodeButton(
                            variant = OpenCodeButtonVariant.FullWidthText("Done"),
                            onClick = viewModel::closePage,
                            modifier = Modifier.weight(1f),
                        )
                    }
                FeedbackPage.LogPreview -> Unit
                null ->
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Done"),
                        onClick = ::dismiss,
                    )
            }
        },
    ) {
        when (page) {
            FeedbackPage.Compose -> ComposeBody(state, viewModel)
            FeedbackPage.HideParts -> HidePartsBody(state, viewModel)
            FeedbackPage.LogPreview -> LogPreviewBody(state)
            null -> SentBody(state)
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
            OpenCodeChoiceBadge(label = value.label, selected = kind == value, onClick = { onKindChange(value) })
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
                label = "${value.minutes} min",
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
        Text(
            text = FeedbackCopy.PRIVACY_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
        )
    }
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tap an area to cover it.",
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
                    .semantics { contentDescription = "Screenshot. Tap an area to cover it." }
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
            text = "Last ${state.logWindow.minutes} minutes · gzipped on upload. Redacted on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
        )
        val lines = state.logPreviewLines
        Text(
            text =
                when {
                    lines == null -> "Loading…"
                    lines.isEmpty() -> "No log lines in this window."
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
            text = "Thanks — report sent",
            style = MaterialTheme.typography.titleMedium,
            color = openCodeColor("text-strong"),
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                when (state.kind) {
                    FeedbackKind.Bug -> "The team will look into it."
                    FeedbackKind.Feedback -> "Thanks for the idea."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-base"),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Ref $reference",
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

internal object FeedbackCopy {
    const val PRIVACY_NOTE =
        "Goes to the OpenCode Mobile team only. Never includes your server address, username or password. " +
            "Logs are redacted on this phone."
    const val OFFLINE = "You're offline. Your report is kept here — try again when you're connected."
    const val UNVERIFIED =
        "Couldn't verify this copy of the app. Reports can only be sent from an App Store, TestFlight or Play install."
    const val FAILED = "Couldn't send the report."

    fun rateLimited(seconds: Int): String = "You just sent a report. Try again in $seconds s."
}

private fun FeedbackSendStatus.errorNotice(
    onTryAgain: () -> Unit,
    onShareInstead: () -> Unit,
): Pair<String, OpenCodeInlineNoticeAction?>? =
    when (this) {
        FeedbackSendStatus.Offline -> FeedbackCopy.OFFLINE to OpenCodeInlineNoticeAction("Try Again", onTryAgain)
        is FeedbackSendStatus.RateLimited -> FeedbackCopy.rateLimited(retryAfterSeconds) to null
        FeedbackSendStatus.Unverified -> FeedbackCopy.UNVERIFIED to OpenCodeInlineNoticeAction("Share Instead", onShareInstead)
        FeedbackSendStatus.Failed -> FeedbackCopy.FAILED to OpenCodeInlineNoticeAction("Try Again", onTryAgain)
        FeedbackSendStatus.Idle, FeedbackSendStatus.Sending, is FeedbackSendStatus.Sent -> null
    }

private fun FeedbackUiState.toComposerState(showsMic: Boolean): OpenCodeFeedbackComposerState {
    val attachments =
        buildList {
            screenshot?.let { shot ->
                val hidden = shot.hiddenRects.size
                add(
                    OpenCodeFeedbackAttachment(
                        kind = OpenCodeFeedbackAttachmentKind.Screenshot,
                        presentation =
                            OpenCodeAttachmentPresentation(
                                id = "screenshot",
                                filename = if (hidden == 0) "Screenshot" else "Screenshot · $hidden hidden",
                                variant = OpenCodeAttachmentVariant.Image,
                                imageBytes = shot.jpeg,
                            ),
                        openLabel = "Hide parts",
                    ),
                )
            }
            if (includeLogs) {
                add(
                    OpenCodeFeedbackAttachment(
                        kind = OpenCodeFeedbackAttachmentKind.Logs,
                        presentation =
                            OpenCodeAttachmentPresentation(
                                id = "logs",
                                filename = "Logs · ${logWindow.minutes} min",
                                variant = OpenCodeAttachmentVariant.Text,
                            ),
                        openLabel = "Diagnostic log",
                    ),
                )
            }
        }
    return OpenCodeFeedbackComposerState(
        message = message,
        placeholder = placeholder,
        attachments = attachments,
        addableAttachments =
            buildList {
                if (screenshot == null) add(OpenCodeFeedbackAttachmentKind.Screenshot)
                if (!includeLogs) add(OpenCodeFeedbackAttachmentKind.Logs)
            },
        showDictationButton = composerDictationEnabled && showsMic,
        sendState =
            when {
                status == FeedbackSendStatus.Sending -> OpenCodeFeedbackSendState.Sending
                canSend -> OpenCodeFeedbackSendState.Enabled
                else -> OpenCodeFeedbackSendState.Disabled
            },
    )
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
    val downloader = remember(context) { DictationModelDownloader(context) }
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
        )
    }

    fun applyProbe(probed: DictationSupport) {
        support = probed
        when (probed) {
            is DictationSupport.Ready -> startReady(probed)
            DictationSupport.ModelNotInstalled -> viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled)
            DictationSupport.PermissionDenied -> viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied)
            DictationSupport.Hidden -> viewModel.dictationCancel()
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
            DictationSupport.ModelNotInstalled -> viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled)
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
        download = downloader::trigger,
        retry = {
            coroutineScope.launch {
                val probed = probeDictationSupport(context)
                support = probed
                when (probed) {
                    is DictationSupport.Ready, DictationSupport.PermissionDenied -> begin()
                    DictationSupport.ModelNotInstalled ->
                        viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled)
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
    send.putExtra(Intent.EXTRA_SUBJECT, payload.subject)
    send.putExtra(Intent.EXTRA_TEXT, payload.text)
    return Intent.createChooser(send, "Share report")
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
