package dev.opencode.mobile.ui.playground

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.PromptSkill
import dev.opencode.mobile.domain.message.SessionFailure
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoStatus
import dev.opencode.mobile.domain.session.VcsFileStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Made-up content for the Session Chat Playground: an `acme-web` project under
 * `/Users/example`, `example.com` links, an `acme` model. Nothing comes from a real session.
 */
object PlaygroundContent {
    const val WORKTREE = "/Users/example/acme-web"
    const val PROJECT_ID = "pg-project-acme-web"
    const val ROOT_SESSION_ID = "pg-session"
    const val PROVIDER_ID = "acme"
    const val MODEL_ID = "acme-coder-1"
    const val AGENT = "build"
    const val BRANCH = "feature/coupon-field"

    const val SIDE_ANSWER =
        "Expired codes return `CouponResult.Expired`, and the form shows *This code has expired*. " +
            "Nothing is charged until the code is valid."

    private const val MINUTE = 60_000L

    private val EDIT_PATCH =
        """
        --- a/{path}
        +++ b/{path}
        @@ -12,7 +12,9 @@ export function CheckoutForm({ cart }: Props) {
           const [email, setEmail] = useState("")
        -  const total = cart.subtotal
        +  const [coupon, setCoupon] = useState("")
        +  const discount = useDiscount(cart, coupon)
        +  const total = cart.subtotal - discount
           return (
             <form onSubmit={submit}>
        """.trimIndent()

    fun session(
        id: String,
        title: String,
        nowMs: Long,
        parentId: String? = null,
        agentName: String? = null,
    ) = Session(
        id = id,
        title = title,
        projectId = PROJECT_ID,
        directory = WORKTREE,
        parentId = parentId,
        createdAtEpochMs = nowMs - 40 * MINUTE,
        updatedAtEpochMs = nowMs,
        agentName = agentName,
    )

    // region Messages

    fun userMessage(
        id: String,
        text: String,
        sentAtMs: Long,
        files: List<MessagePart.File> = emptyList(),
        delivery: FollowUpDelivery? = null,
        waitingForPickup: Boolean = false,
        skills: List<PromptSkill> = emptyList(),
    ) = Message(
        id = id,
        role = MessageRole.User,
        text = text,
        parts = listOf(MessagePart.Text(text, id = "$id-text")) + files,
        modelProviderId = PROVIDER_ID,
        modelId = MODEL_ID,
        mode = AGENT,
        sentAtEpochMs = sentAtMs,
        followUpDelivery = delivery,
        isWaitingForPickup = waitingForPickup,
        promptSkills = skills,
    )

    /** An assistant reply; [completedAtMs] null means it is still running. */
    fun assistant(
        id: String,
        sentAtMs: Long,
        parts: List<MessagePart>,
        completedAtMs: Long? = sentAtMs + 20_000,
        failure: SessionFailure? = null,
        stopReason: MessageStopReason? = null,
    ) = Message(
        id = id,
        role = MessageRole.Assistant,
        text = parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { it.text },
        parts = parts,
        model = MODEL_ID,
        modelProviderId = PROVIDER_ID,
        modelId = MODEL_ID,
        mode = AGENT,
        sentAtEpochMs = sentAtMs,
        completedAtEpochMs = completedAtMs,
        failure = failure,
        stopReason = stopReason,
    )

    fun text(
        id: String,
        text: String,
        streaming: Boolean = false,
    ) = MessagePart.Text(text = text, isStreaming = streaming, id = id)

    fun reasoning(
        id: String,
        text: String,
        startMs: Long,
        streaming: Boolean = false,
    ) = MessagePart.Reasoning(
        text = text,
        isStreaming = streaming,
        id = id,
        timeStartEpochMs = startMs,
        timeEndEpochMs = if (streaming) null else startMs + 4_000,
    )

    fun tool(
        id: String,
        name: String,
        status: ToolStatus,
        startMs: Long,
        input: JsonObject = JsonObject(emptyMap()),
        output: String? = null,
        metadata: JsonObject? = null,
        title: String? = null,
        error: String? = null,
        images: List<MessagePart.File> = emptyList(),
        isInterrupted: Boolean = false,
    ) = MessagePart.Tool(
        toolName = name,
        status = status,
        title = title,
        error = error,
        callId = "$id-call",
        input = input,
        output = output,
        metadata = metadata,
        id = id,
        timeStartEpochMs = startMs,
        timeEndEpochMs = if (status == ToolStatus.Completed || status == ToolStatus.Error) startMs + 1_800 else null,
        isInterrupted = isInterrupted,
        images = images,
    )

    fun bash(
        id: String,
        command: String,
        status: ToolStatus,
        startMs: Long,
        output: String? = null,
        error: String? = null,
    ) = tool(
        id = id,
        name = "bash",
        status = status,
        startMs = startMs,
        input =
            buildJsonObject {
                put("command", command)
                put("description", "Run $command")
            },
        output = output,
        error = error,
    )

    fun read(
        id: String,
        path: String,
        startMs: Long,
        status: ToolStatus = ToolStatus.Completed,
    ) = tool(
        id = id,
        name = "read",
        status = status,
        startMs = startMs,
        input = buildJsonObject { put("filePath", "$WORKTREE/$path") },
        output = "1\timport { Coupon } from './coupon'\n2\t\n3\texport function applyCoupon(cart: Cart, code: string) {",
        title = path,
    )

    fun edit(
        id: String,
        path: String,
        startMs: Long,
        status: ToolStatus = ToolStatus.Completed,
    ) = tool(
        id = id,
        name = "edit",
        status = status,
        startMs = startMs,
        input =
            buildJsonObject {
                put("filePath", "$WORKTREE/$path")
                put("oldString", "const total = cart.subtotal")
                put("newString", "const total = cart.subtotal - discount")
            },
        output = "Edit applied successfully.",
        metadata =
            buildJsonObject {
                putJsonObject("filediff") {
                    put("file", "$WORKTREE/$path")
                    put("patch", EDIT_PATCH.replace("{path}", path))
                    put("additions", 3)
                    put("deletions", 1)
                }
            },
        title = path,
    )

    fun subagentTask(
        id: String,
        childSessionId: String,
        agent: String,
        description: String,
        status: ToolStatus,
        startMs: Long,
        output: String? = null,
    ) = tool(
        id = id,
        name = "task",
        status = status,
        startMs = startMs,
        input =
            buildJsonObject {
                put("description", description)
                put("prompt", "$description. Report what you find.")
                put("subagent_type", agent)
            },
        output = output,
        metadata = buildJsonObject { put("sessionId", childSessionId) },
        title = description,
    )

    fun image(
        id: String,
        name: String,
        color: Int,
    ) = MessagePart.File(
        id = id,
        filename = name,
        mime = "image/png",
        kind = AttachmentKind.Image,
        dataUrl = "data:image/png;base64," + PlaygroundImages.all[color % PlaygroundImages.all.size],
    )

    fun pdf(
        id: String,
        name: String,
    ) = MessagePart.File(
        id = id,
        filename = name,
        mime = "application/pdf",
        kind = AttachmentKind.Pdf,
        dataUrl = "data:application/pdf;base64,JVBERi0x",
    )

    /** What the playground answers to anything sent from the composer. */
    fun cannedReply(
        id: String,
        nowMs: Long,
    ) = assistant(
        id = id,
        sentAtMs = nowMs - WORLD_REPLY_MS,
        completedAtMs = nowMs,
        parts =
            listOf(
                text(
                    "$id-text",
                    "This is the **Session Chat Playground**, so nothing went to a server. " +
                        "Pick another scenario from the menu to keep exploring.",
                ),
            ),
    )

    private const val WORLD_REPLY_MS = 1_500L

    // endregion

    // region Shared history

    /** A finished first turn: ask, look, edit, summarise. */
    fun baseHistory(nowMs: Long): List<Message> {
        val start = nowMs - 30 * MINUTE
        return listOf(
            userMessage("pg-u1", "Add a coupon code field to the checkout page.", start),
            assistant(
                id = "pg-a1",
                sentAtMs = start + 5_000,
                parts =
                    listOf(
                        reasoning("pg-a1-r", "The checkout form lives in `src/checkout`. I should read it first.", start + 6_000),
                        read("pg-a1-t1", "src/checkout/CheckoutForm.tsx", start + 10_000),
                        edit("pg-a1-t2", "src/checkout/CheckoutForm.tsx", start + 14_000),
                        text(
                            "pg-a1-x",
                            "Added a **Coupon code** field under the order summary. It calls `applyCoupon()` " +
                                "when the field loses focus and shows the discount in the total.",
                        ),
                    ),
            ),
        )
    }

    /** Twelve short finished turns, for scrolling. */
    fun longHistory(nowMs: Long): List<Message> =
        (1..12).flatMap { turn ->
            val at = nowMs - (60 - turn * 4) * MINUTE
            listOf(
                userMessage("pg-hu$turn", HISTORY_PROMPTS[(turn - 1) % HISTORY_PROMPTS.size], at),
                assistant(
                    id = "pg-ha$turn",
                    sentAtMs = at + 3_000,
                    parts =
                        listOf(
                            read("pg-ha$turn-t", "src/cart/cart-$turn.ts", at + 4_000),
                            text("pg-ha$turn-x", HISTORY_REPLIES[(turn - 1) % HISTORY_REPLIES.size]),
                        ),
                ),
            )
        }

    private val HISTORY_PROMPTS =
        listOf(
            "Why does the cart badge lag behind?",
            "Rename `cartTotal` to `subtotal` everywhere.",
            "Add a test for an empty cart.",
            "Can the price formatter handle yen?",
        )

    private val HISTORY_REPLIES =
        listOf(
            "The badge reads a cached count. I now refresh it after every `addItem()` call.",
            "Renamed in 6 files. The public API keeps a deprecated alias for one release.",
            "Added `cart.empty.test.ts`; it checks the total, the badge and the empty state copy.",
            "Yes: `formatPrice()` uses `Intl.NumberFormat`, so yen shows without decimals.",
        )

    // endregion

    // region Inbox, todos, changes

    fun todos(
        completed: Int,
        total: Int,
    ): List<SessionTodo> =
        TODO_ITEMS.take(total).mapIndexed { index, content ->
            SessionTodo(
                position = index,
                content = content,
                status =
                    when {
                        index < completed -> SessionTodoStatus.Completed
                        index == completed -> SessionTodoStatus.InProgress
                        else -> SessionTodoStatus.Pending
                    },
                priority = "medium",
            )
        }

    private val TODO_ITEMS =
        listOf(
            "Read the checkout form",
            "Add the coupon field",
            "Validate codes on blur",
            "Show the discount in the total",
            "Write tests for expired codes",
        )

    val changedFiles =
        listOf(
            VcsFileStatus("src/checkout/CheckoutForm.tsx", FileDiffStatus.Modified, additions = 24, deletions = 6),
            VcsFileStatus("src/checkout/coupon.ts", FileDiffStatus.Added, additions = 41, deletions = 0),
            VcsFileStatus("src/checkout/coupon.test.ts", FileDiffStatus.Added, additions = 58, deletions = 0),
        )

    val turnDiff =
        changedFiles.map { file ->
            FileDiff(
                path = file.path,
                patch = EDIT_PATCH.replace("{path}", file.path),
                additions = file.additions,
                deletions = file.deletions,
                status = file.status,
            )
        }

    fun bashPermission(
        id: String,
        sessionId: String,
    ) = PermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = "bash",
        patterns = listOf("npm run test:e2e -- checkout"),
        metadata = buildJsonObject { put("command", "npm run test:e2e -- checkout") },
        always = listOf("npm run test:e2e *"),
        toolMessageId = null,
        toolCallId = null,
    )

    fun couponQuestion(
        id: String,
        sessionId: String,
    ) = QuestionRequest(
        id = id,
        sessionId = sessionId,
        questions =
            listOf(
                QuestionInfo(
                    question = "Should a coupon stack with the member discount?",
                    header = "Coupon rules",
                    options =
                        listOf(
                            QuestionOption("Stack them", "Apply the coupon after the member discount"),
                            QuestionOption("Best one wins", "Use whichever saves the customer more"),
                            QuestionOption("Coupon only", "Ignore the member discount when a code is used"),
                        ),
                ),
            ),
        toolMessageId = null,
        toolCallId = null,
    )

    // endregion

    // region Everything

    /** Reply that shows every markdown feature the timeline renders. */
    val MARKDOWN_REPLY =
        """
        ## Coupon validation

        The validator now runs in three steps:

        1. Normalise the code (trim, upper-case).
        2. Look it up with `CouponStore.find()`.
        3. Check the dates and the minimum order.

        - Expired codes return `Expired`
        - Unknown codes return `NotFound`
          - and are logged once per session

        See the [coupon guide](https://example.com/docs/coupons) for the rules.

        ### TypeScript

        ```ts
        export function validate(code: string, cart: Cart): CouponResult {
          const coupon = store.find(code.trim().toUpperCase())
          if (!coupon) return { kind: "NotFound" }
          if (coupon.expiresAt < Date.now()) return { kind: "Expired" }
          return { kind: "Valid", discount: coupon.percent * cart.subtotal / 100 }
        }
        ```

        ### Kotlin

        ```kotlin
        fun discountFor(cart: Cart, coupon: Coupon): Money =
            if (cart.subtotal >= coupon.minimumOrder) cart.subtotal * coupon.percent / 100 else Money.ZERO
        ```

        ### Swift

        ```swift
        struct Coupon: Codable {
            let code: String
            let percent: Int
            var isExpired: Bool { expiresAt < .now }
        }
        ```

        ### Shell

        ```bash
        npm run test -- coupon
        ```

        | Code | Result | Discount |
        | --- | --- | ---: |
        | `SPRING10` | Valid | 10% |
        | `WINTER5` | Expired | 0% |
        | `NOPE` | NotFound | 0% |

        > The table above comes from the fixture file, not from real orders.
        """.trimIndent()

    val LONG_REPLY =
        (1..6).joinToString("\n\n") { index ->
            "Step $index. The checkout keeps the coupon in the form state, so going back to the cart " +
                "and returning keeps the code. Totals refresh after each change, and the summary row " +
                "explains the discount in plain words so customers can see why the price moved."
        }

    // endregion

    internal fun jsonArrayOfTodos() =
        buildJsonArray {
            TODO_ITEMS.forEachIndexed { index, content ->
                add(
                    buildJsonObject {
                        put("content", content)
                        put("status", if (index < 2) "completed" else "pending")
                        put("priority", "medium")
                    },
                )
            }
        }
}
