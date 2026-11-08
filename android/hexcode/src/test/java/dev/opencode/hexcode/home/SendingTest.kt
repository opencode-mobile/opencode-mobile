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
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** app-plan.md, Sending: delivery per agent state, Now held 3 s with Undo, failure keeps the draft, the waiting sheet. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendingTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 6)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Takes whose text is the next of [texts]; [gate] holds the transcription. */
    private class Dictation(
        vararg texts: String,
    ) : dev.opencode.hexcode.home.Dictation {
        val texts = ArrayDeque(texts.toList())
        var gate: CompletableDeferred<Unit>? = null
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

        override suspend fun transcribe(samples: FloatArray): String {
            gate?.await()
            return texts.removeFirst()
        }

        override fun reload() = Unit
    }

    /** The server: today's session, busy or not, and what was sent or cancelled. */
    private inner class Server :
        DailySessionApi,
        LiveSessionApi,
        DailySessionMemory {
        var busy = false
        var fail = false
        val sent = mutableListOf<Pair<String, String>>()

        /** The images of each message sent, by name. */
        val sentImages = mutableListOf<List<String>>()
        val cancelled = mutableListOf<String>()

        /** Holds Cancel's DELETE until completed; [cancelFails] makes it fail. */
        var cancelGate: CompletableDeferred<Unit>? = null
        var cancelFails = false

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
            sent += text to delivery
            sentImages += images.map { it.name }
            return "msg_${sent.size}"
        }

        override suspend fun cancelQueued(
            sessionId: String,
            itemId: String,
        ) {
            cancelGate?.await()
            if (cancelFails) error("The computer can't be reached.")
            cancelled += itemId
        }

        override suspend fun switchModel(
            sessionId: String,
            model: ModelChoice,
        ) = Unit

        override suspend fun lastOpenedOn() = today

        override suspend fun setLastOpenedOn(date: LocalDate) = Unit
    }

    /** Each connection reads the lines the test sends; null ends it. */
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

    /** Prepares each source when its gate (if any) opens; a source starting "bad" can't be read. */
    private class Images : ImagePreparer {
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Every set [keepOnly] was given, oldest first: each sweep deletes every other prepared file. */
        val sweeps = mutableListOf<Set<File>>()
        val kept: Set<File>? get() = sweeps.lastOrNull()

        /** Whether a sweep since the [from]th deleted [file]. */
        fun deleted(
            file: File,
            from: Int,
        ): Boolean = sweeps.drop(from).any { file !in it }

        override suspend fun prepare(source: String): PreparedImage {
            gates[source]?.await()
            if (source.startsWith("bad")) error("Not an image")
            val kind = if ("Screenshot" in source) ImageKind.Screenshot else ImageKind.Photo
            return PreparedImage(File("/cache/attach/$source.jpg"), kind, 2000, 1500)
        }

        override suspend fun keepOnly(files: Set<File>) {
            sweeps += files
        }
    }

    private val server = Server()
    private val stream = Stream()
    private val images = Images()

    /** What the model can do with images; null when it isn't known. */
    private var imageInput: ImageInput? = null

    private fun TestScope.home(vararg texts: String): Pair<HomeViewModel, Dictation> {
        val dictation = Dictation(*texts)
        val vm =
            HomeViewModel(dictation, images = images, imageInput = { imageInput }, makeSession = { scope ->
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
        return vm to dictation
    }

    private fun TestScope.dictate(vm: HomeViewModel) {
        vm.press()
        vm.release(TalkRelease.End)
        runCurrent()
    }

    private fun TestScope.done(vm: HomeViewModel) {
        // The stream's 35 s watchdog would run forever.
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test
    fun toAnIdleAgentItSendsAtOnceAsSteer() =
        runTest(dispatcher) {
            val (vm, _) = home("Run the tests")
            dictate(vm)
            vm.send()
            runCurrent()
            assertEquals(listOf("Run the tests" to "steer"), server.sent)
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertEquals(HomeNotice.SentIdle, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun toAWorkingAgentItQueuesByDefault() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Also check the logs")
            assertTrue(vm.uiState.value.busy)
            dictate(vm)
            vm.send()
            runCurrent()
            assertEquals(listOf("Also check the logs" to "queue"), server.sent)
            assertEquals(HomeNotice.Queued, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun nowIsHeld3sThenSentAsACorrectionAndTheSwitchGoesBack() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Use the old button")
            dictate(vm)
            vm.setSendMode(SendMode.Now)
            vm.send()
            runCurrent()
            assertEquals(SendMode.AfterTurn, vm.uiState.value.sendMode)
            assertEquals(HomeNotice.SendingNow, vm.uiState.value.notice)
            advanceTimeBy(2_900)
            runCurrent()
            assertTrue(server.sent.isEmpty())
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf("[Correction] Use the old button" to "steer"), server.sent)
            assertEquals(HomeNotice.SentNow, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun undoDuringTheHoldSendsNothingAndKeepsTheDraft() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Use the old button")
            dictate(vm)
            vm.setSendMode(SendMode.Now)
            vm.send()
            runCurrent()
            // The toast calls its action, then reports itself dismissed.
            vm.noticeAction()
            vm.noticeShown()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(server.sent.isEmpty())
            assertEquals(HomeNotice.Unsent, vm.uiState.value.notice)
            assertEquals("Use the old button", vm.uiState.value.draft.text)
            done(vm)
        }

    @Test
    fun undoOfAQueuedMessageTakesItBackFromTheInbox() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Also check the logs")
            dictate(vm)
            vm.send()
            runCurrent()
            vm.noticeAction()
            vm.noticeShown()
            runCurrent()
            assertEquals(listOf("msg_1"), server.cancelled)
            assertEquals(HomeNotice.TakenBack, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun sendWaitsForEveryChunk() =
        runTest(dispatcher) {
            val (vm, dictation) = home("One", "Two")
            dictate(vm)
            dictation.gate = CompletableDeferred()
            dictate(vm)
            assertFalse(vm.uiState.value.draft.isSettled)
            vm.send()
            runCurrent()
            assertTrue(server.sent.isEmpty())
            dictation.gate?.complete(Unit)
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals(listOf("One Two" to "steer"), server.sent)
            done(vm)
        }

    @Test
    fun aFailedSendBringsTheDraftBackAsItWas() =
        runTest(dispatcher) {
            server.fail = true
            val (vm, _) = home("One", "Two")
            dictate(vm)
            dictate(vm)
            val before = vm.uiState.value.draft.chunks
            vm.send()
            runCurrent()
            assertEquals(before, vm.uiState.value.draft.chunks)
            assertEquals(HomeNotice.SendFailed, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun aNowHeldThroughAReconnectGoesOnceTheStreamIsBack() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Stop that")
            dictate(vm)
            vm.setSendMode(SendMode.Now)
            vm.send()
            runCurrent()
            // The stream drops during the hold.
            stream.lines.trySend(null)
            runCurrent()
            advanceTimeBy(3_100)
            runCurrent()
            assertTrue(server.sent.isEmpty())
            advanceTimeBy(1_300)
            stream.connected()
            runCurrent()
            assertEquals(listOf("[Correction] Stop that" to "steer"), server.sent)
            done(vm)
        }

    @Test
    fun cancelInTheSheetSpinsWhileTheDeleteRunsAndTheRowLeavesWhenItWorks() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home()
            enqueue("msg_a")
            enqueue("msg_b")
            runCurrent()
            assertEquals(
                listOf("Message msg_a", "Message msg_b"),
                vm.uiState.value.today.queued
                    .map { it.text },
            )
            server.cancelGate = CompletableDeferred()
            vm.cancelQueued("msg_a")
            runCurrent()
            assertEquals(setOf("msg_a"), vm.uiState.value.cancelling)
            assertEquals(2, vm.uiState.value.today.queued.size)
            server.cancelGate?.complete(Unit)
            runCurrent()
            // Gone as soon as the DELETE worked, without waiting for the event.
            assertEquals(listOf("msg_a"), server.cancelled)
            assertEquals(emptySet<String>(), vm.uiState.value.cancelling)
            assertEquals(
                listOf("msg_b"),
                vm.uiState.value.today.queued
                    .map { it.id },
            )
            // The event that follows, and a delivery, change nothing more.
            stream.lines.trySend("""{"type":"session.inbox.cancelled","data":{"sessionID":"ses_1","inboxID":"msg_a"}}""")
            stream.lines.trySend("""{"type":"session.inbox.delivered","data":{"sessionID":"ses_1","inboxID":"msg_b"}}""")
            runCurrent()
            assertTrue(
                vm.uiState.value.today.queued
                    .isEmpty(),
            )
            done(vm)
        }

    @Test
    fun aFailedCancelKeepsTheRowAndStopsTheSpinner() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home()
            enqueue("msg_a")
            runCurrent()
            server.cancelFails = true
            vm.cancelQueued("msg_a")
            runCurrent()
            assertEquals(emptySet<String>(), vm.uiState.value.cancelling)
            assertEquals(
                listOf("msg_a"),
                vm.uiState.value.today.queued
                    .map { it.id },
            )
            assertNull(vm.uiState.value.notice)
            done(vm)
        }

    private fun enqueue(id: String) =
        stream.lines.trySend(
            """{"type":"session.inbox.enqueued","data":{"sessionID":"ses_1","inboxID":"$id",""" +
                """"item":{"type":"user","payload":{"text":"Message $id"},"delivery":"queue"}}}""",
        )

    @Test
    fun theDeliveryRules() {
        assertEquals(SendPlan.Idle, Sending.plan(busy = false, mode = SendMode.Now))
        assertEquals(SendPlan.Queue, Sending.plan(busy = true, mode = SendMode.AfterTurn))
        assertEquals(SendPlan.Now, Sending.plan(busy = true, mode = SendMode.Now))
        assertEquals("steer", Sending.delivery(SendPlan.Idle))
        assertEquals("queue", Sending.delivery(SendPlan.Queue))
        assertEquals("steer", Sending.delivery(SendPlan.Now))
        assertEquals("[Correction] Fix it", Sending.text(SendPlan.Now, "Fix it"))
        assertEquals("Fix it", Sending.text(SendPlan.Queue, "Fix it"))
    }

    @Test
    fun imagesGoInTheSamePromptForIdleQueueAndNow() =
        runTest(dispatcher) {
            val (vm, _) = home("Idle one", "Queued one", "Now one")
            dictate(vm)
            vm.attach(listOf("photo", "Screenshot_1"))
            runCurrent()
            assertEquals("2 images", 2, vm.uiState.value.draft.images.size)
            vm.send()
            runCurrent()
            server.busy = true
            stream.lines.trySend("""{"type":"session.execution.started","created":5,"data":{"sessionID":"ses_1"}}""")
            runCurrent()
            dictate(vm)
            vm.attach(listOf("photo"))
            runCurrent()
            vm.send()
            runCurrent()
            dictate(vm)
            vm.attach(listOf("photo"))
            runCurrent()
            vm.setSendMode(SendMode.Now)
            vm.send()
            advanceTimeBy(3_100)
            runCurrent()
            assertEquals(
                listOf("Idle one" to "steer", "Queued one" to "queue", "[Correction] Now one" to "steer"),
                server.sent,
            )
            assertEquals(listOf(listOf("Photo 1.jpg", "Screenshot 1.jpg"), listOf("Photo 1.jpg"), listOf("Photo 1.jpg")), server.sentImages)
            // The images left the draft with the text.
            assertTrue(vm.uiState.value.draft.isEmpty)
            done(vm)
        }

    @Test
    fun sendWaitsForImagesBeingPreparedAndADraftOfOnlyImagesSendsEmptyText() =
        runTest(dispatcher) {
            val (vm, _) = home()
            images.gates["photo"] = CompletableDeferred()
            vm.attach(listOf("photo"))
            runCurrent()
            assertTrue(vm.uiState.value.draft.isPreparing)
            assertFalse(vm.uiState.value.canSend)
            vm.send()
            runCurrent()
            assertTrue(server.sent.isEmpty())
            images.gates["photo"]?.complete(Unit)
            runCurrent()
            assertTrue(vm.uiState.value.canSend)
            vm.send()
            runCurrent()
            assertEquals(listOf("" to "steer"), server.sent)
            assertEquals(listOf(listOf("Photo 1.jpg")), server.sentImages)
            // The notice says "1 image" in place of the (empty) text.
            assertEquals(HomeNotice.SentIdle, vm.uiState.value.notice)
            assertNull(vm.uiState.value.noticeDetail)
            assertEquals(1, vm.uiState.value.noticeImages)
            done(vm)
        }

    @Test
    fun theFifthImageIsLeftOutWithTheLimitNotice() =
        runTest(dispatcher) {
            val (vm, _) = home()
            vm.attach(listOf("a", "b", "c"))
            runCurrent()
            assertNull(vm.uiState.value.notice)
            vm.attach(listOf("d", "e"))
            runCurrent()
            assertEquals(4, vm.uiState.value.draft.images.size)
            assertEquals(HomeNotice.AttachLimit, vm.uiState.value.notice)
            vm.noticeShown()
            // With 4 on the draft the paperclip doesn't open the sheet: it says so.
            assertFalse(vm.openAttach())
            assertEquals(HomeNotice.AttachLimit, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun anImageThatCanNotBeReadLeavesWithANotice() =
        runTest(dispatcher) {
            val (vm, _) = home()
            vm.attach(listOf("photo", "bad"))
            runCurrent()
            assertEquals(1, vm.uiState.value.draft.images.size)
            assertEquals(HomeNotice.AttachFailed, vm.uiState.value.notice)
            done(vm)
        }

    @Test
    fun toAModelThatCanNotSeeImagesOnlyTheTextGoesWithANotice() =
        runTest(dispatcher) {
            imageInput = ImageInput("Beam Free", sees = false)
            val (vm, _) = home("What letters are in this image?")
            assertFalse(vm.uiState.value.seesImages)
            dictate(vm)
            vm.attach(listOf("photo"))
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals(listOf("What letters are in this image?" to "steer"), server.sent)
            assertEquals(listOf(emptyList<String>()), server.sentImages)
            assertEquals(HomeNotice.SentNoImages, vm.uiState.value.notice)
            // Queued, the notice keeps its Undo.
            vm.noticeShown()
            server.busy = true
            stream.lines.trySend("""{"type":"session.execution.started","created":5,"data":{"sessionID":"ses_1"}}""")
            runCurrent()
            vm.addToDraft("Again")
            vm.attach(listOf("photo"))
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals(HomeNotice.QueuedNoImages, vm.uiState.value.notice)
            vm.noticeAction()
            vm.noticeShown()
            runCurrent()
            assertEquals(listOf("msg_2"), server.cancelled)
            assertEquals(1, vm.uiState.value.draft.images.size)
            done(vm)
        }

    @Test
    fun anUnknownModelCountsAsSeeingImages() =
        runTest(dispatcher) {
            imageInput = null
            val (vm, _) = home()
            assertTrue(vm.uiState.value.seesImages)
            done(vm)
        }

    @Test
    fun undoOfAQueuedMessageBringsItsImagesBackOntoTheDraft() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Also look at this")
            dictate(vm)
            vm.attach(listOf("photo", "Screenshot_1"))
            runCurrent()
            val before = vm.uiState.value.draft.images
            vm.send()
            runCurrent()
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertEquals(before, vm.uiState.value.queuedImages["msg_1"])
            vm.noticeAction()
            vm.noticeShown()
            runCurrent()
            assertEquals(HomeNotice.TakenBack, vm.uiState.value.notice)
            assertEquals("Also look at this", vm.uiState.value.draft.text)
            assertEquals(before, vm.uiState.value.draft.images)
            assertTrue(
                vm.uiState.value.queuedImages
                    .isEmpty(),
            )
            // Their files were never deleted: held for Waiting to send and Undo, then on the draft again.
            assertTrue(before.mapNotNull { it.file }.none { images.deleted(it, 1) })
            done(vm)
        }

    @Test
    fun aFailedSendBringsTheImagesBackAndKeepsTheirFiles() =
        runTest(dispatcher) {
            server.fail = true
            val (vm, _) = home("One")
            dictate(vm)
            vm.attach(listOf("photo"))
            runCurrent()
            val before = vm.uiState.value.draft.images
            vm.send()
            runCurrent()
            assertEquals(before, vm.uiState.value.draft.images)
            assertEquals(HomeNotice.SendFailed, vm.uiState.value.notice)
            assertFalse(images.deleted(File("/cache/attach/photo.jpg"), 1))
            done(vm)
        }

    @Test
    fun aQueuedMessagesImagesStayUntilItIsDeliveredThenTheirFilesGo() =
        runTest(dispatcher) {
            server.busy = true
            val (vm, _) = home("Queued")
            dictate(vm)
            vm.attach(listOf("photo"))
            runCurrent()
            vm.send()
            runCurrent()
            stream.lines.trySend(
                """{"type":"session.inbox.enqueued","data":{"sessionID":"ses_1","inboxID":"msg_1",""" +
                    """"item":{"type":"user","payload":{"text":"Queued",""" +
                    """"files":[{"data":"/9j/","mime":"image/jpeg","name":"Photo 1.jpg"}]},""" +
                    """"delivery":"queue"}}}""",
            )
            runCurrent()
            vm.noticeShown()
            runCurrent()
            assertEquals(
                1,
                vm.uiState.value.queuedImages["msg_1"]
                    ?.size,
            )
            assertEquals(
                1,
                vm.uiState.value.today.queued
                    .single()
                    .images,
            )
            // Kept for Waiting to send from the moment it was sent (the launch's sweep came before it existed).
            assertFalse(images.deleted(File("/cache/attach/photo.jpg"), 1))
            stream.lines.trySend("""{"type":"session.inbox.delivered","data":{"sessionID":"ses_1","inboxID":"msg_1"}}""")
            runCurrent()
            assertTrue(
                vm.uiState.value.queuedImages
                    .isEmpty(),
            )
            assertEquals(emptySet<File>(), images.kept)
            done(vm)
        }

    @Test
    fun aRemovedOrClearedImageHasItsFileDeleted() =
        runTest(dispatcher) {
            val (vm, _) = home("One")
            dictate(vm)
            vm.attach(listOf("a", "b"))
            runCurrent()
            vm.removeImage(
                vm.uiState.value.draft.images[0]
                    .id,
            )
            runCurrent()
            assertEquals(setOf(File("/cache/attach/b.jpg")), images.kept)
            vm.clearDraft()
            runCurrent()
            assertEquals(emptySet<File>(), images.kept)
            done(vm)
        }
}
