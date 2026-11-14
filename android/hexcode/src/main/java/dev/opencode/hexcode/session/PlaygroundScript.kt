package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/**
 * The `playground` sample ([TodaySessionDemo.Playground]), for Try a sample session: a made-up agent that answers
 * what's sent, entirely on the phone. It plays a scripted run through the real [TodaySession]: the same event lines
 * the server would stream, read back by the same code, so home shows it as it would a real run.
 *
 * The first take plays the whole run: busy, the to-do list ticking along, `npm test` to approve (the run waits for the
 * answer; a deny carries on without the tests), a question (it waits again), then idle with a card about what was
 * asked and the report's artifact tile. Each later take plays a short run: busy, its to-dos, the card. Stop ends a run
 * at any point. A message sent while a run goes waits in the inbox and runs after it.
 *
 * Nothing is saved: every store behind it keeps what it has in memory, for this process only.
 */
class PlaygroundScript(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long the script waits between its steps; tests make it 0. */
    private val pace: Long = 1L,
) {
    /** The state as a read-back would see it (after a reconnect), kept beside the event lines. */
    private var busySince: Long? = null
    private var todos: List<TodoItem> = emptyList()
    private val pending = mutableListOf<PendingItem>()
    private val inbox = mutableListOf<InboxEntry>()
    private var shell: ShellRun? = null
    private var reply: String = IDLE_REPLY
    private var runStartedAt: Long = clock() - 600_000
    private var artifactAt: Long? = null
    private var steps: List<RecentStep> = emptyList()

    /** Full runs played: the first take gets the whole script. */
    private var fullRuns = 0
    private var run: Job? = null
    private var approval: CompletableDeferred<ApprovalDecision>? = null
    private var answer: CompletableDeferred<JsonObject?>? = null
    private var messages = 0

    /** The lines for the stream that's open; none while it's closed, since its read-back covers what it missed. */
    private val lines = Channel<String>(Channel.UNLIMITED)
    private var streamOpen = false

    /** A run is going: busy, or waiting for an approval or an answer. */
    val running: Boolean get() = run?.isActive == true

    /** Today's session over this script: setup always finds today's sample session. */
    fun session(today: LocalDate = LocalDate.now()): TodaySession {
        val listing = SessionListing(SESSION, DailySession.title(today), DIRECTORY, clock(), busySince = runStartedAt)
        return TodaySession(
            scope,
            DailySessionSetup(DemoApi(listOf(listing), fails = false), DemoMemory(today)),
            live,
            transport,
            { DIRECTORY },
            { null },
            { today },
            clock,
            summaryStore = DemoSummaryStore(null),
            artifacts = ArtifactsFeature(scope, artifactsApi, NoArtifactsStore, { DIRECTORY }, { null }),
        )
    }

    /** Sends one take: it runs now when idle; while a run goes, a `queue` waits for it and a `steer` joins it. */
    private fun take(
        text: String,
        delivery: String,
    ): String {
        messages += 1
        val id = "msg_sample_$messages"
        emit(inboxEnqueued(id, delivery, text))
        if (running && delivery == QUEUE) {
            inbox += InboxEntry(id, QUEUE, text)
        } else {
            emit(event("session.inbox.delivered") { put("inboxID", id) })
            if (!running) start(text)
        }
        return id
    }

    private fun start(prompt: String) {
        val full = fullRuns == 0
        run =
            scope.launch {
                try {
                    if (full) fullRun(prompt) else shortRun(prompt)
                } finally {
                    shell = null
                }
                // A message that waited for this run goes now.
                val next = inbox.removeFirstOrNull()
                if (next != null) {
                    emit(event("session.inbox.delivered") { put("inboxID", next.id) })
                    start(next.text)
                }
            }
    }

    private fun begin() {
        val now = clock()
        runStartedAt = now
        busySince = now
        emit(event("session.execution.started", created = now) {})
    }

    private suspend fun fullRun(prompt: String) {
        begin()
        val list = FULL_TODOS
        tick(list, done = 0, now = 0, wait = 1_500)
        tick(list, done = 0, now = 0, wait = 2_500)
        steps = listOf(RecentStep(RecentStep.Kind.Edit, "src/App.tsx", RecentOutcome.Edited(24, 3), clock()))
        tick(list, done = 1, now = 1, wait = 3_000)
        steps = listOf(RecentStep(RecentStep.Kind.Edit, "src/Settings.tsx", RecentOutcome.Edited(18, 2), clock())) + steps
        tick(list, done = 2, now = 2, wait = 800)
        // `npm test` to approve: the run waits for the answer.
        val asked = CompletableDeferred<ApprovalDecision>()
        approval = asked
        request(approvalItem())
        val decision = asked.await()
        approval = null
        val allowed = decision != ApprovalDecision.Reject
        if (allowed) {
            val started = clock()
            shell = ShellRun(SHELL, TEST_COMMAND, started)
            emit(shellCreated(started))
            pause(3_000)
            shell = null
            emit(event("shell.exited") { put("id", SHELL) })
            steps = listOf(RecentStep(RecentStep.Kind.Command, TEST_COMMAND, RecentOutcome.Done, clock())) + steps
            tick(list, done = 3, now = 3, wait = 1_200)
        } else {
            // Denied: the tests are dropped from the list, and the run goes on without them.
            steps = listOf(RecentStep(RecentStep.Kind.Command, TEST_COMMAND, RecentOutcome.Denied, clock())) + steps
            tick(list.filterIndexed { index, _ -> index != TESTS }, done = 2, now = 2, wait = 1_200)
        }
        // A question: the run waits again.
        val asking = CompletableDeferred<JsonObject?>()
        answer = asking
        request(questionItem())
        val answers = asking.await()
        answer = null
        val shown = if (allowed) list else list.filterIndexed { index, _ -> index != TESTS }
        tick(shown, done = shown.size - 1, now = shown.size - 1, wait = 2_500)
        tick(shown, done = shown.size, now = -1, wait = 600)
        reply = fullReply(prompt, allowed, scopeOf(answers))
        fullRuns += 1
        finish()
    }

    private suspend fun shortRun(prompt: String) {
        begin()
        val list = SHORT_TODOS
        tick(list, done = 0, now = 0, wait = 1_500)
        tick(list, done = 1, now = 1, wait = 1_800)
        steps = listOf(RecentStep(RecentStep.Kind.Edit, "src/App.tsx", RecentOutcome.Edited(6, 1), clock())) + steps
        tick(list, done = 2, now = 2, wait = 1_500)
        tick(list, done = 3, now = -1, wait = 600)
        reply = shortReply(prompt)
        finish()
    }

    /** The run ends: idle, its report updated (the card's tile), and the card asked for again. */
    private fun finish() {
        val now = clock()
        artifactAt = now
        busySince = null
        emit(event("session.execution.succeeded", created = now) {})
    }

    /** The to-do list with its first [done] items done and item [now] in progress, then [wait]. */
    private suspend fun tick(
        list: List<String>,
        done: Int,
        now: Int,
        wait: Long,
    ) {
        todos =
            list.mapIndexed { index, content ->
                TodoItem(
                    content,
                    when {
                        index < done -> TodoItem.Status.Completed
                        index == now -> TodoItem.Status.InProgress
                        else -> TodoItem.Status.Pending
                    },
                )
            }
        emit(
            event("session.tool.success") {
                putJsonObject("metadata") {
                    putJsonArray("todos") {
                        todos.forEach { todo ->
                            addJsonObject {
                                put("content", todo.content)
                                put("status", wire(todo.status))
                            }
                        }
                    }
                }
            },
        )
        pause(wait)
    }

    private suspend fun pause(ms: Long) = delay(ms * pace)

    private fun request(item: PendingItem) {
        pending += item
        emit(
            if (item.kind == PendingItem.Kind.Approval) {
                event("permission.asked") {
                    put("id", item.id)
                    put("action", "shell")
                    putJsonArray("resources") { add(TEST_COMMAND) }
                    putJsonArray("save") { add("npm *") }
                }
            } else {
                event("form.created") { put("form", formJson(item.id)) }
            },
        )
    }

    /** Stop: the run ends where it is, with whatever it waited for. */
    private fun stopRun(): Boolean {
        val job = run ?: return false
        if (!job.isActive) return false
        job.cancel()
        run = null
        approval = null
        answer = null
        pending.clear()
        busySince = null
        shell = null
        reply = STOPPED_REPLY
        emit(event("session.execution.interrupted", created = clock()) {})
        return true
    }

    private fun emit(line: String) {
        if (streamOpen) lines.trySend(line)
    }

    private fun event(
        type: String,
        created: Long = clock(),
        data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("id", "evt_sample")
            put("created", created)
            put("type", type)
            putJsonObject("data") {
                put("sessionID", SESSION)
                data()
            }
        }.toString()

    private fun inboxEnqueued(
        id: String,
        delivery: String,
        text: String,
    ) = event("session.inbox.enqueued") {
        put("inboxID", id)
        putJsonObject("item") {
            put("delivery", delivery)
            put("type", "user")
            putJsonObject("payload") { put("text", text) }
        }
    }

    private fun shellCreated(at: Long) =
        event("shell.created", created = at) {
            putJsonObject("info") {
                put("id", SHELL)
                put("command", TEST_COMMAND)
                putJsonObject("metadata") { put("sessionID", SESSION) }
            }
        }

    private fun approvalItem() =
        PendingItem(
            PERMISSION,
            SESSION,
            PendingItem.Kind.Approval,
            TEST_COMMAND,
            approval = ApprovalRequest("shell", listOf(TEST_COMMAND), save = listOf("npm *")),
        )

    private fun questionItem() =
        PendingItem(FORM, SESSION, PendingItem.Kind.Question, QUESTION_TEXT, form = QuestionForm.read(formJson(FORM)))

    private val live =
        object : LiveSessionApi {
            override suspend fun snapshot(rootId: String) =
                LiveSnapshot(
                    busySince = busySince,
                    hasRun = true,
                    children = emptyList(),
                    pending = pending.toList(),
                    inbox = inbox.toList(),
                    todos = todos,
                    projectId = "demo-project",
                )

            override suspend fun summarise(
                sessionId: String,
                prompt: String,
            ): String {
                delay(600 * pace)
                return if (running) WORKING_REPLY else reply
            }

            override suspend fun ask(
                sessionId: String,
                prompt: String,
            ): String {
                delay(900 * pace)
                return ASK_REPLY
            }

            override suspend fun switchModel(
                sessionId: String,
                model: ModelChoice,
            ) = Unit

            override suspend fun send(
                sessionId: String,
                text: String,
                delivery: String,
                images: List<PromptImage>,
                metadata: JsonObject?,
            ): String = take(text, delivery)

            override suspend fun cancelQueued(
                sessionId: String,
                itemId: String,
            ) {
                if (inbox.removeAll { it.id == itemId }) emit(event("session.inbox.cancelled") { put("inboxID", itemId) })
            }

            override suspend fun replyPermission(
                sessionId: String,
                requestId: String,
                decision: ApprovalDecision,
                message: String?,
            ) {
                delay(400 * pace)
                val waiting = approval?.takeIf { requestId == PERMISSION } ?: throw RequestGoneException("Sample: no approval")
                pending.removeAll { it.id == requestId }
                emit(event("permission.replied") { put("requestID", requestId) })
                waiting.complete(decision)
            }

            override suspend fun replyForm(
                sessionId: String,
                formId: String,
                answer: JsonObject,
            ) = settleForm(formId, answer)

            override suspend fun dismissForm(
                sessionId: String,
                formId: String,
                message: String,
            ) = settleForm(formId, null)

            override suspend fun interrupt(sessionId: String): Boolean {
                delay(300 * pace)
                return stopRun()
            }

            override suspend fun subagents(rootId: String): Set<String> = emptySet()

            override suspend fun running(
                rootId: String,
                directory: String,
            ) = RunningRead(emptyMap(), emptySet(), listOfNotNull(shell))

            override suspend fun recent(sessionId: String) = steps
        }

    private suspend fun settleForm(
        formId: String,
        answers: JsonObject?,
    ) {
        delay(400 * pace)
        val waiting = answer?.takeIf { formId == FORM } ?: throw RequestGoneException("Sample: no question")
        pending.removeAll { it.id == formId }
        emit(event(if (answers != null) "form.replied" else "form.cancelled") { put("id", formId) })
        waiting.complete(answers)
    }

    /** Opens at once and stays open, with a heartbeat; only what happens while it's open is sent. */
    private val transport =
        EventTransport { onActivity, onLine ->
            while (lines.tryReceive().isSuccess) Unit
            streamOpen = true
            try {
                onActivity()
                onLine("""{"id":"evt_sample","type":"server.connected","data":{}}""")
                while (true) {
                    val line = withTimeoutOrNull(HEARTBEAT_MS) { lines.receive() }
                    onActivity()
                    if (line != null) onLine(line)
                }
                @Suppress("UNREACHABLE_CODE")
                SseAttemptOutcome.Ended
            } finally {
                streamOpen = false
            }
        }

    /** Artifacts on, and one: the report, from the latest run that finished. */
    private val artifactsApi =
        object : ArtifactsApi {
            override suspend fun declared() = true

            override suspend fun list(directory: String) =
                ArtifactListAnswer.On(
                    listOfNotNull(
                        artifactAt?.let { at ->
                            ArtifactInfo(
                                id = TodaySessionDemo.ARTIFACT_ID,
                                provider = "local",
                                sessionId = SESSION,
                                title = ARTIFACT_TITLE,
                                entry = "index.html",
                                url = "${ArtifactInfo.ARTIFACT_PATH}${TodaySessionDemo.ARTIFACT_ID}/view/",
                                created = at,
                                updated = at,
                            )
                        },
                    ),
                )

            override suspend fun config(directory: String): ConfigDocuments? = null

            override suspend fun readFile(path: String) = FileRead.Unknown

            override suspend fun writeFile(
                path: String,
                bytes: ByteArray,
                directory: String,
            ) = false

            override suspend fun putRules(sessionId: String) = Unit

            override suspend fun deleteRules(sessionId: String) = Unit

            override suspend fun runStart(sessionId: String) = runStartedAt

            override suspend fun sessionExists(sessionId: String) = true

            override suspend fun createSetupSession(
                directory: String,
                model: ModelChoice?,
                rules: List<ArtifactPrompts.Rule>,
            ): String = error("Sample: no setup session.")

            override suspend fun prompt(
                sessionId: String,
                text: String,
            ) = error("Sample: no setup session.")

            override suspend fun approvals(sessionId: String) = emptyList<PendingItem>()

            override suspend fun reply(
                sessionId: String,
                requestId: String,
                allow: Boolean,
            ) = Unit
        }

    companion object {
        /** The suggestion chip's take, which needs no speech model and no microphone. */
        const val SUGGESTION = "Add a dark mode toggle"

        const val ARTIFACT_TITLE = "Test report"
        const val TEST_COMMAND = "npm test"

        private const val SESSION = "ses_demo"
        private const val DIRECTORY = "/Users/example/Projects/hexcode"
        private const val PERMISSION = "per_sample"
        private const val FORM = "frm_sample"
        private const val SHELL = "sh_sample"
        private const val QUEUE = "queue"
        private const val HEARTBEAT_MS = 15_000L

        /** Where `Run the tests` is in [FULL_TODOS]; a deny drops it. */
        private const val TESTS = 2

        private val FULL_TODOS = listOf("Look around the project", "Make the change", "Run the tests", "Write a short report")
        private val SHORT_TODOS = listOf("Look at the last change", "Make the change", "Check it still works")

        const val IDLE_REPLY =
            "## Ready when you are\nNow: This is a made-up project, a small to-do app, to try Hex Code on.\n" +
                "Next: $SUGGESTION"

        private const val WORKING_REPLY = "## Working on it\nNow: Making the change you asked for."

        const val STOPPED_REPLY = "## Stopped\nNow: You stopped the run before it finished.\nNext: Say what to do instead."

        private const val ASK_REPLY =
            "This is a sample session, so there's no real agent to ask. With your computer paired, the agent answers " +
                "here without stopping its work."

        private const val QUESTION_TEXT = "How big should the change be?"

        /** The question, in the Needs sample's shape: one choice, then any, each with an own answer. */
        private fun formJson(id: String): JsonObject =
            buildJsonObject {
                put("id", id)
                put("sessionID", SESSION)
                put("title", "Questions")
                put(
                    "fields",
                    buildJsonArray {
                        add(field("q0", "Scope", QUESTION_TEXT, QuestionField.SINGLE, "Small" to "Just what you asked", "Thorough" to null))
                        add(
                            field(
                                "q1",
                                "Report",
                                "What goes in the report?",
                                QuestionField.MULTIPLE,
                                "Test results" to null,
                                "Screenshots" to null,
                            ),
                        )
                    },
                )
            }

        private fun field(
            key: String,
            title: String,
            description: String,
            type: String,
            vararg options: Pair<String, String?>,
        ): JsonElement =
            buildJsonObject {
                put("key", key)
                put("title", title)
                put("description", description)
                put("type", type)
                put("custom", true)
                putJsonArray("options") {
                    options.forEach { (label, about) ->
                        addJsonObject {
                            put("value", label)
                            put("label", label)
                            if (about != null) put("description", about)
                        }
                    }
                }
            }

        private fun wire(status: TodoItem.Status) =
            when (status) {
                TodoItem.Status.Completed -> "completed"
                TodoItem.Status.InProgress -> "in_progress"
                TodoItem.Status.Pending -> "pending"
            }

        /** The first question's answer, a choice or an own answer, for the card. */
        private fun scopeOf(answers: JsonObject?): String? =
            when (val value = answers?.get("q0")) {
                is JsonPrimitive -> value.content
                is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.content
                else -> null
            }?.trim()?.ifEmpty { null }

        /** The card's title: what was asked, short. */
        private fun titleOf(prompt: String): String {
            val line =
                prompt
                    .trim()
                    .lines()
                    .first()
                    .trim()
                    .ifEmpty { "Your change" }
            val short = if (line.length > TITLE_MAX) line.take(TITLE_MAX - 1).trimEnd() + "…" else line
            return short.replaceFirstChar { it.uppercase() }
        }

        private const val TITLE_MAX = 40

        fun fullReply(
            prompt: String,
            testsRan: Boolean,
            scope: String?,
        ): String {
            val chose = scope?.let { " You chose: ${it.lowercase()}." }.orEmpty()
            val now =
                if (testsRan) {
                    "Done. All 12 tests pass, and the report is ready.$chose"
                } else {
                    "Done, but the tests didn't run: you denied `npm test`. The report says so.$chose"
                }
            val next = if (testsRan) "Open the report, or say what to change next." else "Run the tests, then open the report."
            return "## ${titleOf(prompt)}\nNow: $now\nNext: $next"
        }

        fun shortReply(prompt: String): String =
            "## ${titleOf(prompt)}\nNow: Done, on top of the last change. The tests still pass.\nNext: Say what to change next."
    }
}
