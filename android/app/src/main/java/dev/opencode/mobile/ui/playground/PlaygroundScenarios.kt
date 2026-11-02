package dev.opencode.mobile.ui.playground

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.NoticeModelRef
import dev.opencode.mobile.domain.message.PromptSkill
import dev.opencode.mobile.domain.message.SessionFailure
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.ui.playground.PlaygroundContent.ROOT_SESSION_ID
import dev.opencode.mobile.ui.playground.PlaygroundContent.WORKTREE
import dev.opencode.mobile.ui.playground.PlaygroundContent.assistant
import dev.opencode.mobile.ui.playground.PlaygroundContent.bash
import dev.opencode.mobile.ui.playground.PlaygroundContent.edit
import dev.opencode.mobile.ui.playground.PlaygroundContent.image
import dev.opencode.mobile.ui.playground.PlaygroundContent.read
import dev.opencode.mobile.ui.playground.PlaygroundContent.reasoning
import dev.opencode.mobile.ui.playground.PlaygroundContent.session
import dev.opencode.mobile.ui.playground.PlaygroundContent.subagentTask
import dev.opencode.mobile.ui.playground.PlaygroundContent.text
import dev.opencode.mobile.ui.playground.PlaygroundContent.tool
import dev.opencode.mobile.ui.playground.PlaygroundContent.userMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The groups the scenario sheet lists, in order. */
enum class PlaygroundScenarioGroup(
    val title: String,
) {
    Messages("Messages"),
    Live("Live"),
    Pills("Pills"),
    Sessions("Sessions"),
}

/** The scenarios in the scenario sheet, in sheet order. */
enum class PlaygroundScenarioId(
    val code: String,
    val title: String,
    val summary: String,
    val group: PlaygroundScenarioGroup,
) {
    LongConversation("M1", "Long conversation", "Fourteen finished turns to scroll through", PlaygroundScenarioGroup.Messages),
    UserMessages("M2", "User messages", "Short, long, images, a PDF, a skill and a shell command", PlaygroundScenarioGroup.Messages),
    AgentReplies(
        "M3",
        "Agent replies",
        "Markdown, reasoning, every tool state, a diff, todos, a subagent",
        PlaygroundScenarioGroup.Messages,
    ),
    NoticesAndFailures(
        "M4",
        "Notices & failures",
        "Changes, compaction, an interrupt, a failure, a retry",
        PlaygroundScenarioGroup.Messages,
    ),
    EmptySession("M5", "Empty session", "No messages yet", PlaygroundScenarioGroup.Messages),
    StreamingTurn("L1", "Streaming turn", "Reasoning, tools, a diff and tests, then the reply streams", PlaygroundScenarioGroup.Live),
    FollowUps("L2", "Follow-ups while running", "A steer is delivered; two queued prompts run after", PlaygroundScenarioGroup.Live),
    Subagents("L3", "Subagents", "Two run, one asks permission, both finish", PlaygroundScenarioGroup.Live),
    AbortAndRetry("L4", "Abort & retry", "Stop interrupts the reply; the next prompt retries", PlaygroundScenarioGroup.Live),
    IdleOnBranch("P1", "Idle on a branch", "Branch +128 −14 and todos 3/5, nothing at the bottom", PlaygroundScenarioGroup.Pills),
    CleanWorktree("P2", "Clean worktree", "A worktree checkout without changes: its icon only", PlaygroundScenarioGroup.Pills),
    RunningQueued("P3", "Running + queued", "“2 queued” during a running turn", PlaygroundScenarioGroup.Pills),
    BackgroundWork("P4", "Background work", "“2 subagents” counter", PlaygroundScenarioGroup.Pills),
    NeedsInput("P5", "Needs input", "A subagent permission and a question, collapsed", PlaygroundScenarioGroup.Pills),
    SideQuestionReady("P6", "Side question ready", "A /btw answer waits while a turn runs", PlaygroundScenarioGroup.Pills),
    RequestFailed("P7", "Request failed", "The error pill jumps to the failure", PlaygroundScenarioGroup.Pills),
    BusyCorner("P8", "Busy corner", "Five items: one pill plus “+4”", PlaygroundScenarioGroup.Pills),
    JumpToLatest("P9", "Jump to latest", "Scroll up: three messages arrive below", PlaygroundScenarioGroup.Pills),
    KeyboardUp("P10", "Keyboard up", "Tap the composer: compact pill, other corners hide", PlaygroundScenarioGroup.Pills),
    FullLifecycle("P11", "Full lifecycle", "Send, queue, subagents, input, finish, queue runs", PlaygroundScenarioGroup.Pills),
    ChildSession("S1", "Child session", "Inside a subagent, composer locked", PlaygroundScenarioGroup.Sessions),
    ;

    /** "M1 Long conversation". */
    val label: String get() = "$code $title"
}

/** One change to the world, [afterMs] after the previous frame. */
data class PlaygroundFrame(
    val afterMs: Long,
    val apply: (state: PlaygroundState, nowMs: Long) -> PlaygroundState,
)

/** One scripted step: a single change, or several frames such as text streaming in. */
data class PlaygroundStep(
    val label: String,
    val frames: List<PlaygroundFrame>,
) {
    constructor(
        afterMs: Long,
        label: String,
        apply: (state: PlaygroundState, nowMs: Long) -> PlaygroundState,
    ) : this(label, listOf(PlaygroundFrame(afterMs, apply)))

    init {
        require(frames.isNotEmpty()) { "A step has at least one frame" }
    }

    /** Every frame at once, as Next or a test runs it. */
    fun apply(
        state: PlaygroundState,
        nowMs: Long,
    ): PlaygroundState = frames.fold(state) { acc, frame -> frame.apply(acc, nowMs) }
}

data class PlaygroundScenario(
    val id: PlaygroundScenarioId,
    val initial: PlaygroundState,
    /** The session the chat opens; a child session for [PlaygroundScenarioId.ChildSession]. */
    val boundSessionId: String = ROOT_SESSION_ID,
    val steps: List<PlaygroundStep> = emptyList(),
    /** What the capsule says before the first step runs. */
    val hint: String? = null,
    /** Collapse the permission panel to its pill once it shows. */
    val collapsesPermission: Boolean = false,
    /** `/btw` questions answered before the chat opens, so they wait as "ready". */
    val readySideQuestions: List<String> = emptyList(),
    /** `/btw` questions asked at open that never answer, so they stay running. */
    val runningSideQuestions: List<String> = emptyList(),
) {
    val isScripted: Boolean get() = steps.isNotEmpty()

    val boundSession get() = requireNotNull(initial.session(boundSessionId))

    /** The state after every step ran, as the script leaves it. */
    fun finalState(nowMs: Long): PlaygroundState = steps.fold(initial) { state, step -> step.apply(state, nowMs) }
}

object PlaygroundScenarios {
    private const val MINUTE = 60_000L

    /** Streamed text grows by this many words a frame, one frame every [STREAM_FRAME_MS]. */
    private const val STREAM_WORDS_PER_FRAME = 3
    private const val STREAM_FRAME_MS = 90L

    /** How long the retry countdown in Abort & retry waits. */
    const val RETRY_WAIT_MS = 5_000L

    fun build(
        id: PlaygroundScenarioId,
        nowMs: Long,
    ): PlaygroundScenario =
        when (id) {
            PlaygroundScenarioId.LongConversation -> longConversation(nowMs)
            PlaygroundScenarioId.UserMessages -> userMessages(nowMs)
            PlaygroundScenarioId.AgentReplies -> agentReplies(nowMs)
            PlaygroundScenarioId.NoticesAndFailures -> noticesAndFailures(nowMs)
            PlaygroundScenarioId.EmptySession -> emptySession(nowMs)
            PlaygroundScenarioId.StreamingTurn -> streamingTurn(nowMs)
            PlaygroundScenarioId.FollowUps -> followUps(nowMs)
            PlaygroundScenarioId.Subagents -> subagents(nowMs)
            PlaygroundScenarioId.AbortAndRetry -> abortAndRetry(nowMs)
            PlaygroundScenarioId.IdleOnBranch -> idleOnBranch(nowMs)
            PlaygroundScenarioId.CleanWorktree -> cleanWorktree(nowMs)
            PlaygroundScenarioId.RunningQueued -> runningQueued(nowMs)
            PlaygroundScenarioId.BackgroundWork -> backgroundWork(nowMs)
            PlaygroundScenarioId.NeedsInput -> needsInput(nowMs)
            PlaygroundScenarioId.SideQuestionReady -> sideQuestionReady(nowMs)
            PlaygroundScenarioId.RequestFailed -> requestFailed(nowMs)
            PlaygroundScenarioId.BusyCorner -> busyCorner(nowMs)
            PlaygroundScenarioId.JumpToLatest -> jumpToLatest(nowMs)
            PlaygroundScenarioId.KeyboardUp -> keyboardUp(nowMs)
            PlaygroundScenarioId.FullLifecycle -> fullLifecycle(nowMs)
            PlaygroundScenarioId.ChildSession -> childSession(nowMs)
        }

    // region Shared pieces

    private const val CHILD_AUDIT_CHECKOUT = "pg-child-checkout"
    private const val CHILD_AUDIT_CART = "pg-child-cart"
    private const val AUDIT_PROMPT = "Audit the checkout and the cart totals in parallel."

    private fun state(
        title: String,
        nowMs: Long,
        messages: List<Message>,
        children: List<Session> = emptyList(),
        childMessages: Map<String, List<Message>> = emptyMap(),
        directory: String = WORKTREE,
    ) = PlaygroundState(
        rootSessionId = ROOT_SESSION_ID,
        sessions = listOf(session(ROOT_SESSION_ID, title, nowMs, agentName = PlaygroundContent.AGENT, directory = directory)) + children,
        messages = mapOf(ROOT_SESSION_ID to messages) + childMessages,
    )

    private fun auditChildren(nowMs: Long) =
        listOf(
            session(CHILD_AUDIT_CHECKOUT, "Audit the checkout flow", nowMs, parentId = ROOT_SESSION_ID, agentName = "explore"),
            session(CHILD_AUDIT_CART, "Audit the cart totals", nowMs, parentId = ROOT_SESSION_ID, agentName = "general"),
        )

    private fun childTranscript(
        childId: String,
        prompt: String,
        nowMs: Long,
        running: Boolean,
    ): List<Message> {
        val start = nowMs - 2 * MINUTE
        return listOf(
            userMessage("$childId-u", prompt, start),
            assistant(
                id = "$childId-a",
                sentAtMs = start + 2_000,
                completedAtMs = if (running) null else nowMs,
                parts =
                    listOfNotNull(
                        read("$childId-t1", "src/checkout/CheckoutForm.tsx", start + 3_000),
                        tool(
                            id = "$childId-t2",
                            name = "grep",
                            status = ToolStatus.Completed,
                            startMs = start + 6_000,
                            input = buildJsonObject { put("pattern", "applyCoupon") },
                            output = "src/checkout/coupon.ts:12\nsrc/checkout/CheckoutForm.tsx:48",
                        ),
                        if (running) {
                            bash("$childId-t3", "npm run test -- checkout", ToolStatus.Running, start + 9_000)
                        } else {
                            text("$childId-x", "No problems found. The coupon is applied once, after the member discount.")
                        },
                    ),
            ),
        )
    }

    /**
     * The parent turn that runs both audits from [start]. Each audit's task finishes on its own;
     * the reply completes when both did. [replyAfterMs] is how long after the prompt the reply starts.
     */
    private fun auditTurn(
        start: Long,
        nowMs: Long,
        checkoutDone: Boolean,
        cartDone: Boolean,
        replyAfterMs: Long = 3_000,
    ): List<Message> {
        val done = checkoutDone && cartDone
        return listOf(
            userMessage("pg-audit-u", AUDIT_PROMPT, start),
            assistant(
                id = "pg-audit-a",
                sentAtMs = start + replyAfterMs,
                completedAtMs = if (done) nowMs else null,
                parts =
                    listOfNotNull(
                        text("pg-audit-x1", "I'll run two subagents, one per area."),
                        subagentTask(
                            "pg-audit-t1",
                            CHILD_AUDIT_CHECKOUT,
                            "explore",
                            "Audit the checkout flow",
                            if (checkoutDone) ToolStatus.Completed else ToolStatus.Running,
                            start + replyAfterMs + 2_000,
                            output = if (checkoutDone) "Checkout is fine: one coupon, applied after the member discount." else null,
                        ),
                        subagentTask(
                            "pg-audit-t2",
                            CHILD_AUDIT_CART,
                            "general",
                            "Audit the cart totals",
                            if (cartDone) ToolStatus.Completed else ToolStatus.Running,
                            start + replyAfterMs + 2_500,
                            output = if (cartDone) "Cart totals round once, at the end. No drift found." else null,
                        ),
                        if (done) text("pg-audit-x2", "Both audits are done. Nothing to fix.") else null,
                    ),
            ),
        )
    }

    private fun PlaygroundState.withAuditTurn(
        start: Long,
        nowMs: Long,
        checkoutDone: Boolean,
        cartDone: Boolean,
        replyAfterMs: Long = 3_000,
    ): PlaygroundState {
        // Keep each message's time from when it first landed.
        val withTurn =
            auditTurn(start, nowMs, checkoutDone, cartDone, replyAfterMs).fold(this) { acc, message ->
                val previous = acc.messages(ROOT_SESSION_ID).firstOrNull { it.id == message.id }
                acc.upsert(ROOT_SESSION_ID, previous?.let { message.copy(sentAtEpochMs = it.sentAtEpochMs) } ?: message)
            }
        return withTurn
            .copy(
                messages =
                    withTurn.messages +
                        (
                            CHILD_AUDIT_CHECKOUT to
                                childTranscript(CHILD_AUDIT_CHECKOUT, "Audit the checkout flow", nowMs, running = !checkoutDone)
                        ) +
                        (CHILD_AUDIT_CART to childTranscript(CHILD_AUDIT_CART, "Audit the cart totals", nowMs, running = !cartDone)),
            ).withActivity(ROOT_SESSION_ID, if (checkoutDone && cartDone) SessionActivity.Idle else SessionActivity.Running)
            .withActivity(CHILD_AUDIT_CHECKOUT, if (checkoutDone) SessionActivity.Idle else SessionActivity.Running)
            .withActivity(CHILD_AUDIT_CART, if (cartDone) SessionActivity.Idle else SessionActivity.Running)
    }

    /** The audit running for three minutes after the base history. */
    private fun auditRunning(
        title: String,
        nowMs: Long,
    ) = state(title, nowMs, PlaygroundContent.baseHistory(nowMs), auditChildren(nowMs))
        .withAuditTurn(nowMs - 3 * MINUTE, nowMs, checkoutDone = false, cartDone = false)

    /** When the audit prompt was sent. */
    private fun PlaygroundState.auditStart(): Long = messages(ROOT_SESSION_ID).firstOrNull { it.id == "pg-audit-u" }?.sentAtEpochMs ?: 0L

    /** A turn in progress after the base history: streaming text and a running command. */
    private fun runningTurn(nowMs: Long): List<Message> {
        val start = nowMs - 40_000
        return listOf(
            userMessage("pg-run-u", "Now write tests for expired coupon codes.", start),
            assistant(
                id = "pg-run-a",
                sentAtMs = start + 2_000,
                completedAtMs = null,
                parts =
                    listOf(
                        reasoning("pg-run-r", "Expired codes need a fixed clock, so the test can pin `Date.now()`.", start + 3_000),
                        read("pg-run-t1", "src/checkout/coupon.ts", start + 8_000),
                        text("pg-run-x", "Writing the tests now. I'll pin the clock so the expiry check is stable", streaming = true),
                        bash("pg-run-t2", "npm run test -- coupon", ToolStatus.Running, start + 20_000),
                    ),
            ),
        )
    }

    private fun queued(
        id: String,
        text: String,
        nowMs: Long,
    ) = userMessage(id, text, nowMs, delivery = FollowUpDelivery.Queue, waitingForPickup = true)

    /** An assistant reply that is still running and has nothing to show yet. */
    private fun working(
        id: String,
        nowMs: Long,
    ) = assistant(id, nowMs, emptyList(), completedAtMs = null)

    private fun PlaygroundState.complete(
        messageId: String,
        nowMs: Long,
    ) = updateMessage(ROOT_SESSION_ID, messageId) { message ->
        PlaygroundContent
            .withParts(
                message,
                message.parts.map { part ->
                    when (part) {
                        is MessagePart.Text -> part.copy(isStreaming = false)
                        is MessagePart.Reasoning -> part.copy(isStreaming = false, timeEndEpochMs = part.timeEndEpochMs ?: nowMs)
                        else -> part
                    }
                },
            ).copy(completedAtEpochMs = nowMs)
    }

    private fun PlaygroundState.toolStatus(
        messageId: String,
        partId: String,
        status: ToolStatus,
        nowMs: Long,
        output: String? = null,
    ) = updatePart(ROOT_SESSION_ID, messageId, partId) { part ->
        val tool = part as MessagePart.Tool
        tool.copy(
            status = status,
            output = output ?: tool.output,
            timeEndEpochMs = if (status == ToolStatus.Completed || status == ToolStatus.Error) nowMs else null,
        )
    }

    /**
     * A step that streams [full] into one text or reasoning part, a few words a frame, through the
     * same message updates a server sends. The part stays streaming; a later step settles it.
     */
    private fun streamed(
        label: String,
        full: String,
        afterMs: Long,
        write: (state: PlaygroundState, text: String, nowMs: Long) -> PlaygroundState,
    ): PlaygroundStep {
        val words = Regex("""\S+\s*""").findAll(full).map { it.value }.toList()
        val frames =
            words.chunked(STREAM_WORDS_PER_FRAME).indices.map { index ->
                val shown = words.take((index + 1) * STREAM_WORDS_PER_FRAME).joinToString("").trimEnd()
                PlaygroundFrame(if (index == 0) afterMs else STREAM_FRAME_MS) { state, now -> write(state, shown, now) }
            }
        return PlaygroundStep(label, frames)
    }

    private fun streamText(
        label: String,
        messageId: String,
        full: String,
        afterMs: Long = 500,
    ) = streamed(label, full, afterMs) { state, shown, _ ->
        state.upsertPart(ROOT_SESSION_ID, messageId, text("$messageId-x", shown, streaming = true))
    }

    // endregion

    // region Messages

    private fun longConversation(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.LongConversation,
            initial = state("Cart cleanup", nowMs, PlaygroundContent.longHistory(nowMs)),
        )

    private fun userMessages(nowMs: Long): PlaygroundScenario {
        val start = nowMs - 30 * MINUTE
        var minute = 0

        fun at(): Long = start + (minute++) * MINUTE

        fun ack(
            id: String,
            reply: String,
        ) = assistant(id, at(), listOf(text("$id-x", reply)))
        val messages =
            listOf(
                userMessage("pg-m2-u1", "Add a coupon code field.", at()),
                ack("pg-m2-a1", "Added it under the order summary."),
                userMessage(
                    "pg-m2-u2",
                    "Match the spacing of the email field above it, keep the Apply button inside the field on wide " +
                        "screens and below it on phones, and use the same error style as the card number field when a " +
                        "code is not valid. The copy is in the design file; ask me if anything is unclear before you " +
                        "change shared components.",
                    at(),
                ),
                ack("pg-m2-a2", "Understood. I'll leave the shared components alone."),
                userMessage("pg-m2-u3", "Three things:\n1. Trim spaces\n2. Upper-case the code\n3. Keep the old export", at()),
                ack("pg-m2-a3", "All three are in."),
                userMessage("pg-m2-u4", "Here is the design.", at(), files = listOf(image("pg-m2-u4-img", "coupon-row.png", 0))),
                ack("pg-m2-a4", "The row now matches the design."),
                userMessage(
                    "pg-m2-u5",
                    "And the three states:",
                    at(),
                    files =
                        listOf(
                            image("pg-m2-u5-img1", "valid.png", 0),
                            image("pg-m2-u5-img2", "expired.png", 1),
                            image("pg-m2-u5-img3", "unknown.png", 2),
                        ),
                ),
                ack("pg-m2-a5", "Each state has its own message now."),
                userMessage(
                    "pg-m2-u6",
                    "The stacking rules are in the attached spec.",
                    at(),
                    files = listOf(PlaygroundContent.pdf("pg-m2-u6-pdf", "coupon-rules.pdf")),
                ),
                ack("pg-m2-a6", "The coupon now applies after the member discount, as the spec says."),
                userMessage(
                    "pg-m2-u7",
                    "/review src/checkout",
                    at(),
                    skills =
                        listOf(
                            PromptSkill(id = "skill-review", name = "review", mentionText = "/review", mentionStart = 0, mentionEnd = 7),
                        ),
                ),
                ack("pg-m2-a7", "The review found nothing to fix."),
            ) +
                PlaygroundContent.shellCommand(
                    "pg-m2-sh",
                    "git status --short",
                    " M src/checkout/CheckoutForm.tsx\n?? src/checkout/coupon.ts\n?? src/checkout/coupon.test.ts",
                    at(),
                )
        return PlaygroundScenario(id = PlaygroundScenarioId.UserMessages, initial = state("Coupon code field", nowMs, messages))
    }

    private fun agentReplies(nowMs: Long): PlaygroundScenario {
        val start = nowMs - 25 * MINUTE
        val child = session("pg-child-replies", "Find coupon call sites", nowMs, ROOT_SESSION_ID, "explore")
        val messages =
            PlaygroundContent.baseHistory(nowMs) +
                listOf(
                    userMessage("pg-m3-u1", "How does the coupon validation work now?", start),
                    assistant(
                        "pg-m3-a1",
                        start + 5_000,
                        listOf(
                            reasoning(
                                "pg-m3-a1-r",
                                "They want the validator explained. A short list and the code should do.",
                                start + 6_000,
                            ),
                            text("pg-m3-a1-x", PlaygroundContent.MARKDOWN_REPLY),
                        ),
                    ),
                    userMessage("pg-m3-u2", "Check the totals, then plan the remaining work.", start + 5 * MINUTE),
                    assistant(
                        "pg-m3-a2",
                        start + 5 * MINUTE + 4_000,
                        listOf(
                            bash(
                                "pg-m3-t1",
                                "npm run lint",
                                ToolStatus.Completed,
                                start + 5 * MINUTE + 5_000,
                                output = "> acme-web@1.0.0 lint\n> eslint src\n\n✔ No problems found",
                            ),
                            tool(
                                "pg-m3-t2",
                                "grep",
                                ToolStatus.Completed,
                                start + 5 * MINUTE + 8_000,
                                input = buildJsonObject { put("pattern", "memberDiscount") },
                                output = "src/checkout/totals.ts:22\nsrc/account/member.ts:9",
                            ),
                            edit("pg-m3-t3", "src/checkout/totals.ts", start + 5 * MINUTE + 12_000),
                            tool(
                                "pg-m3-t4",
                                "todowrite",
                                ToolStatus.Completed,
                                start + 5 * MINUTE + 15_000,
                                input = buildJsonObject { put("todos", PlaygroundContent.jsonArrayOfTodos()) },
                                output = "5 todos",
                            ),
                            bash(
                                "pg-m3-t5",
                                "npm run typecheck",
                                ToolStatus.Error,
                                start + 5 * MINUTE + 17_000,
                                error = "src/checkout/totals.ts(31,7): error TS2322: Type 'string' is not assignable to type 'Money'.",
                            ),
                            subagentTask(
                                "pg-m3-t6",
                                child.id,
                                "explore",
                                "Find coupon call sites",
                                ToolStatus.Completed,
                                start + 5 * MINUTE + 20_000,
                                output = "Found 3 call sites: checkout form, cart summary, order API.",
                            ),
                            text("pg-m3-a2-x", "The typecheck fails on one line in `totals.ts`. I'll fix it next."),
                        ),
                    ),
                    userMessage("pg-m3-u3", "Fix it and add tests for expired codes.", nowMs - 30_000),
                    assistant(
                        "pg-m3-a3",
                        nowMs - 28_000,
                        completedAtMs = null,
                        parts =
                            listOf(
                                reasoning("pg-m3-a3-r", "Fix the type first, then pin the clock for the expiry tests.", nowMs - 27_000),
                                edit("pg-m3-t7", "src/checkout/totals.ts", nowMs - 20_000),
                                bash("pg-m3-t8", "npm run test -- coupon", ToolStatus.Running, nowMs - 12_000),
                                tool(
                                    "pg-m3-t9",
                                    "edit",
                                    ToolStatus.Pending,
                                    nowMs - 10_000,
                                    input = buildJsonObject { put("filePath", "$WORKTREE/src/checkout/coupon.test.ts") },
                                ),
                            ),
                    ),
                )
        return PlaygroundScenario(
            id = PlaygroundScenarioId.AgentReplies,
            initial =
                state(
                    "Coupon code field",
                    nowMs,
                    messages,
                    children = listOf(child),
                    childMessages = mapOf(child.id to childTranscript(child.id, "Find coupon call sites", nowMs, running = false)),
                ).copy(todos = PlaygroundContent.todos(completed = 2, total = 5))
                    .withActivity(ROOT_SESSION_ID, SessionActivity.Running),
        )
    }

    private fun noticesAndFailures(nowMs: Long): PlaygroundScenario {
        val start = nowMs - 40 * MINUTE
        val messages =
            PlaygroundContent.baseHistory(nowMs - 10 * MINUTE) +
                listOf(
                    PlaygroundContent.notice(
                        "pg-m4-n1",
                        TimelineNotice.AgentChanged(agent = "plan", previous = "build"),
                        start + 3 * MINUTE,
                    ),
                    PlaygroundContent.notice(
                        "pg-m4-n2",
                        TimelineNotice.ModelChanged(
                            model = NoticeModelRef(PlaygroundContent.PROVIDER_ID, "acme-mini"),
                            previous = NoticeModelRef(PlaygroundContent.PROVIDER_ID, PlaygroundContent.MODEL_ID),
                        ),
                        start + 4 * MINUTE,
                    ),
                    userMessage("pg-m4-u1", "Plan the coupon stacking rules.", start + 5 * MINUTE),
                    assistant(
                        "pg-m4-a1",
                        start + 5 * MINUTE + 3_000,
                        listOf(text("pg-m4-a1-x", "The coupon should apply after the member discount, once per order.")),
                    ),
                    PlaygroundContent.notice(
                        "pg-m4-n3",
                        TimelineNotice.Moved(directory = PlaygroundContent.WORKTREE_CHECKOUT, previousDirectory = WORKTREE),
                        start + 8 * MINUTE,
                    ),
                    PlaygroundContent.notice("pg-m4-n4", TimelineNotice.InstructionsUpdated(listOf("AGENTS.md")), start + 9 * MINUTE),
                    PlaygroundContent.compaction("pg-m4-c", start + 10 * MINUTE),
                    userMessage("pg-m4-u2", "Run the checkout tests in watch mode.", start + 12 * MINUTE),
                    assistant(
                        "pg-m4-a2",
                        start + 12 * MINUTE + 3_000,
                        listOf(
                            text("pg-m4-a2-x", "Starting the tests in watch mode. First the form, then"),
                            bash(
                                "pg-m4-t1",
                                "npm run test -- --watch",
                                ToolStatus.Error,
                                start + 12 * MINUTE + 5_000,
                                error = "Tool execution aborted",
                            ).copy(isInterrupted = true),
                        ),
                        stopReason = MessageStopReason.Interrupted,
                    ),
                    userMessage("pg-m4-u3", "Run them once instead.", start + 15 * MINUTE),
                    assistant(
                        "pg-m4-a3",
                        start + 15 * MINUTE + 3_000,
                        listOf(text("pg-m4-a3-x", "Running the suite once.")),
                        failure =
                            SessionFailure(
                                type = "provider.rate-limit",
                                message = "Rate limit reached for acme-coder-1. Wait a minute and try again.",
                                status = 429,
                            ),
                    ),
                    userMessage("pg-m4-u4", "Try again, please.", nowMs - 20_000),
                )
        return PlaygroundScenario(
            id = PlaygroundScenarioId.NoticesAndFailures,
            initial =
                state("Coupon stacking", nowMs, messages).withActivity(
                    ROOT_SESSION_ID,
                    SessionActivity.Retrying(
                        attempt = 2,
                        nextAtMs = nowMs + 10 * MINUTE,
                        message = "The acme provider is overloaded",
                        errorType = "provider.overloaded",
                    ),
                ),
        )
    }

    private fun emptySession(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.EmptySession,
            initial = state("New session", nowMs, emptyList()),
        )

    // endregion

    // region Live

    private const val L1_REASONING =
        "The field already calls `applyCoupon()` on blur. I need to find where the result is shown, then make " +
            "expired and unknown codes show an error instead of failing silently. The tests should pin the clock " +
            "so the expiry check is stable."

    private const val L1_REPLY =
        "Done. The coupon field now validates when it loses focus:\n\n" +
            "- **Valid** codes apply the discount at once.\n" +
            "- **Expired** codes show *This code has expired*.\n" +
            "- **Unknown** codes show *We don't recognise this code*.\n\n" +
            "I added three tests to `coupon.test.ts`, and all 9 checkout tests pass."

    private fun streamingTurn(nowMs: Long): PlaygroundScenario {
        val reply = "pg-l1-a"
        return PlaygroundScenario(
            id = PlaygroundScenarioId.StreamingTurn,
            initial = state("Coupon validation", nowMs, PlaygroundContent.baseHistory(nowMs)).copy(branch = PlaygroundContent.BRANCH),
            steps =
                listOf(
                    PlaygroundStep(800, "Prompt sent") { state, now ->
                        state
                            .upsert(ROOT_SESSION_ID, userMessage("pg-l1-u", "Validate the coupon code when the field loses focus.", now))
                            .upsert(ROOT_SESSION_ID, working(reply, now))
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    streamed("Reasoning streams", L1_REASONING, 700) { state, shown, now ->
                        val started =
                            state
                                .messages(ROOT_SESSION_ID)
                                .firstOrNull { it.id == reply }
                                ?.parts
                                ?.filterIsInstance<MessagePart.Reasoning>()
                                ?.firstOrNull()
                                ?.timeStartEpochMs ?: now
                        state.upsertPart(ROOT_SESSION_ID, reply, reasoning("$reply-r", shown, started, streaming = true))
                    },
                    PlaygroundStep(700, "Tools pending") { state, now ->
                        state
                            .updatePart(ROOT_SESSION_ID, reply, "$reply-r") {
                                (it as MessagePart.Reasoning).copy(isStreaming = false, timeEndEpochMs = now)
                            }.upsertPart(
                                ROOT_SESSION_ID,
                                reply,
                                read("$reply-t1", "src/checkout/CheckoutForm.tsx", now, ToolStatus.Pending),
                            ).upsertPart(
                                ROOT_SESSION_ID,
                                reply,
                                tool(
                                    "$reply-t2",
                                    "grep",
                                    ToolStatus.Pending,
                                    now,
                                    input = buildJsonObject { put("pattern", "applyCoupon") },
                                ),
                            )
                    },
                    PlaygroundStep(800, "Tools running") { state, now ->
                        state
                            .toolStatus(reply, "$reply-t1", ToolStatus.Running, now)
                            .toolStatus(reply, "$reply-t2", ToolStatus.Running, now)
                    },
                    PlaygroundStep(1_200, "Tools done") { state, now ->
                        state
                            .toolStatus(reply, "$reply-t1", ToolStatus.Completed, now)
                            .toolStatus(
                                reply,
                                "$reply-t2",
                                ToolStatus.Completed,
                                now,
                                "src/checkout/coupon.ts:12\nsrc/checkout/CheckoutForm.tsx:48",
                            )
                    },
                    PlaygroundStep(900, "Edit and its diff") { state, now ->
                        state.upsertPart(ROOT_SESSION_ID, reply, edit("$reply-t3", "src/checkout/CheckoutForm.tsx", now))
                    },
                    PlaygroundStep(800, "Tests run") { state, now ->
                        state.upsertPart(ROOT_SESSION_ID, reply, bash("$reply-t4", "npm run test -- checkout", ToolStatus.Running, now))
                    },
                    PlaygroundStep(2_000, "Tests pass") { state, now ->
                        state.toolStatus(reply, "$reply-t4", ToolStatus.Completed, now, "Tests: 9 passed, 9 total")
                    },
                    streamText("Reply streams", reply, L1_REPLY),
                    PlaygroundStep(700, "Turn done") { state, now ->
                        state
                            .complete(reply, now)
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Idle)
                            .copy(changedFiles = PlaygroundContent.changedFiles, turnDiff = PlaygroundContent.turnDiff)
                    },
                ),
        )
    }

    private fun followUps(nowMs: Long): PlaygroundScenario {
        val steer = "pg-l2-steer"
        return PlaygroundScenario(
            id = PlaygroundScenarioId.FollowUps,
            initial =
                state(
                    "Coupon tests",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs) +
                        runningTurn(nowMs) +
                        listOf(
                            queued("pg-q1", "Then update the checkout README.", nowMs - 8_000),
                            queued("pg-q2", "And bump the version to 1.4.0.", nowMs - 4_000),
                        ),
                ).withActivity(ROOT_SESSION_ID, SessionActivity.Running),
            steps =
                listOf(
                    PlaygroundStep(1_500, "Steer sent") { state, now ->
                        state.upsert(
                            ROOT_SESSION_ID,
                            userMessage(
                                steer,
                                "Also cover codes typed in lower case.",
                                now,
                                delivery = FollowUpDelivery.Steer,
                                waitingForPickup = true,
                            ),
                        )
                    },
                    PlaygroundStep(2_500, "Steer delivered") { state, now ->
                        state
                            .upsertPart(
                                ROOT_SESSION_ID,
                                "pg-run-a",
                                text("pg-run-x", "Writing the tests now. I'll pin the clock so the expiry check is stable."),
                            ).toolStatus("pg-run-a", "pg-run-t2", ToolStatus.Completed, now, "Tests: 6 passed")
                            .complete("pg-run-a", now)
                            .deliver(ROOT_SESSION_ID, steer, now)
                            .upsert(ROOT_SESSION_ID, working("pg-l2-a2", now))
                    },
                    streamText(
                        "Steered reply streams",
                        "pg-l2-a2",
                        "Good idea. I added a lower-case case: `spring10` now finds `SPRING10`, and the test covers it.",
                    ),
                    PlaygroundStep(600, "Turn finishes") { state, now -> state.complete("pg-l2-a2", now) },
                    PlaygroundStep(1_200, "First queued prompt runs") { state, now ->
                        state
                            .deliver(ROOT_SESSION_ID, "pg-q1", now)
                            .upsert(
                                ROOT_SESSION_ID,
                                assistant(
                                    "pg-l2-a3",
                                    now,
                                    listOf(read("pg-l2-a3-t", "README.md", now, ToolStatus.Running)),
                                    completedAtMs = null,
                                ),
                            )
                    },
                    PlaygroundStep(1_800, "Second queued prompt runs") { state, now ->
                        state
                            .toolStatus("pg-l2-a3", "pg-l2-a3-t", ToolStatus.Completed, now)
                            .upsertPart(ROOT_SESSION_ID, "pg-l2-a3", text("pg-l2-a3-x", "The README now explains coupon codes."))
                            .complete("pg-l2-a3", now)
                            .deliver(ROOT_SESSION_ID, "pg-q2", now)
                            .upsert(ROOT_SESSION_ID, working("pg-l2-a4", now))
                    },
                    PlaygroundStep(1_800, "Queue done") { state, now ->
                        state
                            .upsertPart(ROOT_SESSION_ID, "pg-l2-a4", text("pg-l2-a4-x", "Bumped the version to 1.4.0 in `package.json`."))
                            .complete("pg-l2-a4", now)
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Idle)
                    },
                ),
        )
    }

    private fun subagents(nowMs: Long): PlaygroundScenario {
        val start = nowMs - 3 * MINUTE
        return PlaygroundScenario(
            id = PlaygroundScenarioId.Subagents,
            initial = auditRunning("Checkout audit", nowMs),
            steps =
                listOf(
                    PlaygroundStep(2_500, "explore asks permission") { state, _ ->
                        state.copy(permissions = listOf(PlaygroundContent.bashPermission("pg-perm-1", CHILD_AUDIT_CHECKOUT)))
                    },
                    PlaygroundStep(4_000, "Permission allowed") { state, _ -> state.copy(permissions = emptyList()) },
                    PlaygroundStep(2_500, "general finishes") { state, now ->
                        state.withAuditTurn(start, now, checkoutDone = false, cartDone = true)
                    },
                    PlaygroundStep(2_500, "explore finishes") { state, now ->
                        state.withAuditTurn(start, now, checkoutDone = true, cartDone = true)
                    },
                ),
        )
    }

    private const val L4_INTERRUPTED =
        "I'll move the subtotal, discount and tax maths into one `computeTotals()` function. First the subtotal: " +
            "it sums the line items, then the discount reads the coupon, and"

    private fun abortAndRetry(nowMs: Long): PlaygroundScenario =
        PlaygroundScenario(
            id = PlaygroundScenarioId.AbortAndRetry,
            initial = state("Totals refactor", nowMs, PlaygroundContent.baseHistory(nowMs)),
            steps =
                listOf(
                    PlaygroundStep(800, "Prompt sent") { state, now ->
                        state
                            .upsert(ROOT_SESSION_ID, userMessage("pg-l4-u1", "Refactor the totals into one function.", now))
                            .upsert(ROOT_SESSION_ID, working("pg-l4-a1", now))
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    streamText("Reply streams", "pg-l4-a1", L4_INTERRUPTED),
                    PlaygroundStep(500, "Stop interrupts it") { state, now ->
                        state
                            .aborted(ROOT_SESSION_ID)
                            .updateMessage(ROOT_SESSION_ID, "pg-l4-a1") {
                                it.copy(stopReason = MessageStopReason.Interrupted, completedAtEpochMs = now)
                            }
                    },
                    PlaygroundStep(1_500, "Next prompt waits to retry") { state, now ->
                        state
                            .upsert(ROOT_SESSION_ID, userMessage("pg-l4-u2", "Try again, and keep the old export.", now))
                            .withActivity(
                                ROOT_SESSION_ID,
                                SessionActivity.Retrying(
                                    attempt = 1,
                                    nextAtMs = now + RETRY_WAIT_MS,
                                    message = "The acme provider is overloaded",
                                    errorType = "provider.overloaded",
                                ),
                            )
                    },
                    PlaygroundStep(RETRY_WAIT_MS, "Retry goes through") { state, now ->
                        state
                            .upsert(ROOT_SESSION_ID, working("pg-l4-a2", now))
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    streamText(
                        "Reply streams",
                        "pg-l4-a2",
                        "Done. `computeTotals()` now returns the subtotal, discount and tax together, and `cartTotal` " +
                            "stays exported as a thin wrapper so nothing else changes.",
                    ),
                    PlaygroundStep(600, "Turn done") { state, now ->
                        state.complete("pg-l4-a2", now).withActivity(ROOT_SESSION_ID, SessionActivity.Idle)
                    },
                ),
        )

    // endregion

    // region Pills

    private fun idleOnBranch(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.IdleOnBranch,
            initial =
                state("Coupon code field", nowMs, PlaygroundContent.baseHistory(nowMs)).copy(
                    branch = PlaygroundContent.BRANCH,
                    changedFiles = PlaygroundContent.changedFiles,
                    turnDiff = PlaygroundContent.turnDiff,
                    todos = PlaygroundContent.todos(completed = 3, total = 5),
                ),
        )

    private fun cleanWorktree(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.CleanWorktree,
            initial =
                state(
                    "Coupon code field",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs),
                    directory = PlaygroundContent.WORKTREE_CHECKOUT,
                ).copy(branch = PlaygroundContent.BRANCH),
        )

    private fun runningQueued(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.RunningQueued,
            initial =
                state(
                    "Coupon tests",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs) +
                        runningTurn(nowMs) +
                        listOf(
                            queued("pg-q1", "Then update the checkout README.", nowMs - 8_000),
                            queued("pg-q2", "And bump the version to 1.4.0.", nowMs - 4_000),
                        ),
                ).withActivity(ROOT_SESSION_ID, SessionActivity.Running),
        )

    private fun backgroundWork(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.BackgroundWork,
            initial = auditRunning("Checkout audit", nowMs),
        )

    private fun needsInput(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.NeedsInput,
            collapsesPermission = true,
            initial =
                auditRunning("Checkout audit", nowMs).copy(
                    permissions = listOf(PlaygroundContent.bashPermission("pg-perm-1", CHILD_AUDIT_CHECKOUT)),
                    questions = listOf(PlaygroundContent.couponQuestion("pg-question-1", ROOT_SESSION_ID)),
                ),
        )

    private fun sideQuestionReady(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.SideQuestionReady,
            readySideQuestions = listOf("What happens to expired codes?"),
            initial =
                state("Coupon tests", nowMs, PlaygroundContent.baseHistory(nowMs) + runningTurn(nowMs))
                    .withActivity(ROOT_SESSION_ID, SessionActivity.Running),
        )

    private fun requestFailed(nowMs: Long): PlaygroundScenario {
        val start = nowMs - 2 * MINUTE
        return PlaygroundScenario(
            id = PlaygroundScenarioId.RequestFailed,
            initial =
                state(
                    "Coupon tests",
                    nowMs,
                    PlaygroundContent.longHistory(nowMs) +
                        listOf(
                            userMessage("pg-f-u", "Run the full checkout test suite.", start),
                            assistant(
                                "pg-f-a",
                                start + 2_000,
                                listOf(
                                    bash(
                                        "pg-f-t",
                                        "npm run test:e2e -- checkout",
                                        ToolStatus.Completed,
                                        start + 3_000,
                                        output = "12 passed",
                                    ),
                                    text("pg-f-x", "The suite passed. Now checking"),
                                ),
                                completedAtMs = start + 30_000,
                                failure =
                                    SessionFailure(
                                        type = "provider.rate-limit",
                                        message = "Rate limit reached for acme-coder-1. Wait a minute and try again.",
                                        status = 429,
                                    ),
                            ),
                        ),
                ),
        )
    }

    private fun busyCorner(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.BusyCorner,
            collapsesPermission = true,
            readySideQuestions = listOf("Which file holds the coupon rules?"),
            runningSideQuestions = listOf("Is the coupon applied before tax?"),
            initial =
                auditRunning(
                    "Checkout audit",
                    nowMs,
                ).let { state ->
                    state
                        .upsert(ROOT_SESSION_ID, queued("pg-q1", "Then update the checkout README.", nowMs - 8_000))
                        .upsert(ROOT_SESSION_ID, queued("pg-q2", "And bump the version to 1.4.0.", nowMs - 4_000))
                }.copy(
                    branch = PlaygroundContent.BRANCH,
                    changedFiles = PlaygroundContent.changedFiles,
                    todos = PlaygroundContent.todos(completed = 2, total = 5),
                    permissions = listOf(PlaygroundContent.bashPermission("pg-perm-1", CHILD_AUDIT_CHECKOUT)),
                ),
        )

    private fun jumpToLatest(nowMs: Long): PlaygroundScenario {
        fun reply(
            id: String,
            text: String,
            now: Long,
        ) = assistant(id, now, listOf(read("$id-t", "src/cart/totals.ts", now), text("$id-x", text)), completedAtMs = now)
        return PlaygroundScenario(
            id = PlaygroundScenarioId.JumpToLatest,
            hint = "Scroll up now: messages arrive in 6 s",
            initial = state("Cart cleanup", nowMs, PlaygroundContent.longHistory(nowMs)),
            steps =
                listOf(
                    PlaygroundStep(6_000, "A prompt arrives") { state, now ->
                        state
                            .upsert(ROOT_SESSION_ID, userMessage("pg-s-u", "Is the cart total still right with a coupon?", now))
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    PlaygroundStep(2_000, "The reply") { state, now ->
                        state.upsert(
                            ROOT_SESSION_ID,
                            reply("pg-s-a1", "Yes. The coupon applies once, before tax, so the total matches the receipt.", now),
                        )
                    },
                    PlaygroundStep(2_000, "A follow-up prompt") { state, now ->
                        state.upsert(ROOT_SESSION_ID, userMessage("pg-s-u2", "And with two coupons?", now))
                    },
                ),
        )
    }

    private fun keyboardUp(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.KeyboardUp,
            initial =
                auditRunning("Checkout audit", nowMs).copy(
                    branch = PlaygroundContent.BRANCH,
                    changedFiles = PlaygroundContent.changedFiles,
                    todos = PlaygroundContent.todos(completed = 2, total = 5),
                ),
        )

    private fun fullLifecycle(nowMs: Long): PlaygroundScenario =
        PlaygroundScenario(
            id = PlaygroundScenarioId.FullLifecycle,
            initial = state("Checkout audit", nowMs, PlaygroundContent.baseHistory(nowMs)),
            steps =
                listOf(
                    PlaygroundStep(800, "Prompt sent") { state, now ->
                        state
                            .upsert(ROOT_SESSION_ID, userMessage("pg-audit-u", AUDIT_PROMPT, now))
                            .upsert(
                                ROOT_SESSION_ID,
                                assistant(
                                    "pg-audit-a",
                                    now,
                                    listOf(text("pg-audit-x1", "I'll run two subagents, one per area.")),
                                    completedAtMs = null,
                                ),
                            ).withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    PlaygroundStep(1_500, "A prompt is queued") { state, now ->
                        state.upsert(ROOT_SESSION_ID, queued("pg-q1", "Then update the checkout README.", now))
                    },
                    PlaygroundStep(1_500, "Subagents start") { state, now ->
                        state
                            .copy(sessions = state.sessions + auditChildren(now))
                            .withAuditTurn(state.auditStart(), now, checkoutDone = false, cartDone = false, replyAfterMs = 0)
                    },
                    PlaygroundStep(2_500, "explore needs input") { state, _ ->
                        state.copy(permissions = listOf(PlaygroundContent.bashPermission("pg-perm-1", CHILD_AUDIT_CHECKOUT)))
                    },
                    PlaygroundStep(3_000, "Permission allowed") { state, _ -> state.copy(permissions = emptyList()) },
                    PlaygroundStep(2_500, "Subagents finish") { state, now ->
                        state
                            .withAuditTurn(state.auditStart(), now, checkoutDone = true, cartDone = true, replyAfterMs = 0)
                            // The queued prompt runs next, so the session stays at work.
                            .withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    PlaygroundStep(1_200, "Queued prompt runs") { state, now ->
                        state
                            .deliver(ROOT_SESSION_ID, "pg-q1", now)
                            .upsert(ROOT_SESSION_ID, working("pg-l11-a2", now))
                    },
                    streamText(
                        "Reply streams",
                        "pg-l11-a2",
                        "Updated the checkout README: it now explains coupon codes and how they stack.",
                    ),
                    PlaygroundStep(600, "Done") { state, now ->
                        state.complete("pg-l11-a2", now).withActivity(ROOT_SESSION_ID, SessionActivity.Idle)
                    },
                ),
        )

    // endregion

    // region Sessions

    private fun childSession(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.ChildSession,
            boundSessionId = CHILD_AUDIT_CHECKOUT,
            initial = auditRunning("Checkout audit", nowMs),
        )

    // endregion
}
