package dev.opencode.mobile.ui.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.domain.feedback.FeedbackAttachment
import dev.opencode.mobile.domain.feedback.FeedbackAttachmentRenderer
import dev.opencode.mobile.domain.feedback.FeedbackCooldownStore
import dev.opencode.mobile.domain.feedback.FeedbackHiddenRect
import dev.opencode.mobile.domain.feedback.FeedbackKind
import dev.opencode.mobile.domain.feedback.FeedbackMetadataSource
import dev.opencode.mobile.domain.feedback.FeedbackReport
import dev.opencode.mobile.domain.feedback.FeedbackScreen
import dev.opencode.mobile.domain.feedback.FeedbackScreenshot
import dev.opencode.mobile.domain.feedback.FeedbackSharePayload
import dev.opencode.mobile.domain.feedback.FeedbackSubmitResult
import dev.opencode.mobile.domain.feedback.FeedbackSubmitter
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import dev.opencode.mobile.ui.sessionchat.ComposerDictationUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pages inside the feedback sheet. */
enum class FeedbackPage {
    Compose,
    HideParts,
    LogPreview,
}

sealed interface FeedbackSendStatus {
    data object Idle : FeedbackSendStatus

    data object Sending : FeedbackSendStatus

    data class Sent(
        val reference: String,
    ) : FeedbackSendStatus

    data object Offline : FeedbackSendStatus

    data class RateLimited(
        val retryAfterSeconds: Int,
    ) : FeedbackSendStatus

    data object Unverified : FeedbackSendStatus

    data object Failed : FeedbackSendStatus
}

data class FeedbackUiState(
    val screen: FeedbackScreen = FeedbackScreen.Settings,
    val kind: FeedbackKind = FeedbackKind.Bug,
    val message: String = "",
    val screenshot: FeedbackScreenshot? = null,
    /** The screen captured before the sheet opened; "+ Screenshot" re-attaches it. */
    val capturedScreenshot: FeedbackScreenshot? = null,
    val includeLogs: Boolean = true,
    val logWindow: DiagnosticLogExportWindow = DiagnosticLogExportWindow.DEFAULT,
    val page: FeedbackPage = FeedbackPage.Compose,
    /** Redacted log lines for the Diagnostic log page; null while loading. */
    val logPreviewLines: List<String>? = null,
    val dictation: ComposerDictationUiState? = null,
    val status: FeedbackSendStatus = FeedbackSendStatus.Idle,
    /** A share sheet the screen should open once, then [FeedbackViewModel.consumeShare]. */
    val pendingShare: FeedbackSharePayload? = null,
) {
    val placeholder: String
        get() =
            when (kind) {
                FeedbackKind.Bug -> "What happened? What did you expect?"
                FeedbackKind.Feedback -> "What would make OpenCode better for you?"
            }

    val canSend: Boolean
        get() = message.isNotBlank() && status != FeedbackSendStatus.Sending && dictation == null

    /** From Settings there is no capture, so adding a screenshot opens the Photo Picker. */
    val addScreenshotPicksPhoto: Boolean get() = capturedScreenshot == null

    /** Sheet title. Listening replaces the report title while the orb is up. */
    val sheetTitle: String
        get() =
            when {
                page == FeedbackPage.HideParts -> "Hide parts"
                page == FeedbackPage.LogPreview -> "Diagnostic log"
                showsListeningTitle -> "Listening"
                else -> "Report a problem"
            }

    /** The dictation card replaces the compose form, so the sheet is one surface. */
    val dictationTakesOverCompose: Boolean
        get() = status !is FeedbackSendStatus.Sent && page == FeedbackPage.Compose && dictation != null

    /** Orb states use the Listening title. An unavailable card still takes over, but it is not listening. */
    private val showsListeningTitle: Boolean
        get() =
            dictationTakesOverCompose &&
                when (dictation?.phase) {
                    ComposerDictationPhase.Listening,
                    ComposerDictationPhase.Finishing,
                    ComposerDictationPhase.Stopped,
                    -> true
                    else -> false
                }
}

/**
 * Feedback composer Screen Model (#721): one draft, its attachments, dictation, and the send
 * states. The 60 s cooldown is checked here before the submitter makes any request.
 */
@HiltViewModel
class FeedbackViewModel
    @Inject
    constructor(
        private val submitter: FeedbackSubmitter,
        private val metadataSource: FeedbackMetadataSource,
        private val cooldownStore: FeedbackCooldownStore,
        private val renderer: FeedbackAttachmentRenderer,
    ) : ViewModel() {
        internal var nowMs: () -> Long = { System.currentTimeMillis() }
        internal var dictationFinishDelayMs: Long = 350

        private val mutableState = MutableStateFlow(FeedbackUiState())
        val state: StateFlow<FeedbackUiState> = mutableState.asStateFlow()

        private var sendJob: Job? = null
        private var previewJob: Job? = null
        private var dictationInsertJob: Job? = null
        private var dictationGeneration = 0

        /** The last report built for sending; Share Instead reuses it. */
        private var lastReport: FeedbackReport? = null

        /**
         * Opens the sheet from [screen]. A sent report starts a fresh draft; an unsent one keeps
         * its kind and message. [capturedJpeg] is the screen captured before the sheet opened.
         * A send still in flight (the sheet was closed mid-send) keeps running and keeps its draft,
         * so its result, reference and cooldown still land.
         */
        fun open(
            screen: FeedbackScreen,
            capturedJpeg: ByteArray? = null,
        ) {
            invalidateDictationInsert()
            if (isSending) {
                mutableState.update { it.copy(page = FeedbackPage.Compose) }
                return
            }
            val captured = capturedJpeg?.let { FeedbackScreenshot(it) }
            mutableState.update { current ->
                val keepDraft = current.status !is FeedbackSendStatus.Sent
                FeedbackUiState(
                    screen = screen,
                    kind = if (keepDraft) current.kind else FeedbackKind.Bug,
                    message = if (keepDraft) current.message else "",
                    screenshot =
                        when {
                            captured != null -> captured
                            keepDraft && screen == FeedbackScreen.Settings && current.capturedScreenshot == null ->
                                current.screenshot
                            else -> null
                        },
                    capturedScreenshot = captured,
                    includeLogs = if (keepDraft) current.includeLogs else true,
                    logWindow = if (keepDraft) current.logWindow else DiagnosticLogExportWindow.DEFAULT,
                )
            }
        }

        private val isSending: Boolean get() = mutableState.value.status == FeedbackSendStatus.Sending

        /**
         * Applies a draft edit unless a send is in flight. The report is built from the draft as it
         * was when Send was tapped, so later edits would not reach the upload.
         */
        private fun editDraft(transform: (FeedbackUiState) -> FeedbackUiState) {
            mutableState.update { current ->
                if (current.status == FeedbackSendStatus.Sending) current else transform(current)
            }
        }

        fun setKind(kind: FeedbackKind) {
            editDraft { it.copy(kind = kind) }
        }

        fun updateMessage(message: String) {
            editDraft { it.copy(message = message.take(FeedbackReport.MAX_MESSAGE_LENGTH)) }
        }

        fun removeScreenshot() {
            editDraft { it.copy(screenshot = null, page = FeedbackPage.Compose) }
        }

        /** Re-attaches the captured screen. From Settings the screen opens the Photo Picker instead. */
        fun addCapturedScreenshot() {
            editDraft { current ->
                current.capturedScreenshot?.let { current.copy(screenshot = it) } ?: current
            }
        }

        fun attachScreenshot(jpeg: ByteArray) {
            editDraft { it.copy(screenshot = FeedbackScreenshot(jpeg)) }
        }

        fun openHideParts() {
            if (mutableState.value.screenshot == null || isSending) return
            mutableState.update { it.copy(page = FeedbackPage.HideParts) }
        }

        /** Covers the area around a tap at ([x], [y]), both fractions of the screenshot. */
        fun hideArea(
            x: Float,
            y: Float,
        ) {
            editDraft { current ->
                val screenshot = current.screenshot ?: return@editDraft current
                current.copy(
                    screenshot = screenshot.withHiddenRects(screenshot.hiddenRects + FeedbackHiddenRect.centeredAt(x, y)),
                )
            }
        }

        fun undoHiddenArea() {
            editDraft { current ->
                val screenshot = current.screenshot ?: return@editDraft current
                current.copy(screenshot = screenshot.withHiddenRects(screenshot.hiddenRects.dropLast(1)))
            }
        }

        fun closePage() {
            previewJob?.cancel()
            mutableState.update { it.copy(page = FeedbackPage.Compose, logPreviewLines = null) }
        }

        fun setIncludeLogs(include: Boolean) {
            editDraft { it.copy(includeLogs = include) }
        }

        fun setLogWindow(window: DiagnosticLogExportWindow) {
            if (isSending) return
            mutableState.update { it.copy(logWindow = window) }
            if (mutableState.value.page == FeedbackPage.LogPreview) loadLogPreview()
        }

        fun openLogPreview() {
            mutableState.update { it.copy(page = FeedbackPage.LogPreview, logPreviewLines = null) }
            loadLogPreview()
        }

        private fun loadLogPreview() {
            previewJob?.cancel()
            val window = mutableState.value.logWindow
            previewJob =
                viewModelScope.launch {
                    val lines =
                        try {
                            renderer.logPreview(window)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    mutableState.update { current ->
                        if (current.page == FeedbackPage.LogPreview && current.logWindow == window) {
                            current.copy(logPreviewLines = lines)
                        } else {
                            current
                        }
                    }
                }
        }

        fun send() {
            val current = mutableState.value
            if (!current.canSend) return
            val remaining = FeedbackCooldownStore.remainingSeconds(cooldownStore.lastSentAtEpochMs(), nowMs())
            if (remaining > 0) {
                mutableState.update { it.copy(status = FeedbackSendStatus.RateLimited(remaining)) }
                return
            }
            mutableState.update { it.copy(status = FeedbackSendStatus.Sending) }
            sendJob =
                viewModelScope.launch {
                    val result =
                        try {
                            val report = buildReport(current)
                            lastReport = report
                            submitter.submit(report)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            FeedbackSubmitResult.Failed(error.javaClass.simpleName)
                        }
                    apply(result)
                }
        }

        /** Unverified fallback: hand the same report to the share sheet. */
        fun shareInstead() {
            val report = lastReport ?: return
            mutableState.update {
                it.copy(status = FeedbackSendStatus.Idle, pendingShare = FeedbackSharePayload.from(report))
            }
        }

        fun consumeShare() {
            mutableState.update { it.copy(pendingShare = null) }
        }

        fun dismissError() {
            mutableState.update { current ->
                when (current.status) {
                    FeedbackSendStatus.Sending, is FeedbackSendStatus.Sent -> current
                    else -> current.copy(status = FeedbackSendStatus.Idle)
                }
            }
        }

        /** The sheet closed. Dictation stops; the draft stays unless it was sent. */
        fun dismiss() {
            cancelActiveDictation()
            previewJob?.cancel()
            mutableState.update { current ->
                if (current.status is FeedbackSendStatus.Sent) {
                    FeedbackUiState(screen = current.screen, status = current.status)
                } else {
                    current.copy(page = FeedbackPage.Compose, logPreviewLines = null)
                }
            }
        }

        private suspend fun buildReport(state: FeedbackUiState): FeedbackReport {
            val attachments =
                buildList {
                    state.screenshot?.let { add(FeedbackAttachment.screenshot(renderer.screenshot(it))) }
                    if (state.includeLogs) add(FeedbackAttachment.logs(renderer.logs(state.logWindow)))
                }
            return FeedbackReport(
                reportId = FeedbackReport.newReportId(),
                kind = state.kind,
                message = state.message.trim(),
                metadata = metadataSource.metadata(state.screen),
                attachments = attachments.filter { it.size in 1..FeedbackAttachment.MAX_BYTES },
            )
        }

        private fun apply(result: FeedbackSubmitResult) {
            when (result) {
                is FeedbackSubmitResult.Sent -> {
                    cooldownStore.recordSent(nowMs())
                    mutableState.update { it.copy(status = FeedbackSendStatus.Sent(result.reference)) }
                }
                FeedbackSubmitResult.Offline -> mutableState.update { it.copy(status = FeedbackSendStatus.Offline) }
                is FeedbackSubmitResult.RateLimited ->
                    mutableState.update { it.copy(status = FeedbackSendStatus.RateLimited(result.retryAfterSeconds)) }
                FeedbackSubmitResult.Unverified -> mutableState.update { it.copy(status = FeedbackSendStatus.Unverified) }
                is FeedbackSubmitResult.Failed -> mutableState.update { it.copy(status = FeedbackSendStatus.Failed) }
                is FeedbackSubmitResult.ShareInstead ->
                    mutableState.update { it.copy(status = FeedbackSendStatus.Idle, pendingShare = result.payload) }
            }
        }

        // Dictation: mirrors the Session Chat composer's state machine (SessionChatViewModel).

        fun beginDictation(
            languageLabel: String,
            onDevice: Boolean,
        ) {
            if (isSending) return
            invalidateDictationInsert()
            mutableState.update {
                it.copy(
                    dictation =
                        ComposerDictationUiState(
                            phase = ComposerDictationPhase.Listening,
                            languageLabel = languageLabel,
                            onDevice = onDevice,
                            startedAtEpochMs = nowMs(),
                        ),
                )
            }
        }

        fun beginDictationUnavailable(
            phase: ComposerDictationPhase,
            message: String? = null,
            languageLabel: String = "",
        ) {
            if (!phase.isUnavailable) return
            if (mutableState.value.dictation?.phase == ComposerDictationPhase.Finishing) return
            invalidateDictationInsert()
            mutableState.update {
                it.copy(
                    dictation =
                        ComposerDictationUiState(
                            phase = phase,
                            languageLabel = languageLabel,
                            startedAtEpochMs = nowMs(),
                            errorMessage = message,
                        ),
                )
            }
        }

        /** Applies a model download update only while the card still shows the model. */
        fun dictationModelDownload(
            phase: ComposerDictationPhase,
            progress: Float? = null,
        ) {
            if (!phase.isModelPhase) return
            mutableState.update { state ->
                val current = state.dictation
                if (current == null || !current.phase.isModelPhase) {
                    state
                } else {
                    state.copy(dictation = current.copy(phase = phase, downloadProgress = progress))
                }
            }
        }

        fun dictationPartial(
            committed: String,
            volatile: String,
        ) {
            updateListening { it.copy(committed = committed, volatile = volatile) }
        }

        fun dictationLevel(level: Float) {
            updateListening { it.copy(level = level.coerceIn(0f, 1f)) }
        }

        fun dictationStopped() {
            updateListening { it.copy(phase = ComposerDictationPhase.Stopped, level = 0f) }
        }

        fun dictationInsert() {
            val current = mutableState.value.dictation ?: return
            if (
                current.phase != ComposerDictationPhase.Listening &&
                current.phase != ComposerDictationPhase.Stopped
            ) {
                return
            }
            if ((current.committed + current.volatile).isBlank()) return
            val generation = ++dictationGeneration
            mutableState.update { it.copy(dictation = current.copy(phase = ComposerDictationPhase.Finishing)) }
            dictationInsertJob?.cancel()
            dictationInsertJob =
                viewModelScope.launch {
                    delay(dictationFinishDelayMs)
                    if (generation != dictationGeneration) return@launch
                    val latest = mutableState.value.dictation ?: return@launch
                    if (latest.phase != ComposerDictationPhase.Finishing) return@launch
                    mutableState.update {
                        it.copy(
                            message = appendTranscript(it.message, latest.committed + latest.volatile),
                            dictation = null,
                        )
                    }
                }
        }

        fun dictationCancel() {
            cancelActiveDictation()
        }

        private fun updateListening(transform: (ComposerDictationUiState) -> ComposerDictationUiState) {
            mutableState.update { state ->
                val current = state.dictation
                if (current == null || current.phase != ComposerDictationPhase.Listening) {
                    state
                } else {
                    state.copy(dictation = transform(current))
                }
            }
        }

        private fun cancelActiveDictation() {
            invalidateDictationInsert()
            if (mutableState.value.dictation == null) return
            mutableState.update { it.copy(dictation = null) }
        }

        private fun invalidateDictationInsert() {
            dictationGeneration += 1
            dictationInsertJob?.cancel()
            dictationInsertJob = null
        }

        internal companion object {
            /** Appends dictated text after the draft with one separating space. */
            fun appendTranscript(
                message: String,
                transcript: String,
            ): String {
                val next = transcript.trim()
                if (next.isEmpty()) return message
                val separator = if (message.isEmpty() || message.last().isWhitespace()) "" else " "
                return (message + separator + next).take(FeedbackReport.MAX_MESSAGE_LENGTH)
            }
        }
    }
