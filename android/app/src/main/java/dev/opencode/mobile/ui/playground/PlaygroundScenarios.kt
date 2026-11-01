package dev.opencode.mobile.ui.playground

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.PromptSkill
import dev.opencode.mobile.domain.message.SessionFailure
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.ToolStatus
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

/** The scenarios in the playground menu, in menu order. */
enum class PlaygroundScenarioId(
    val title: String,
    val summary: String,
) {
    Everything("Everything", "Every message, part and tool state the timeline renders"),
    Quiet("Quiet", "Finished session on a branch with changes and todos"),
    Working("Working", "A turn running with two queued prompts"),
    Subagents("Subagents", "Two subagents run, one asks for permission, both finish"),
    NeedsInput("Needs input", "A subagent permission collapsed to its pill, a question behind it"),
    SideQuestionReady("Side question ready", "A /btw answer waits while a turn runs"),
    RequestFailed("Request failed", "The last request failed; the pill jumps to it"),
    BusyCorner("Busy corner", "Five activity items at once, four behind \"+4\""),
    ScrolledUp("Scrolled up", "Scroll up within 6s: three messages arrive below"),
    KeyboardUp("Keyboard up", "Tap the composer: compact pill, top pills hide"),
    ChildSession("Child session", "Inside a subagent's session, composer locked"),
}

/** One scripted change: [afterMs] after the previous step, [apply] runs on the world. */
data class PlaygroundStep(
    val afterMs: Long,
    val label: String,
    val apply: (state: PlaygroundState, nowMs: Long) -> PlaygroundState,
)

data class PlaygroundScenario(
    val id: PlaygroundScenarioId,
    val initial: PlaygroundState,
    /** The session the chat opens; a child session for [PlaygroundScenarioId.ChildSession]. */
    val boundSessionId: String = ROOT_SESSION_ID,
    val steps: List<PlaygroundStep> = emptyList(),
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

    fun build(
        id: PlaygroundScenarioId,
        nowMs: Long,
    ): PlaygroundScenario =
        when (id) {
            PlaygroundScenarioId.Everything -> everything(nowMs)
            PlaygroundScenarioId.Quiet -> quiet(nowMs)
            PlaygroundScenarioId.Working -> working(nowMs)
            PlaygroundScenarioId.Subagents -> subagents(nowMs)
            PlaygroundScenarioId.NeedsInput -> needsInput(nowMs)
            PlaygroundScenarioId.SideQuestionReady -> sideQuestionReady(nowMs)
            PlaygroundScenarioId.RequestFailed -> requestFailed(nowMs)
            PlaygroundScenarioId.BusyCorner -> busyCorner(nowMs)
            PlaygroundScenarioId.ScrolledUp -> scrolledUp(nowMs)
            PlaygroundScenarioId.KeyboardUp -> keyboardUp(nowMs)
            PlaygroundScenarioId.ChildSession -> childSession(nowMs)
        }

    // region Shared pieces

    private const val CHILD_AUDIT_CHECKOUT = "pg-child-checkout"
    private const val CHILD_AUDIT_CART = "pg-child-cart"

    private fun root(
        title: String,
        nowMs: Long,
    ) = session(ROOT_SESSION_ID, title, nowMs, agentName = PlaygroundContent.AGENT)

    private fun state(
        title: String,
        nowMs: Long,
        messages: List<Message>,
        children: List<dev.opencode.mobile.domain.session.Session> = emptyList(),
        childMessages: Map<String, List<Message>> = emptyMap(),
    ) = PlaygroundState(
        rootSessionId = ROOT_SESSION_ID,
        sessions = listOf(root(title, nowMs)) + children,
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

    /** The parent turn that started both audits; [running] keeps the tasks going. */
    private fun auditTurn(
        nowMs: Long,
        running: Boolean,
    ): List<Message> {
        val start = nowMs - 3 * MINUTE
        val status = if (running) ToolStatus.Running else ToolStatus.Completed
        return listOf(
            userMessage("pg-audit-u", "Audit the checkout and the cart totals in parallel.", start),
            assistant(
                id = "pg-audit-a",
                sentAtMs = start + 3_000,
                completedAtMs = if (running) null else nowMs,
                parts =
                    listOfNotNull(
                        text("pg-audit-x1", "I'll run two subagents, one per area."),
                        subagentTask(
                            "pg-audit-t1",
                            CHILD_AUDIT_CHECKOUT,
                            "explore",
                            "Audit the checkout flow",
                            status,
                            start + 5_000,
                            output = if (running) null else "Checkout is fine: one coupon, applied after the member discount.",
                        ),
                        subagentTask(
                            "pg-audit-t2",
                            CHILD_AUDIT_CART,
                            "general",
                            "Audit the cart totals",
                            status,
                            start + 5_500,
                            output = if (running) null else "Cart totals round once, at the end. No drift found.",
                        ),
                        if (running) null else text("pg-audit-x2", "Both audits are done. Nothing to fix."),
                    ),
            ),
        )
    }

    private fun PlaygroundState.auditRunning(nowMs: Long) =
        copy(
            messages =
                messages +
                    (CHILD_AUDIT_CHECKOUT to childTranscript(CHILD_AUDIT_CHECKOUT, "Audit the checkout flow", nowMs, running = true)) +
                    (CHILD_AUDIT_CART to childTranscript(CHILD_AUDIT_CART, "Audit the cart totals", nowMs, running = true)),
        ).withActivity(ROOT_SESSION_ID, SessionActivity.Running)
            .withActivity(CHILD_AUDIT_CHECKOUT, SessionActivity.Running)
            .withActivity(CHILD_AUDIT_CART, SessionActivity.Running)

    /** A turn in progress after the base history: streaming text and a running command. */
    private fun runningTurn(
        nowMs: Long,
        prompt: String = "Now write tests for expired coupon codes.",
    ): List<Message> {
        val start = nowMs - 40_000
        return listOf(
            userMessage("pg-run-u", prompt, start),
            assistant(
                id = "pg-run-a",
                sentAtMs = start + 2_000,
                completedAtMs = null,
                parts =
                    listOf(
                        reasoning("pg-run-r", "Expired codes need a fixed clock, so the test can pin `Date.now()`.", start + 3_000),
                        PlaygroundContent.read("pg-run-t1", "src/checkout/coupon.ts", start + 8_000),
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

    // endregion

    // region Everything

    private fun everything(nowMs: Long): PlaygroundScenario {
        // After the base history (30 minutes ago), so the timeline keeps this order.
        val start = nowMs - 28 * MINUTE
        val child = session("pg-child-everything", "Find coupon call sites", nowMs, ROOT_SESSION_ID, "explore")
        val history =
            PlaygroundContent.baseHistory(nowMs) +
                listOf(
                    userMessage(
                        "pg-e-u2",
                        "Here is the design for the coupon row. Match the spacing of the email field above it, keep " +
                            "the Apply button inside the field on wide screens and below it on phones, and use the " +
                            "same error style as the card number field when a code is not valid. The copy is in the " +
                            "design file; ask me if anything is unclear before you change shared components.",
                        start + 10 * MINUTE,
                        files = listOf(image("pg-e-u2-img", "coupon-row.png", 0)),
                    ),
                    assistant(
                        "pg-e-a2",
                        start + 10 * MINUTE + 5_000,
                        listOf(
                            reasoning(
                                "pg-e-a2-r",
                                "The design puts the button inside the field. On phones it moves below. I'll write " +
                                    "the validator first so the error states are real.",
                                start + 10 * MINUTE + 6_000,
                            ),
                            text("pg-e-a2-x", PlaygroundContent.MARKDOWN_REPLY),
                        ),
                    ),
                    Message(
                        id = "pg-e-notice",
                        role = MessageRole.Notice,
                        text = "",
                        parts = listOf(MessagePart.Notice("pg-e-notice", TimelineNotice.AgentChanged(agent = "plan", previous = "build"))),
                        sentAtEpochMs = start + 14 * MINUTE,
                    ),
                    userMessage(
                        "pg-e-u3",
                        "The stacking rules are in the attached spec.",
                        start + 15 * MINUTE,
                        files = listOf(PlaygroundContent.pdf("pg-e-u3-pdf", "coupon-rules.pdf")),
                    ),
                    assistant(
                        "pg-e-a3",
                        start + 15 * MINUTE + 4_000,
                        listOf(
                            bash(
                                "pg-e-t1",
                                "npm run lint",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 5_000,
                                output = "> acme-web@1.0.0 lint\n> eslint src\n\n✔ No problems found",
                            ),
                            tool(
                                "pg-e-t2",
                                "grep",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 8_000,
                                input = buildJsonObject { put("pattern", "memberDiscount") },
                                output = "src/checkout/totals.ts:22\nsrc/account/member.ts:9",
                            ),
                            tool(
                                "pg-e-t3",
                                "glob",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 9_000,
                                input = buildJsonObject { put("pattern", "src/**/*.test.ts") },
                                output = "src/cart/cart.test.ts\nsrc/checkout/coupon.test.ts",
                            ),
                            tool(
                                "pg-e-t4",
                                "webfetch",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 10_000,
                                input = buildJsonObject { put("url", "https://example.com/docs/coupons") },
                                output = "# Coupons\nA coupon applies after member discounts.",
                            ),
                            edit("pg-e-t5", "src/checkout/totals.ts", start + 15 * MINUTE + 12_000),
                            tool(
                                "pg-e-t6",
                                "write",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 14_000,
                                input =
                                    buildJsonObject {
                                        put("filePath", "$WORKTREE/src/checkout/coupon.ts")
                                        put("content", "export type CouponResult = Valid | Expired | NotFound\n")
                                    },
                                output = "Wrote file successfully.",
                            ),
                            tool(
                                "pg-e-t7",
                                "todowrite",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 15_000,
                                input = buildJsonObject { put("todos", PlaygroundContent.jsonArrayOfTodos()) },
                                output = "5 todos",
                            ),
                            bash(
                                "pg-e-t8",
                                "npm run typecheck",
                                ToolStatus.Error,
                                start + 15 * MINUTE + 17_000,
                                error = "src/checkout/totals.ts(31,7): error TS2322: Type 'string' is not assignable to type 'Money'.",
                            ),
                            subagentTask(
                                "pg-e-t9",
                                child.id,
                                "explore",
                                "Find coupon call sites",
                                ToolStatus.Completed,
                                start + 15 * MINUTE + 20_000,
                                output = "Found 3 call sites: checkout form, cart summary, order API.",
                            ),
                            text("pg-e-a3-x", "Stacking follows the spec: the coupon applies after the member discount."),
                        ),
                    ),
                    userMessage(
                        "pg-e-u4",
                        "/review src/checkout",
                        start + 20 * MINUTE,
                        skills =
                            listOf(
                                PromptSkill(
                                    id = "skill-review",
                                    name = "review",
                                    mentionText = "/review",
                                    mentionStart = 0,
                                    mentionEnd = 7,
                                ),
                            ),
                    ),
                    assistant(
                        "pg-e-a4",
                        start + 20 * MINUTE + 3_000,
                        listOf(text("pg-e-a4-x", "Starting the review…")),
                        failure =
                            SessionFailure(
                                type = "provider.overloaded",
                                message = "The acme provider is overloaded. Try again in a moment.",
                                status = 529,
                            ),
                    ),
                    userMessage("pg-e-u5", "Try again, please.", start + 22 * MINUTE),
                    assistant(
                        "pg-e-a5",
                        start + 22 * MINUTE + 3_000,
                        listOf(
                            text("pg-e-a5-x", "Reviewing `src/checkout`. First the form, then"),
                            bash(
                                "pg-e-a5-t",
                                "npm run test -- --watch",
                                ToolStatus.Error,
                                start + 22 * MINUTE + 5_000,
                                error = "Tool execution aborted",
                            ).copy(isInterrupted = true),
                        ),
                        stopReason = MessageStopReason.Interrupted,
                    ),
                    userMessage("pg-e-u6", "Show me the three coupon states.", start + 25 * MINUTE),
                    assistant(
                        "pg-e-a6",
                        start + 25 * MINUTE + 3_000,
                        listOf(
                            tool(
                                "pg-e-a6-t",
                                "screenshot",
                                ToolStatus.Completed,
                                start + 25 * MINUTE + 4_000,
                                input = buildJsonObject { put("url", "https://example.com/checkout") },
                                output = "3 screenshots",
                                title = "Checkout states",
                                images =
                                    listOf(
                                        image("pg-e-img1", "valid.png", 0),
                                        image("pg-e-img2", "expired.png", 1),
                                        image("pg-e-img3", "unknown.png", 2),
                                    ),
                            ),
                            text("pg-e-a6-x", "Valid, expired and unknown codes:\n\n" + PlaygroundContent.LONG_REPLY),
                        ),
                    ),
                )
        val turnStart = nowMs - 30_000
        val running =
            listOf(
                userMessage("pg-e-u7", "Now add tests for expired codes.", turnStart),
                assistant(
                    "pg-e-a7",
                    turnStart + 2_000,
                    completedAtMs = null,
                    parts =
                        listOf(
                            reasoning("pg-e-a7-r", "I'll pin the clock so expiry is stable.", turnStart + 3_000),
                            text("pg-e-a7-x", "Adding the tests. First a fixed clock", streaming = true),
                            bash("pg-e-a7-t1", "npm run test -- coupon", ToolStatus.Running, turnStart + 9_000),
                            tool(
                                "pg-e-a7-t2",
                                "edit",
                                ToolStatus.Pending,
                                turnStart + 10_000,
                                input = buildJsonObject { put("filePath", "$WORKTREE/src/checkout/coupon.test.ts") },
                            ),
                        ),
                ),
                userMessage(
                    "pg-e-steer",
                    "Also cover codes typed in lower case.",
                    nowMs - 5_000,
                    delivery = FollowUpDelivery.Steer,
                    waitingForPickup = true,
                ),
            )
        val initial =
            state(
                "Coupon code field",
                nowMs,
                history + running,
                children = listOf(child),
                childMessages = mapOf(child.id to childTranscript(child.id, "Find coupon call sites", nowMs, running = false)),
            ).copy(
                branch = PlaygroundContent.BRANCH,
                changedFiles = PlaygroundContent.changedFiles,
                turnDiff = PlaygroundContent.turnDiff,
                todos = PlaygroundContent.todos(completed = 2, total = 5),
            ).withActivity(ROOT_SESSION_ID, SessionActivity.Running)
        return PlaygroundScenario(
            id = PlaygroundScenarioId.Everything,
            initial = initial,
            steps =
                listOf(
                    PlaygroundStep(3_000, "Steer delivered") { state, now ->
                        state
                            .mapMessages(ROOT_SESSION_ID) { if (it.id == "pg-e-steer") it.copy(isWaitingForPickup = false) else it }
                            .mapMessages(ROOT_SESSION_ID) { message ->
                                if (message.id != "pg-e-a7") return@mapMessages message
                                message.copy(
                                    parts =
                                        message.parts.map { part ->
                                            if (part is MessagePart.Tool && part.id == "pg-e-a7-t1") {
                                                part.copy(status = ToolStatus.Completed, output = "Tests: 6 passed", timeEndEpochMs = now)
                                            } else {
                                                part
                                            }
                                        },
                                )
                            }
                    },
                    PlaygroundStep(3_000, "Retry countdown") { state, now ->
                        state.withActivity(
                            ROOT_SESSION_ID,
                            SessionActivity.Retrying(
                                attempt = 1,
                                nextAtMs = now + 10_000,
                                message = "The acme provider is overloaded",
                                errorType = "provider.overloaded",
                            ),
                        )
                    },
                    PlaygroundStep(10_000, "Running again") { state, _ ->
                        state.withActivity(ROOT_SESSION_ID, SessionActivity.Running)
                    },
                    PlaygroundStep(3_000, "Turn finished") { state, now ->
                        state
                            .upsert(
                                ROOT_SESSION_ID,
                                assistant(
                                    "pg-e-a7",
                                    turnStart + 2_000,
                                    completedAtMs = now,
                                    parts =
                                        listOf(
                                            reasoning("pg-e-a7-r", "I'll pin the clock so expiry is stable.", turnStart + 3_000),
                                            bash(
                                                "pg-e-a7-t1",
                                                "npm run test -- coupon",
                                                ToolStatus.Completed,
                                                turnStart + 9_000,
                                                output = "Tests: 8 passed",
                                            ),
                                            edit("pg-e-a7-t2", "src/checkout/coupon.test.ts", turnStart + 10_000),
                                            text("pg-e-a7-x", "Added 8 tests, including lower-case codes. All pass."),
                                        ),
                                ),
                            ).withActivity(ROOT_SESSION_ID, SessionActivity.Idle)
                    },
                ),
        )
    }

    // endregion

    // region Pill scenarios

    private fun quiet(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.Quiet,
            initial =
                state("Coupon code field", nowMs, PlaygroundContent.baseHistory(nowMs)).copy(
                    branch = PlaygroundContent.BRANCH,
                    changedFiles = PlaygroundContent.changedFiles,
                    turnDiff = PlaygroundContent.turnDiff,
                    todos = PlaygroundContent.todos(completed = 3, total = 5),
                ),
        )

    private fun working(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.Working,
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
                ).copy(branch = PlaygroundContent.BRANCH, changedFiles = PlaygroundContent.changedFiles)
                    .withActivity(ROOT_SESSION_ID, SessionActivity.Running),
        )

    private fun subagents(nowMs: Long): PlaygroundScenario {
        val initial =
            state("Checkout audit", nowMs, PlaygroundContent.baseHistory(nowMs) + auditTurn(nowMs, running = true), auditChildren(nowMs))
                .auditRunning(nowMs)
        return PlaygroundScenario(
            id = PlaygroundScenarioId.Subagents,
            initial = initial,
            steps =
                listOf(
                    PlaygroundStep(3_000, "explore asks for permission") { state, _ ->
                        state.copy(permissions = listOf(PlaygroundContent.bashPermission("pg-perm-1", CHILD_AUDIT_CHECKOUT)))
                    },
                    PlaygroundStep(5_000, "Permission answered") { state, _ -> state.copy(permissions = emptyList()) },
                    PlaygroundStep(3_000, "Both subagents finish") { state, now -> state.auditFinished(now) },
                ),
        )
    }

    private fun PlaygroundState.auditFinished(nowMs: Long): PlaygroundState {
        val finishedTurn = auditTurn(nowMs, running = false)
        return copy(
            permissions = emptyList(),
            messages =
                messages +
                    (CHILD_AUDIT_CHECKOUT to childTranscript(CHILD_AUDIT_CHECKOUT, "Audit the checkout flow", nowMs, running = false)) +
                    (CHILD_AUDIT_CART to childTranscript(CHILD_AUDIT_CART, "Audit the cart totals", nowMs, running = false)),
        ).let { state -> finishedTurn.fold(state) { acc, message -> acc.upsert(ROOT_SESSION_ID, message) } }
            .withActivity(ROOT_SESSION_ID, SessionActivity.Idle)
            .withActivity(CHILD_AUDIT_CHECKOUT, SessionActivity.Idle)
            .withActivity(CHILD_AUDIT_CART, SessionActivity.Idle)
    }

    private fun needsInput(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.NeedsInput,
            collapsesPermission = true,
            initial =
                state(
                    "Checkout audit",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs) + auditTurn(nowMs, running = true),
                    auditChildren(nowMs),
                ).auditRunning(nowMs)
                    .copy(
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
                state(
                    "Checkout audit",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs) +
                        auditTurn(nowMs, running = true) +
                        listOf(
                            queued("pg-q1", "Then update the checkout README.", nowMs - 8_000),
                            queued("pg-q2", "And bump the version to 1.4.0.", nowMs - 4_000),
                        ),
                    auditChildren(nowMs),
                ).auditRunning(nowMs)
                    .copy(
                        branch = PlaygroundContent.BRANCH,
                        changedFiles = PlaygroundContent.changedFiles,
                        todos = PlaygroundContent.todos(completed = 2, total = 5),
                        permissions = listOf(PlaygroundContent.bashPermission("pg-perm-1", CHILD_AUDIT_CHECKOUT)),
                    ),
        )

    private fun scrolledUp(nowMs: Long): PlaygroundScenario {
        fun reply(
            id: String,
            text: String,
            now: Long,
        ) = assistant(id, now, listOf(read("$id-t", "src/cart/totals.ts", now), text("$id-x", text)), completedAtMs = now)
        return PlaygroundScenario(
            id = PlaygroundScenarioId.ScrolledUp,
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
                state(
                    "Checkout audit",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs) + auditTurn(nowMs, running = true),
                    auditChildren(nowMs),
                ).auditRunning(nowMs)
                    .copy(
                        branch = PlaygroundContent.BRANCH,
                        changedFiles = PlaygroundContent.changedFiles,
                        todos = PlaygroundContent.todos(completed = 2, total = 5),
                    ),
        )

    private fun childSession(nowMs: Long) =
        PlaygroundScenario(
            id = PlaygroundScenarioId.ChildSession,
            boundSessionId = CHILD_AUDIT_CHECKOUT,
            initial =
                state(
                    "Checkout audit",
                    nowMs,
                    PlaygroundContent.baseHistory(nowMs) + auditTurn(nowMs, running = true),
                    auditChildren(nowMs),
                ).auditRunning(nowMs),
        )

    // endregion
}
