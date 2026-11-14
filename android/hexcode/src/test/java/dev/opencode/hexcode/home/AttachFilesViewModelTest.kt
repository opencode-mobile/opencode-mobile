package dev.opencode.hexcode.home

import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.DailySession
import dev.opencode.hexcode.session.DailySessionApi
import dev.opencode.hexcode.session.DailySessionMemory
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.EventTransport
import dev.opencode.hexcode.session.LiveSessionApi
import dev.opencode.hexcode.session.LiveSnapshot
import dev.opencode.hexcode.session.PromptImage
import dev.opencode.hexcode.session.SessionListing
import dev.opencode.hexcode.session.SessionListingPage
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * app-plan.md, Attach files (M7) › Testing: picking and uploading, ✕ during an upload, failures, the limit, the prompt
 * with path lines and `metadata`, a failed send and Undo keeping the uploaded paths, and Set as goal with files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachFilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 8)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Each take's text is the next of [texts]. */
    private class Dictation(
        vararg texts: String,
    ) : dev.opencode.hexcode.home.Dictation {
        val texts = ArrayDeque(texts.toList())
        private var recording = false
        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Ready)

        override fun start(onFailed: (Throwable) -> Unit) {
            recording = true
        }

        override suspend fun end(): FloatArray? = if (recording) FloatArray(16_000).also { recording = false } else null

        override fun drop() {
            recording = false
        }

        override suspend fun transcribe(samples: FloatArray): String = texts.removeFirst()

        override fun reload() = Unit
    }

    /** One message the server got: its text, delivery, images by name and metadata. */
    private data class Sent(
        val text: String,
        val delivery: String,
        val images: List<String>,
        val metadata: JsonObject?,
    )

    private inner class Server :
        DailySessionApi,
        LiveSessionApi,
        DailySessionMemory {
        var busy = false
        var fail = false
        val sent = mutableListOf<Sent>()
        val goals = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun sessionsPage(
            directory: String,
            search: String,
            limit: Int,
            cursor: String?,
        ) = SessionListingPage(listOf(SessionListing("ses_1", DailySession.title(today), "/p", 1, busySince = 1)), null)

        override suspend fun create(
            title: String,
            directory: String,
            model: ModelChoice?,
        ) = SessionListing("ses_1", title, directory, 1)

        override suspend fun putMessageRules(sessionId: String) = Unit

        override suspend fun snapshot(rootId: String) =
            LiveSnapshot(if (busy) 5 else null, hasRun = true, children = emptyList(), pending = emptyList(), inbox = emptyList())

        override suspend fun summarise(
            sessionId: String,
            prompt: String,
        ) = "## Task\nNow: Working."

        override suspend fun send(
            sessionId: String,
            text: String,
            delivery: String,
            images: List<PromptImage>,
            metadata: JsonObject?,
        ): String {
            if (fail) error("The computer can't be reached.")
            sent += Sent(text, delivery, images.map { it.name }, metadata)
            return "msg_${sent.size}"
        }

        override suspend fun goalCommand(
            sessionId: String,
            text: String,
            images: List<PromptImage>,
        ) {
            goals += text
        }

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) {
            cancelled += itemId
        }

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) = Unit

        override suspend fun lastOpenedOn() = today

        override suspend fun setLastOpenedOn(date: LocalDate) = Unit
    }

    private class Stream : EventTransport {
        val lines = Channel<String?>(Channel.UNLIMITED)

        override suspend fun connect(
            onActivity: () -> Unit,
            onLine: suspend (String) -> Unit,
        ): SseAttemptOutcome {
            while (true) {
                val line = lines.receive() ?: return SseAttemptOutcome.Ended
                onActivity()
                onLine(line)
            }
        }

        fun connected() = lines.trySend("""{"type":"server.connected","data":{}}""")
    }

    /**
     * Copies and uploads: a source named "bad…" can't be read; [gates] hold an upload half way; [refuse] answers the
     * upload with that kind of failure. Every upload, cancelled and discarded copy is kept.
     */
    private class Files : FileAttacher {
        var available = true
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val refuse = mutableMapOf<String, Exception>()
        val uploaded = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var discardedAll = 0

        override suspend fun available() = available

        override suspend fun stage(source: String): StagedFile {
            if (source.startsWith("bad")) throw IOException("can't read")
            val mime =
                when (source.substringAfterLast('.')) {
                    "pdf" -> "application/pdf"
                    "zip" -> "application/zip"
                    "csv" -> "text/csv"
                    "mp4" -> "video/mp4"
                    else -> FileNames.OCTET_STREAM
                }
            return StagedFile(File("/cache/attach-files/$source"), source, mime, 1_000)
        }

        override suspend fun upload(
            file: StagedFile,
            onProgress: (sent: Long, total: Long) -> Unit,
        ): String {
            onProgress(0, 1_000)
            gates[file.name]?.let { gate ->
                onProgress(450, 1_000)
                try {
                    gate.await()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    cancelled += file.name
                    throw error
                }
            }
            refuse[file.name]?.let { throw it }
            onProgress(1_000, 1_000)
            uploaded += file.name
            return "/tmp/opencode/uploads/u${uploaded.size}/${file.name}"
        }

        override suspend fun discard(file: StagedFile) {
            discarded += file.name
        }

        override suspend fun discardAll() {
            discardedAll++
        }
    }

    private val server = Server()
    private val stream = Stream()
    private val files = Files()
    private val images =
        object : ImagePreparer {
            override suspend fun prepare(source: String) = PreparedImage(File("/cache/attach/a.jpg"), ImageKind.Screenshot, 10, 10)

            override suspend fun keepOnly(files: Set<File>) = Unit
        }

    private fun TestScope.home(vararg texts: String): HomeViewModel {
        val vm =
            HomeViewModel(Dictation(*texts), images = images, files = files, makeSession = { scope ->
                TodaySession(
                    scope,
                    DailySessionSetup(server, server),
                    server,
                    stream,
                    { "/p" },
                    { null },
                    { today },
                    { testScheduler.currentTime },
                )
            }) { testScheduler.currentTime }
        runCurrent()
        stream.connected()
        runCurrent()
        return vm
    }

    private fun TestScope.dictate(vm: HomeViewModel) {
        vm.press()
        vm.release(TalkRelease.End)
        runCurrent()
    }

    private fun TestScope.done(vm: HomeViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    private fun JsonObject.attachments(): List<String> =
        (this["attachments"] as JsonArray).map { (it.jsonObject["path"] as JsonPrimitive).content }

    @Test
    fun filesIsOfferedWhenTheServerTakesUploadsAndCopiesLeftFromBeforeGo() =
        runTest(dispatcher) {
            val vm = home()
            assertEquals(true, vm.uiState.value.filesAvailable)
            assertEquals(1, files.discardedAll)
            done(vm)
            files.available = false
            val older = home()
            assertEquals(false, older.uiState.value.filesAvailable)
            done(older)
        }

    @Test
    fun pickedFilesUploadThenSendAsPathLinesWithMetadata() =
        runTest(dispatcher) {
            val vm = home("Read these")
            dictate(vm)
            files.gates["report.pdf"] = CompletableDeferred()
            vm.attachFiles(listOf("logs.zip", "report.pdf", "table.csv"))
            runCurrent()
            val uploading = vm.uiState.value.draft
            assertTrue(uploading.isUploading)
            assertFalse("Send reads Uploading", vm.uiState.value.canSend)
            assertEquals(AttachCaption.Uploading(45), AttachCaption.of(uploading, sees = true))
            files.gates.getValue("report.pdf").complete(Unit)
            runCurrent()
            assertTrue(vm.uiState.value.canSend)
            assertEquals("each cached copy goes once uploaded", listOf("logs.zip", "table.csv", "report.pdf"), files.discarded)
            vm.send()
            runCurrent()
            val sent = server.sent.single()
            assertEquals(
                "Read these\nAttached file: `/tmp/opencode/uploads/u1/logs.zip`\nAttached file: `/tmp/opencode/uploads/u3/report.pdf`" +
                    "\nAttached file: `/tmp/opencode/uploads/u2/table.csv`",
                sent.text,
            )
            assertEquals("steer", sent.delivery)
            val metadata = sent.metadata!!
            assertEquals("Read these", (metadata["displayText"] as JsonPrimitive).content)
            assertEquals(JsonArray(emptyList()), metadata["comments"])
            assertEquals(3, metadata.attachments().size)
            val first = (metadata["attachments"] as JsonArray)[0].jsonObject
            assertEquals("logs.zip", (first["name"] as JsonPrimitive).content)
            assertEquals("application/zip", (first["mime"] as JsonPrimitive).content)
            assertTrue(vm.uiState.value.draft.isEmpty)
            done(vm)
        }

    @Test
    fun aDraftOfOnlyFilesSendsOnlyTheLines() =
        runTest(dispatcher) {
            val vm = home()
            vm.attachFiles(listOf("logs.zip"))
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals("Attached file: `/tmp/opencode/uploads/u1/logs.zip`", server.sent.single().text)
            done(vm)
        }

    @Test
    fun nowKeepsTheCorrectionLabelAtTheVeryStart() =
        runTest(dispatcher) {
            server.busy = true
            val vm = home("Use this one")
            dictate(vm)
            vm.attachFiles(listOf("spec.pdf"))
            runCurrent()
            vm.setSendMode(SendMode.Now)
            vm.send()
            advanceTimeBy(Sending.HOLD_MS + 100)
            runCurrent()
            assertEquals("[Correction] Use this one\nAttached file: `/tmp/opencode/uploads/u1/spec.pdf`", server.sent.single().text)
            done(vm)
        }

    @Test
    fun theRemoveButtonCancelsARunningUpload() =
        runTest(dispatcher) {
            val vm = home()
            files.gates["big.zip"] = CompletableDeferred()
            vm.attachFiles(listOf("big.zip"))
            runCurrent()
            val id =
                vm.uiState.value.draft.files
                    .single()
                    .id
            vm.removeFile(id)
            runCurrent()
            assertEquals(listOf("big.zip"), files.cancelled)
            assertEquals(listOf("big.zip"), files.discarded)
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertNull("no notice for a file taken off", vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun clearingTheDraftCancelsItsUploads() =
        runTest(dispatcher) {
            val vm = home()
            files.gates["big.zip"] = CompletableDeferred()
            vm.attachFiles(listOf("big.zip"))
            runCurrent()
            vm.clearDraft()
            runCurrent()
            assertEquals(listOf("big.zip"), files.cancelled)
            done(vm)
        }

    @Test
    fun aFailedUploadLeavesWithItsNameInTheNotice() =
        runTest(dispatcher) {
            val vm = home()
            files.refuse["logs.zip"] = IOException("the upload answered 500")
            vm.attachFiles(listOf("logs.zip"))
            runCurrent()
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertEquals(HomeNotice.UploadFailed, vm.uiState.value.notice)
            assertEquals("logs.zip", vm.uiState.value.noticeDetail)
            assertEquals("a 500 doesn't dim Files", true, vm.uiState.value.filesAvailable)
            assertEquals(listOf("logs.zip"), files.discarded)
            done(vm)
        }

    @Test
    fun aChangedRouteDimsFiles() =
        runTest(dispatcher) {
            val vm = home()
            files.refuse["logs.zip"] = UploadUnavailableException("the upload route answered 404")
            vm.attachFiles(listOf("logs.zip"))
            runCurrent()
            assertEquals(HomeNotice.UploadFailed, vm.uiState.value.notice)
            assertEquals(false, vm.uiState.value.filesAvailable)
            done(vm)
        }

    @Test
    fun aFileThatCantBeReadIsntAdded() =
        runTest(dispatcher) {
            val vm = home()
            vm.attachFiles(listOf("bad.bin"))
            runCurrent()
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertEquals(HomeNotice.FileFailed, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun filesAddOnlyTheRoomLeftWithTheLimitNotice() =
        runTest(dispatcher) {
            val vm = home()
            vm.attachFiles(listOf("a.zip", "b.zip", "c.zip"))
            runCurrent()
            vm.attachFiles(listOf("d.zip", "e.zip"))
            runCurrent()
            assertEquals(
                listOf("a.zip", "b.zip", "c.zip", "d.zip"),
                vm.uiState.value.draft.files
                    .map { it.name },
            )
            assertEquals(HomeNotice.AttachLimit, vm.uiState.value.notice)
            assertFalse("the paperclip shows the notice instead of the sheet", vm.openAttach())
            done(vm)
        }

    @Test
    fun aVideoFromThePhotoPickerIsUploadedAndAnImageIsPrepared() =
        runTest(dispatcher) {
            val vm = home()
            vm.attachMedia(listOf(PickedMedia("clip.mp4", "video/mp4"), PickedMedia("content://shot", "image/png")))
            runCurrent()
            val draft = vm.uiState.value.draft
            assertEquals(1, draft.images.size)
            assertEquals("clip.mp4", draft.files.single().name)
            assertTrue(draft.files.single().video)
            assertEquals(listOf("clip.mp4"), files.uploaded)
            done(vm)
        }

    @Test
    fun aFailedSendBringsTheFilesBackWithoutUploadingAgain() =
        runTest(dispatcher) {
            val vm = home("Look")
            dictate(vm)
            vm.attachFiles(listOf("logs.zip"))
            runCurrent()
            server.fail = true
            vm.send()
            runCurrent()
            assertEquals(HomeNotice.SendFailed, vm.uiState.value.notice)
            val back =
                vm.uiState.value.draft.files
                    .single()
            assertEquals("/tmp/opencode/uploads/u1/logs.zip", back.path)
            server.fail = false
            vm.send()
            runCurrent()
            assertEquals(listOf("logs.zip"), files.uploaded)
            assertEquals(
                listOf("/tmp/opencode/uploads/u1/logs.zip"),
                server.sent
                    .single()
                    .metadata!!
                    .attachments(),
            )
            done(vm)
        }

    @Test
    fun undoOfAQueuedMessageBringsItsFilesBackWithTheirPaths() =
        runTest(dispatcher) {
            server.busy = true
            val vm = home("Also these")
            dictate(vm)
            vm.attachFiles(listOf("logs.zip"))
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals(HomeNotice.Queued, vm.uiState.value.notice)
            assertEquals(
                listOf("logs.zip"),
                vm.uiState.value.queuedFiles["msg_1"]
                    ?.map { it.name },
            )
            vm.noticeAction()
            vm.noticeShown()
            runCurrent()
            assertEquals(listOf("msg_1"), server.cancelled)
            val draft = vm.uiState.value.draft
            assertEquals("Also these", draft.text)
            assertEquals("/tmp/opencode/uploads/u1/logs.zip", draft.files.single().path)
            assertNull(vm.uiState.value.queuedFiles["msg_1"])
            assertEquals("never uploaded again", 1, files.uploaded.size)
            done(vm)
        }

    @Test
    fun setAsGoalSendsTheFilesAsPathLinesAfterTheObjective() =
        runTest(dispatcher) {
            val vm = home("Ship the login screen")
            dictate(vm)
            vm.attachFiles(listOf("spec.pdf"))
            runCurrent()
            vm.goalFromDraft()
            runCurrent()
            assertEquals(listOf("Ship the login screen\nAttached file: `/tmp/opencode/uploads/u1/spec.pdf`"), server.goals)
            assertTrue(vm.uiState.value.draft.isEmpty)
            done(vm)
        }

    @Test
    fun aLoneGoalWordWithFilesIsStillRefused() =
        runTest(dispatcher) {
            val vm = home("pause")
            dictate(vm)
            vm.attachFiles(listOf("spec.pdf"))
            runCurrent()
            vm.goalFromDraft()
            runCurrent()
            assertEquals(HomeNotice.GoalRefusedWord, vm.uiState.value.notice)
            assertTrue(server.goals.isEmpty())
            done(vm)
        }
}
