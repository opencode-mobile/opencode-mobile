package dev.opencode.mobile.ui.feedback

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.domain.feedback.FeedbackAttachmentRenderer
import dev.opencode.mobile.domain.feedback.FeedbackCooldownStore
import dev.opencode.mobile.domain.feedback.FeedbackKind
import dev.opencode.mobile.domain.feedback.FeedbackMetadata
import dev.opencode.mobile.domain.feedback.FeedbackReport
import dev.opencode.mobile.domain.feedback.FeedbackScreen
import dev.opencode.mobile.domain.feedback.FeedbackScreenshot
import dev.opencode.mobile.domain.feedback.FeedbackSubmitResult
import dev.opencode.mobile.domain.feedback.FeedbackSubmitter
import dev.opencode.mobile.domain.feedback.ShareFeedbackSubmitter
import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cooldown = MemoryCooldownStore()
    private val renderer = FakeRenderer()
    private val submitted = mutableListOf<FeedbackReport>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun kindSwitch_changesPlaceholder() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)

        assertEquals("What happened? What did you expect?", viewModel.state.value.placeholder)
        viewModel.setKind(FeedbackKind.Feedback)
        assertEquals("What would make OpenCode better for you?", viewModel.state.value.placeholder)
        assertEquals("Idea", FeedbackKind.Feedback.label)
    }

    @Test
    fun message_isCappedAndRequiredToSend() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)
        assertFalse(viewModel.state.value.canSend)

        viewModel.updateMessage("   ")
        assertFalse(viewModel.state.value.canSend)
        viewModel.updateMessage("x".repeat(6000))
        assertEquals(5000, viewModel.state.value.message.length)
        assertTrue(viewModel.state.value.canSend)
    }

    @Test
    fun sessionChat_keepsTheCaptureForAddButDoesNotPreattachIt() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.SessionChat, capturedJpeg = byteArrayOf(9))

        assertNull(viewModel.state.value.screenshot)
        assertFalse(viewModel.state.value.addScreenshotPicksPhoto)
        viewModel.addCapturedScreenshot()
        assertEquals(FeedbackScreenshot(byteArrayOf(9)), viewModel.state.value.screenshot)
        viewModel.removeScreenshot()
        assertNull(viewModel.state.value.screenshot)
        viewModel.addCapturedScreenshot()
        assertEquals(FeedbackScreenshot(byteArrayOf(9)), viewModel.state.value.screenshot)
    }

    @Test
    fun settings_hasNoCaptureSoAddScreenshotPicksPhoto() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)

        assertNull(viewModel.state.value.screenshot)
        assertTrue(viewModel.state.value.addScreenshotPicksPhoto)
        viewModel.attachScreenshot(byteArrayOf(7))
        assertEquals(FeedbackScreenshot(byteArrayOf(7)), viewModel.state.value.screenshot)
    }

    @Test
    fun hideParts_addsAndUndoesBoxes() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.SessionChat, capturedJpeg = byteArrayOf(1))
        viewModel.addCapturedScreenshot()
        viewModel.openHideParts()
        assertEquals(FeedbackPage.HideParts, viewModel.state.value.page)

        viewModel.hideArea(0.5f, 0.5f)
        viewModel.hideArea(0.1f, 0.9f)
        val rects =
            viewModel.state.value.screenshot!!
                .hiddenRects
        assertEquals(2, rects.size)
        assertEquals(0.5f, (rects[0].left + rects[0].right) / 2f, 0.001f)
        assertEquals(0f, rects[1].left, 0f)

        viewModel.undoHiddenArea()
        assertEquals(
            1,
            viewModel.state.value.screenshot!!
                .hiddenRects.size,
        )
        viewModel.closePage()
        assertEquals(FeedbackPage.Compose, viewModel.state.value.page)
    }

    @Test
    fun logs_removeAddAndWindow() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.open(FeedbackScreen.Settings)
            assertTrue(viewModel.state.value.includeLogs)
            assertEquals(DiagnosticLogExportWindow.FifteenMinutes, viewModel.state.value.logWindow)

            viewModel.setIncludeLogs(false)
            assertFalse(viewModel.state.value.includeLogs)
            viewModel.setIncludeLogs(true)

            viewModel.openLogPreview()
            assertNull(viewModel.state.value.logPreviewLines)
            advanceUntilIdle()
            assertEquals(listOf("line 15"), viewModel.state.value.logPreviewLines)
            viewModel.setLogWindow(DiagnosticLogExportWindow.SixtyMinutes)
            advanceUntilIdle()
            assertEquals(listOf("line 60"), viewModel.state.value.logPreviewLines)
        }

    @Test
    fun send_buildsReportWithAttachmentsAndShowsSent() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val viewModel =
                viewModel { report ->
                    gate.await()
                    FeedbackSubmitResult.Sent(report.reference)
                }
            viewModel.open(FeedbackScreen.SessionChat, capturedJpeg = byteArrayOf(1, 2))
            viewModel.addCapturedScreenshot()
            viewModel.hideArea(0.5f, 0.5f)
            viewModel.updateMessage("  It froze.  ")

            viewModel.send()
            assertEquals(FeedbackSendStatus.Sending, viewModel.state.value.status)
            assertFalse(viewModel.state.value.canSend)
            gate.complete(Unit)
            advanceUntilIdle()

            val report = submitted.single()
            assertEquals("It froze.", report.message)
            assertEquals(FeedbackScreen.SessionChat, report.metadata.screen)
            assertEquals(listOf("screenshot.jpg", "logs.jsonl.gz"), report.attachments.map { it.name })
            assertEquals(1, renderer.renderedRects)
            assertEquals(FeedbackSendStatus.Sent(report.reference), viewModel.state.value.status)
            assertEquals(now, cooldown.lastSent)
        }

    @Test
    fun reopeningMidSend_letsTheSendFinishAndShowsTheResult() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val viewModel =
                viewModel { report ->
                    gate.await()
                    FeedbackSubmitResult.Sent(report.reference)
                }
            viewModel.open(FeedbackScreen.SessionChat, capturedJpeg = byteArrayOf(1, 2))
            viewModel.addCapturedScreenshot()
            viewModel.updateMessage("It froze.")
            viewModel.send()
            runCurrent()

            viewModel.dismiss()
            viewModel.open(FeedbackScreen.Settings)
            assertEquals(FeedbackSendStatus.Sending, viewModel.state.value.status)
            assertEquals("It froze.", viewModel.state.value.message)

            gate.complete(Unit)
            advanceUntilIdle()

            val report = submitted.single()
            assertEquals(FeedbackSendStatus.Sent(report.reference), viewModel.state.value.status)
            assertEquals(now, cooldown.lastSent)
        }

    @Test
    fun draftEditsWhileSending_areIgnored() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val viewModel =
                viewModel { report ->
                    gate.await()
                    FeedbackSubmitResult.Offline
                }
            viewModel.open(FeedbackScreen.SessionChat, capturedJpeg = byteArrayOf(1, 2))
            viewModel.addCapturedScreenshot()
            viewModel.updateMessage("It froze.")
            viewModel.send()
            runCurrent()

            viewModel.removeScreenshot()
            viewModel.setIncludeLogs(false)
            viewModel.updateMessage("edited")
            viewModel.setKind(FeedbackKind.Feedback)
            viewModel.hideArea(0.5f, 0.5f)
            viewModel.openHideParts()

            val sending = viewModel.state.value
            assertEquals("It froze.", sending.message)
            assertEquals(FeedbackKind.Bug, sending.kind)
            assertEquals(true, sending.screenshot != null)
            assertEquals(0, sending.screenshot?.hiddenRects?.size)
            assertEquals(true, sending.includeLogs)
            assertEquals(FeedbackPage.Compose, sending.page)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("screenshot.jpg", "logs.jsonl.gz"), submitted.single().attachments.map { it.name })
            assertEquals(FeedbackSendStatus.Offline, viewModel.state.value.status)
            viewModel.updateMessage("edited after the failure")
            assertEquals("edited after the failure", viewModel.state.value.message)
        }

    @Test
    fun cooldown_blocksASecondSendWithoutCallingTheSubmitter() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            sendOnce(viewModel)
            assertEquals(1, submitted.size)

            now += 15_000
            viewModel.open(FeedbackScreen.Settings)
            assertEquals("", viewModel.state.value.message)
            viewModel.updateMessage("Another one")
            viewModel.send()
            advanceUntilIdle()

            assertEquals(FeedbackSendStatus.RateLimited(45), viewModel.state.value.status)
            assertEquals(1, submitted.size)
            assertEquals("Another one", viewModel.state.value.message)

            now += 45_000
            viewModel.send()
            advanceUntilIdle()
            assertEquals(2, submitted.size)
        }

    @Test
    fun failures_keepTheDraftAndMapToStates() =
        runTest(dispatcher) {
            val results =
                ArrayDeque(
                    listOf(
                        FeedbackSubmitResult.Offline,
                        FeedbackSubmitResult.RateLimited(60),
                        FeedbackSubmitResult.Unverified,
                        FeedbackSubmitResult.Failed("commit 500"),
                    ),
                )
            val viewModel = viewModel { results.removeFirst() }
            viewModel.open(FeedbackScreen.Settings)
            viewModel.updateMessage("Draft")

            val states =
                List(4) {
                    viewModel.send()
                    advanceUntilIdle()
                    viewModel.state.value.status
                }

            assertEquals(
                listOf(
                    FeedbackSendStatus.Offline,
                    FeedbackSendStatus.RateLimited(60),
                    FeedbackSendStatus.Unverified,
                    FeedbackSendStatus.Failed,
                ),
                states,
            )
            assertEquals("Draft", viewModel.state.value.message)
            assertNull(cooldown.lastSent)
        }

    @Test
    fun unverified_shareInsteadHandsTheReportToTheShareSheet() =
        runTest(dispatcher) {
            val viewModel = viewModel { FeedbackSubmitResult.Unverified }
            viewModel.open(FeedbackScreen.Settings)
            viewModel.updateMessage("Draft")
            viewModel.send()
            advanceUntilIdle()

            viewModel.shareInstead()

            val share = viewModel.state.value.pendingShare
            assertNotNull(share)
            assertTrue(share!!.text.startsWith("Draft"))
            assertEquals(FeedbackSendStatus.Idle, viewModel.state.value.status)
            viewModel.consumeShare()
            assertNull(viewModel.state.value.pendingShare)
        }

    @Test
    fun debugSubmitter_returnsShareInsteadWithTheAttachments() =
        runTest(dispatcher) {
            val viewModel = viewModel(ShareFeedbackSubmitter())
            viewModel.open(FeedbackScreen.SessionChat, capturedJpeg = byteArrayOf(1))
            viewModel.addCapturedScreenshot()
            viewModel.updateMessage("Debug report")
            viewModel.send()
            advanceUntilIdle()

            val share = viewModel.state.value.pendingShare
            assertNotNull(share)
            assertEquals(listOf("screenshot.jpg", "logs.jsonl.gz"), share!!.attachments.map { it.name })
            assertEquals(FeedbackSendStatus.Idle, viewModel.state.value.status)
            assertNull(cooldown.lastSent)
        }

    @Test
    fun dictationInsert_appendsTranscriptToTheMessage() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.open(FeedbackScreen.Settings)
            viewModel.updateMessage("Crash")

            viewModel.beginDictation(languageLabel = "English", onDevice = true)
            assertEquals(
                ComposerDictationPhase.Listening,
                viewModel.state.value.dictation
                    ?.phase,
            )
            assertFalse(viewModel.state.value.canSend)
            viewModel.dictationPartial("when I open", " the diff")
            viewModel.dictationInsert()
            assertEquals(
                ComposerDictationPhase.Finishing,
                viewModel.state.value.dictation
                    ?.phase,
            )
            advanceUntilIdle()

            assertNull(viewModel.state.value.dictation)
            assertEquals("Crash when I open the diff", viewModel.state.value.message)
        }

    @Test
    fun dictation_morphsTheSheetIntoListening() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)
        assertEquals("Report a problem", viewModel.state.value.sheetTitle)
        assertFalse(viewModel.state.value.dictationTakesOverCompose)

        viewModel.beginDictation(languageLabel = "English", onDevice = true)

        assertEquals("Listening", viewModel.state.value.sheetTitle)
        assertTrue(viewModel.state.value.dictationTakesOverCompose)

        viewModel.dictationCancel()

        assertEquals("Report a problem", viewModel.state.value.sheetTitle)
        assertFalse(viewModel.state.value.dictationTakesOverCompose)
    }

    @Test
    fun dictationUnavailable_takesOverWithoutTheListeningTitle() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)

        viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied, "Microphone access is off")

        assertTrue(viewModel.state.value.dictationTakesOverCompose)
        assertEquals("Report a problem", viewModel.state.value.sheetTitle)
    }

    @Test
    fun dictationModelDownload_updatesOnlyAModelCard() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)

        viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloading, 0.3f)
        assertEquals(null, viewModel.state.value.dictation)

        viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled, languageLabel = "English")
        viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloading, 0.3f)
        assertEquals(ComposerDictationPhase.ModelDownloading, viewModel.state.value.dictation?.phase)
        assertEquals(0.3f, viewModel.state.value.dictation?.downloadProgress)
        assertEquals("English", viewModel.state.value.dictation?.languageLabel)

        viewModel.beginDictation(languageLabel = "English", onDevice = true)
        viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloadFailed)
        assertEquals(ComposerDictationPhase.Listening, viewModel.state.value.dictation?.phase)
    }

    @Test
    fun dictationStopped_keepsTheListeningTitle() {
        val viewModel = viewModel()
        viewModel.open(FeedbackScreen.Settings)
        viewModel.beginDictation(languageLabel = "English", onDevice = true)

        viewModel.dictationStopped()

        assertEquals(ComposerDictationPhase.Stopped, viewModel.state.value.dictation?.phase)
        assertEquals("Listening", viewModel.state.value.sheetTitle)
        assertTrue(viewModel.state.value.dictationTakesOverCompose)
    }

    @Test
    fun dismissAfterSent_startsAFreshDraftNextTime() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            sendOnce(viewModel)
            viewModel.dismiss()

            viewModel.open(FeedbackScreen.Settings)

            assertEquals(FeedbackSendStatus.Idle, viewModel.state.value.status)
            assertEquals("", viewModel.state.value.message)
        }

    private fun TestScope.sendOnce(viewModel: FeedbackViewModel) {
        viewModel.open(FeedbackScreen.Settings)
        viewModel.setKind(FeedbackKind.Feedback)
        viewModel.updateMessage("First")
        viewModel.send()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.status is FeedbackSendStatus.Sent)
    }

    private fun viewModel(submit: suspend (FeedbackReport) -> FeedbackSubmitResult = { FeedbackSubmitResult.Sent(it.reference) }) =
        viewModel(
            FeedbackSubmitter { report ->
                submitted += report
                submit(report)
            },
        )

    private fun viewModel(submitter: FeedbackSubmitter) =
        FeedbackViewModel(
            submitter = submitter,
            metadataSource = { screen ->
                FeedbackMetadata(
                    version = "1.1.0",
                    build = "1",
                    osVersion = "Android 16",
                    device = "Pixel",
                    locale = "en-GB",
                    screen = screen,
                )
            },
            cooldownStore = cooldown,
            renderer = renderer,
        ).also { viewModel ->
            viewModel.nowMs = { now }
        }

    private class MemoryCooldownStore : FeedbackCooldownStore {
        var lastSent: Long? = null

        override fun lastSentAtEpochMs(): Long? = lastSent

        override fun recordSent(atEpochMs: Long) {
            lastSent = atEpochMs
        }
    }

    private class FakeRenderer : FeedbackAttachmentRenderer {
        var renderedRects = 0

        override suspend fun screenshot(screenshot: FeedbackScreenshot): ByteArray {
            renderedRects += screenshot.hiddenRects.size
            return screenshot.jpeg
        }

        override suspend fun logs(window: DiagnosticLogExportWindow): ByteArray = byteArrayOf(0x1f, 0x8b.toByte())

        override suspend fun logPreview(window: DiagnosticLogExportWindow): List<String> = listOf("line ${window.minutes}")
    }
}
