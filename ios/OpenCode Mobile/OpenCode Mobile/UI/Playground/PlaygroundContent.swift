import Foundation

/// Made-up content for the Session Chat Playground: an `acme-web` project under
/// `/Users/example`, `example.com` links, an `acme` model. Nothing comes from a real session.
enum PlaygroundContent {
    static let worktree = "/Users/example/acme-web"
    /// A worktree checkout of `worktree`, for sessions that run in their own checkout.
    static let worktreeCheckout = "/Users/example/acme-web-coupon-field"
    static let projectID = "pg-project-acme-web"
    static let rootSessionID = "pg-session"
    static let providerID = "acme"
    static let modelID = "acme-coder-1"
    static let agent = "build"
    static let branch = "feature/coupon-field"

    static let sideAnswer =
        "Expired codes return `CouponResult.Expired`, and the form shows *This code has expired*. " +
        "Nothing is charged until the code is valid."

    static let minute: Int64 = 60_000

    static let editPatch = """
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
    """

    static func session(
        _ id: String,
        _ title: String,
        now: Int64,
        parentID: String? = nil,
        agentName: String? = nil,
        directory: String = worktree
    ) -> Session {
        Session(
            id: id,
            title: title,
            slug: nil,
            projectID: projectID,
            directory: directory,
            parentID: parentID,
            createdAtEpochMs: now - 40 * minute,
            updatedAtEpochMs: now,
            agentName: agentName
        )
    }

    // MARK: - Messages

    static func userMessage(
        _ id: String,
        _ text: String,
        _ sentAt: Int64,
        files: [FilePart] = [],
        delivery: FollowUpDelivery? = nil,
        waitingForPickup: Bool = false,
        skills: [PromptSkill] = []
    ) -> Message {
        Message(
            id: id,
            role: .user,
            text: text,
            parts: [.text(TextPart(id: "\(id)-text", text: text, isStreaming: false))] + files.map { .file($0) },
            model: nil,
            modelProviderID: providerID,
            modelID: modelID,
            mode: agent,
            sentAtEpochMs: sentAt,
            followUpDelivery: delivery,
            isWaitingForPickup: waitingForPickup,
            promptSkills: skills
        )
    }

    /// An assistant reply; `running` leaves it without a completion time.
    static func assistant(
        _ id: String,
        _ sentAt: Int64,
        _ parts: [MessagePart],
        completedAt: Int64? = nil,
        running: Bool = false,
        failure: SessionFailure? = nil,
        stopReason: MessageStopReason? = nil
    ) -> Message {
        Message(
            id: id,
            role: .assistant,
            text: Message.joinedText(parts),
            parts: parts,
            model: modelID,
            modelProviderID: providerID,
            modelID: modelID,
            mode: agent,
            sentAtEpochMs: sentAt,
            completedAtEpochMs: running ? nil : (completedAt ?? sentAt + 20_000),
            stopReason: stopReason,
            failure: failure
        )
    }

    /// `message` with `parts`; an assistant's text is rebuilt from them as `assistant` builds it.
    static func withParts(_ message: Message, _ parts: [MessagePart]) -> Message {
        message.role == .assistant ? message.replacing(parts: parts) : message.replacing(text: message.text, parts: parts)
    }

    static func notice(_ id: String, _ kind: TimelineNoticeKind, _ sentAt: Int64) -> Message {
        Message(
            id: id,
            role: .notice,
            text: "",
            parts: [.notice(TimelineNotice(id: id, kind: kind))],
            model: nil,
            mode: nil,
            sentAtEpochMs: sentAt
        )
    }

    /// A `!` command the user ran, as the server reports it: the command, then its output under it.
    static func shellCommand(_ id: String, _ command: String, output: String, _ sentAt: Int64) -> [Message] {
        [
            Message(
                id: id,
                role: .user,
                text: command,
                parts: [.text(TextPart(id: "\(id):text:0", text: command, isStreaming: false))],
                model: nil,
                modelProviderID: providerID,
                modelID: modelID,
                mode: agent,
                sentAtEpochMs: sentAt,
                completedAtEpochMs: sentAt + 1_200
            ),
            assistant("\(id):assistant", sentAt, [
                .tool(ToolPart(
                    id: "\(id):tool",
                    toolName: "bash",
                    status: .completed,
                    title: "Shell",
                    input: ["command": .string(command)],
                    output: output,
                    metadata: ["exit": .int(0)]
                )),
            ], completedAt: sentAt + 1_200),
        ]
    }

    /// A finished compaction: the summary stays behind the row's disclosure.
    static func compaction(_ id: String, _ sentAt: Int64) -> Message {
        Message(
            id: id,
            role: .assistant,
            text: "",
            parts: [.compaction(CompactionPart(
                id: id,
                auto: true,
                completed: true,
                summary: "The checkout form has a coupon field; codes are validated on blur and expired codes show an error."
            ))],
            model: nil,
            mode: "compaction",
            sentAtEpochMs: sentAt,
            completedAtEpochMs: sentAt + 6_000
        )
    }

    static func text(_ id: String, _ text: String, streaming: Bool = false) -> MessagePart {
        .text(TextPart(id: id, text: text, isStreaming: streaming))
    }

    static func reasoning(_ id: String, _ text: String, start: Int64, streaming: Bool = false) -> MessagePart {
        .reasoning(ReasoningPart(
            id: id,
            text: text,
            isStreaming: streaming,
            timeStartEpochMs: start,
            timeEndEpochMs: streaming ? nil : start + 4_000
        ))
    }

    static func toolPart(
        _ id: String,
        _ name: String,
        _ status: ToolStatus,
        start: Int64,
        input: [String: AnyCodable] = [:],
        output: String? = nil,
        metadata: [String: AnyCodable]? = nil,
        title: String? = nil,
        error: String? = nil,
        images: [FilePart] = [],
        isInterrupted: Bool = false
    ) -> ToolPart {
        ToolPart(
            id: id,
            toolName: name,
            status: status,
            title: title,
            error: error,
            callID: "\(id)-call",
            input: input,
            output: output,
            metadata: metadata,
            timeStartEpochMs: start,
            timeEndEpochMs: status == .completed || status == .error ? start + 1_800 : nil,
            isInterrupted: isInterrupted,
            images: images
        )
    }

    static func tool(
        _ id: String,
        _ name: String,
        _ status: ToolStatus,
        start: Int64,
        input: [String: AnyCodable] = [:],
        output: String? = nil,
        metadata: [String: AnyCodable]? = nil,
        title: String? = nil,
        error: String? = nil,
        images: [FilePart] = [],
        isInterrupted: Bool = false
    ) -> MessagePart {
        .tool(toolPart(
            id, name, status,
            start: start, input: input, output: output, metadata: metadata,
            title: title, error: error, images: images, isInterrupted: isInterrupted
        ))
    }

    static func bash(
        _ id: String,
        _ command: String,
        _ status: ToolStatus,
        start: Int64,
        output: String? = nil,
        error: String? = nil,
        isInterrupted: Bool = false
    ) -> MessagePart {
        tool(
            id, "bash", status,
            start: start,
            input: ["command": .string(command), "description": .string("Run \(command)")],
            output: output,
            error: error,
            isInterrupted: isInterrupted
        )
    }

    static func read(_ id: String, _ path: String, start: Int64, status: ToolStatus = .completed) -> MessagePart {
        tool(
            id, "read", status,
            start: start,
            input: ["filePath": .string("\(worktree)/\(path)")],
            output: "1\timport { Coupon } from './coupon'\n2\t\n3\texport function applyCoupon(cart: Cart, code: string) {",
            title: path
        )
    }

    static func edit(_ id: String, _ path: String, start: Int64, status: ToolStatus = .completed) -> MessagePart {
        tool(
            id, "edit", status,
            start: start,
            input: [
                "filePath": .string("\(worktree)/\(path)"),
                "oldString": .string("const total = cart.subtotal"),
                "newString": .string("const total = cart.subtotal - discount"),
            ],
            output: "Edit applied successfully.",
            metadata: [
                "filediff": .dictionary([
                    "file": .string("\(worktree)/\(path)"),
                    "patch": .string(editPatch.replacingOccurrences(of: "{path}", with: path)),
                    "additions": .int(3),
                    "deletions": .int(1),
                ]),
            ],
            title: path
        )
    }

    static func subagentTask(
        _ id: String,
        child: String,
        agent: String,
        description: String,
        _ status: ToolStatus,
        start: Int64,
        output: String? = nil,
        childTools: [AnyCodable] = []
    ) -> MessagePart {
        var metadata: [String: AnyCodable] = ["sessionId": .string(child)]
        // Classic v1 lists the child's tool calls here (#966).
        if !childTools.isEmpty { metadata["summary"] = .array(childTools) }
        return tool(
            id, "task", status,
            start: start,
            input: [
                "description": .string(description),
                "prompt": .string("\(description). Report what you find."),
                "subagent_type": .string(agent),
            ],
            output: output,
            metadata: metadata,
            title: description
        )
    }

    /// One child tool call in a task's `metadata.summary`, as classic v1 reports it.
    static func childToolCall(_ id: String, _ tool: String, input: [String: AnyCodable], running: Bool = false) -> AnyCodable {
        .dictionary([
            "id": .string(id),
            "tool": .string(tool),
            "state": .dictionary([
                "status": .string(running ? "running" : "completed"),
                "input": .dictionary(input),
            ]),
        ])
    }

    static func image(_ id: String, _ name: String, _ color: Int) -> FilePart {
        FilePart(
            id: id,
            filename: name,
            mime: "image/png",
            kind: .image,
            dataURL: "data:image/png;base64," + PlaygroundImages.all[color % PlaygroundImages.all.count]
        )
    }

    static func pdf(_ id: String, _ name: String) -> FilePart {
        FilePart(id: id, filename: name, mime: "application/pdf", kind: .pdf, dataURL: "data:application/pdf;base64,JVBERi0x")
    }

    /// What the playground answers to anything sent from the composer.
    static func cannedReply(_ id: String, now: Int64) -> Message {
        assistant(
            id,
            now - PlaygroundWorld.replyDelayMs,
            [text(
                "\(id)-text",
                "This is the **Session Chat Playground**, so nothing went to a server. " +
                    "Tap the title to pick another scenario."
            )],
            completedAt: now
        )
    }

    // MARK: - Shared history

    /// A finished first turn: ask, look, edit, summarise.
    static func baseHistory(now: Int64) -> [Message] {
        let start = now - 30 * minute
        return [
            userMessage("pg-u1", "Add a coupon code field to the checkout page.", start),
            assistant("pg-a1", start + 5_000, [
                reasoning("pg-a1-r", "The checkout form lives in `src/checkout`. I should read it first.", start: start + 6_000),
                read("pg-a1-t1", "src/checkout/CheckoutForm.tsx", start: start + 10_000),
                edit("pg-a1-t2", "src/checkout/CheckoutForm.tsx", start: start + 14_000),
                text(
                    "pg-a1-x",
                    "Added a **Coupon code** field under the order summary. It calls `applyCoupon()` " +
                        "when the field loses focus and shows the discount in the total."
                ),
            ]),
        ]
    }

    /// Fourteen short finished turns, for scrolling.
    static func longHistory(now: Int64) -> [Message] {
        (1...14).flatMap { turn -> [Message] in
            let at = now - Int64(70 - turn * 4) * minute
            return [
                userMessage("pg-hu\(turn)", historyPrompts[(turn - 1) % historyPrompts.count], at),
                assistant("pg-ha\(turn)", at + 3_000, [
                    read("pg-ha\(turn)-t", "src/cart/cart-\(turn).ts", start: at + 4_000),
                    text("pg-ha\(turn)-x", historyReplies[(turn - 1) % historyReplies.count]),
                ]),
            ]
        }
    }

    private static let historyPrompts = [
        "Why does the cart badge lag behind?",
        "Rename `cartTotal` to `subtotal` everywhere.",
        "Add a test for an empty cart.",
        "Can the price formatter handle yen?",
    ]

    private static let historyReplies = [
        "The badge reads a cached count. I now refresh it after every `addItem()` call.",
        "Renamed in 6 files. The public API keeps a deprecated alias for one release.",
        "Added `cart.empty.test.ts`; it checks the total, the badge and the empty state copy.",
        "Yes: `formatPrice()` uses `Intl.NumberFormat`, so yen shows without decimals.",
    ]

    // MARK: - Inbox, todos, changes

    static func todos(completed: Int, total: Int) -> [SessionTodo] {
        todoItems.prefix(total).enumerated().map { index, content in
            SessionTodo(
                position: index,
                content: content,
                status: index < completed ? .completed : (index == completed ? .inProgress : .pending),
                priority: "medium"
            )
        }
    }

    static let todoItems = [
        "Read the checkout form",
        "Add the coupon field",
        "Validate codes on blur",
        "Show the discount in the total",
        "Write tests for expired codes",
    ]

    static let changedFiles = [
        VcsFileStatus(path: "src/checkout/CheckoutForm.tsx", status: .modified, additions: 29, deletions: 14),
        VcsFileStatus(path: "src/checkout/coupon.ts", status: .added, additions: 41, deletions: 0),
        VcsFileStatus(path: "src/checkout/coupon.test.ts", status: .added, additions: 58, deletions: 0),
    ]

    static let turnDiff = changedFiles.map { file in
        FileDiff(
            path: file.path,
            patch: editPatch.replacingOccurrences(of: "{path}", with: file.path),
            additions: file.additions,
            deletions: file.deletions,
            status: file.status
        )
    }

    static func bashPermission(_ id: String, sessionID: String) -> PermissionRequest {
        PermissionRequest(
            id: id,
            sessionID: sessionID,
            permission: "bash",
            patterns: ["npm run test:e2e -- checkout"],
            metadata: ["command": .string("npm run test:e2e -- checkout")],
            always: ["npm run test:e2e *"],
            toolMessageID: nil,
            toolCallID: nil
        )
    }

    static func couponQuestion(_ id: String, sessionID: String) -> QuestionRequest {
        QuestionRequest(
            id: id,
            sessionID: sessionID,
            questions: [
                QuestionInfo(
                    question: "Should a coupon stack with the member discount?",
                    header: "Coupon rules",
                    options: [
                        QuestionOption(label: "Stack them", description: "Apply the coupon after the member discount"),
                        QuestionOption(label: "Best one wins", description: "Use whichever saves the customer more"),
                        QuestionOption(label: "Coupon only", description: "Ignore the member discount when a code is used"),
                    ],
                    multiple: false,
                    custom: true
                ),
            ],
            toolMessageID: nil,
            toolCallID: nil
        )
    }

    static func todosJSON() -> AnyCodable {
        .array(todoItems.enumerated().map { index, content in
            .dictionary([
                "content": .string(content),
                "status": .string(index < 2 ? "completed" : "pending"),
                "priority": .string("medium"),
            ])
        })
    }

    // MARK: - Everything

    /// Reply that shows every markdown feature the timeline renders.
    static let markdownReply = """
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
    """

    static let longReply = (1...6).map { index in
        "Step \(index). The checkout keeps the coupon in the form state, so going back to the cart " +
            "and returning keeps the code. Totals refresh after each change, and the summary row " +
            "explains the discount in plain words so customers can see why the price moved."
    }.joined(separator: "\n\n")
}
