import Foundation

/// The groups the scenario sheet lists, in order.
enum PlaygroundScenarioGroup: String, CaseIterable {
    case messages
    case live
    case pills
    case sessions

    var title: String {
        switch self {
        case .messages: "Messages"
        case .live: "Live"
        case .pills: "Pills"
        case .sessions: "Sessions"
        }
    }
}

/// The scenarios in the scenario sheet, in sheet order.
enum PlaygroundScenarioID: String, CaseIterable {
    case longConversation
    case userMessages
    case agentReplies
    case noticesAndFailures
    case emptySession
    case streamingTurn
    case followUps
    case subagents
    case abortAndRetry
    case idleOnBranch
    case cleanWorktree
    case runningQueued
    case backgroundWork
    case needsInput
    case sideQuestionReady
    case requestFailed
    case busyCorner
    case jumpToLatest
    case keyboardUp
    case fullLifecycle
    case childSession

    var code: String {
        switch self {
        case .longConversation: "M1"
        case .userMessages: "M2"
        case .agentReplies: "M3"
        case .noticesAndFailures: "M4"
        case .emptySession: "M5"
        case .streamingTurn: "L1"
        case .followUps: "L2"
        case .subagents: "L3"
        case .abortAndRetry: "L4"
        case .idleOnBranch: "P1"
        case .cleanWorktree: "P2"
        case .runningQueued: "P3"
        case .backgroundWork: "P4"
        case .needsInput: "P5"
        case .sideQuestionReady: "P6"
        case .requestFailed: "P7"
        case .busyCorner: "P8"
        case .jumpToLatest: "P9"
        case .keyboardUp: "P10"
        case .fullLifecycle: "P11"
        case .childSession: "S1"
        }
    }

    var title: String {
        switch self {
        case .longConversation: "Long conversation"
        case .userMessages: "User messages"
        case .agentReplies: "Agent replies"
        case .noticesAndFailures: "Notices & failures"
        case .emptySession: "Empty session"
        case .streamingTurn: "Streaming turn"
        case .followUps: "Follow-ups while running"
        case .subagents: "Subagents"
        case .abortAndRetry: "Abort & retry"
        case .idleOnBranch: "Idle on a branch"
        case .cleanWorktree: "Clean worktree"
        case .runningQueued: "Running + queued"
        case .backgroundWork: "Background work"
        case .needsInput: "Needs input"
        case .sideQuestionReady: "Side question ready"
        case .requestFailed: "Request failed"
        case .busyCorner: "Busy corner"
        case .jumpToLatest: "Jump to latest"
        case .keyboardUp: "Keyboard up"
        case .fullLifecycle: "Full lifecycle"
        case .childSession: "Child session"
        }
    }

    var summary: String {
        switch self {
        case .longConversation: "Fourteen finished turns to scroll through"
        case .userMessages: "Short, long, images, a PDF, a skill and a shell command"
        case .agentReplies: "Markdown, reasoning, every tool state, a diff, todos, images, a subagent"
        case .noticesAndFailures: "Changes, compaction, an interrupt, a failure, a retry"
        case .emptySession: "No messages yet"
        case .streamingTurn: "Reasoning, tools, a diff and tests, then the reply streams"
        case .followUps: "A steer is delivered; two queued prompts run after"
        case .subagents: "Two run, one asks permission, both finish"
        case .abortAndRetry: "Stop interrupts the reply; the next prompt retries"
        case .idleOnBranch: "Branch +128 −14 and todos 3/5, nothing at the bottom"
        case .cleanWorktree: "A worktree checkout without changes: its icon only"
        case .runningQueued: "“2 queued” during a running turn"
        case .backgroundWork: "“2 subagents” counter"
        case .needsInput: "A subagent permission and a question, collapsed"
        case .sideQuestionReady: "A /btw answer waits while a turn runs"
        case .requestFailed: "The error pill jumps to the failure"
        case .busyCorner: "Five items: one pill plus “+4”"
        case .jumpToLatest: "Scroll up: three messages arrive below"
        case .keyboardUp: "Tap the composer: compact pill, other corners hide"
        case .fullLifecycle: "Send, queue, subagents, input, finish, queue runs"
        case .childSession: "Inside a subagent, composer locked"
        }
    }

    var group: PlaygroundScenarioGroup {
        switch self {
        case .longConversation, .userMessages, .agentReplies, .noticesAndFailures, .emptySession: .messages
        case .streamingTurn, .followUps, .subagents, .abortAndRetry: .live
        case .childSession: .sessions
        default: .pills
        }
    }

    /// "M1 Long conversation".
    var label: String { "\(code) \(title)" }
}

/// One change to the world, `afterMs` after the previous frame.
struct PlaygroundFrame {
    let afterMs: Int64
    let apply: (PlaygroundState, Int64) -> PlaygroundState
}

/// One scripted step: a single change, or several frames such as text streaming in.
struct PlaygroundStep {
    let label: String
    let frames: [PlaygroundFrame]

    init(label: String, frames: [PlaygroundFrame]) {
        precondition(!frames.isEmpty, "A step has at least one frame")
        self.label = label
        self.frames = frames
    }

    init(afterMs: Int64, label: String, apply: @escaping (PlaygroundState, Int64) -> PlaygroundState) {
        self.init(label: label, frames: [PlaygroundFrame(afterMs: afterMs, apply: apply)])
    }

    /// Every frame at once, as Next or a test runs it.
    func apply(_ state: PlaygroundState, _ now: Int64) -> PlaygroundState {
        frames.reduce(state) { state, frame in frame.apply(state, now) }
    }
}

struct PlaygroundScenario {
    let id: PlaygroundScenarioID
    let initial: PlaygroundState
    /// The session the chat opens; a child session for `.childSession`.
    var boundSessionID = PlaygroundContent.rootSessionID
    var steps: [PlaygroundStep] = []
    /// What the capsule says before the first step runs.
    var hint: String?
    /// Collapse the permission panel to its pill once it shows.
    var collapsesPermission = false
    /// `/btw` questions answered before the chat opens, so they wait as "ready".
    var readySideQuestions: [String] = []
    /// `/btw` questions asked at open that never answer, so they stay running.
    var runningSideQuestions: [String] = []

    var isScripted: Bool { !steps.isEmpty }

    var boundSession: Session { initial.session(boundSessionID)! }

    /// The state after every step ran, as the script leaves it.
    func finalState(now: Int64) -> PlaygroundState {
        steps.reduce(initial) { state, step in step.apply(state, now) }
    }
}

enum PlaygroundScenarios {
    private typealias C = PlaygroundContent
    private static let root = PlaygroundContent.rootSessionID
    private static let minute = PlaygroundContent.minute

    /// Streamed text grows by this many words a frame, one frame every `streamFrameMs`.
    private static let streamWordsPerFrame = 3
    private static let streamFrameMs: Int64 = 90

    /// How long the retry countdown in Abort & retry waits.
    static let retryWaitMs: Int64 = 5_000

    static func build(_ id: PlaygroundScenarioID, now: Int64) -> PlaygroundScenario {
        switch id {
        case .longConversation: longConversation(now)
        case .userMessages: userMessages(now)
        case .agentReplies: agentReplies(now)
        case .noticesAndFailures: noticesAndFailures(now)
        case .emptySession: emptySession(now)
        case .streamingTurn: streamingTurn(now)
        case .followUps: followUps(now)
        case .subagents: subagents(now)
        case .abortAndRetry: abortAndRetry(now)
        case .idleOnBranch: idleOnBranch(now)
        case .cleanWorktree: cleanWorktree(now)
        case .runningQueued: runningQueued(now)
        case .backgroundWork: backgroundWork(now)
        case .needsInput: needsInput(now)
        case .sideQuestionReady: sideQuestionReady(now)
        case .requestFailed: requestFailed(now)
        case .busyCorner: busyCorner(now)
        case .jumpToLatest: jumpToLatest(now)
        case .keyboardUp: keyboardUp(now)
        case .fullLifecycle: fullLifecycle(now)
        case .childSession: childSession(now)
        }
    }

    // MARK: - Shared pieces

    private static let childCheckout = "pg-child-checkout"
    private static let childCart = "pg-child-cart"
    private static let auditPrompt = "Audit the checkout and the cart totals in parallel."

    private static func state(
        _ title: String,
        now: Int64,
        _ messages: [Message],
        children: [Session] = [],
        childMessages: [String: [Message]] = [:],
        directory: String = PlaygroundContent.worktree
    ) -> PlaygroundState {
        var all = childMessages
        all[root] = messages
        return PlaygroundState(
            rootSessionID: root,
            sessions: [C.session(root, title, now: now, agentName: C.agent, directory: directory)] + children,
            messages: all
        )
    }

    private static func auditChildren(_ now: Int64) -> [Session] {
        [
            C.session(childCheckout, "Audit the checkout flow", now: now, parentID: root, agentName: "explore"),
            C.session(childCart, "Audit the cart totals", now: now, parentID: root, agentName: "general"),
        ]
    }

    private static func childTranscript(_ childID: String, _ prompt: String, now: Int64, running: Bool) -> [Message] {
        let start = now - 2 * minute
        return [
            C.userMessage("\(childID)-u", prompt, start),
            C.assistant("\(childID)-a", start + 2_000, [
                C.read("\(childID)-t1", "src/checkout/CheckoutForm.tsx", start: start + 3_000),
                C.tool(
                    "\(childID)-t2", "grep", .completed,
                    start: start + 6_000,
                    input: ["pattern": .string("applyCoupon")],
                    output: "src/checkout/coupon.ts:12\nsrc/checkout/CheckoutForm.tsx:48"
                ),
                running
                    ? C.bash("\(childID)-t3", "npm run test -- checkout", .running, start: start + 9_000)
                    : C.text("\(childID)-x", "No problems found. The coupon is applied once, after the member discount."),
            ], completedAt: now, running: running),
        ]
    }

    /// The parent turn that runs both audits from `start`. Each audit's task finishes on its own;
    /// the reply completes when both did. `replyAfterMs` is how long after the prompt the reply starts.
    private static func auditTurn(
        start: Int64,
        now: Int64,
        checkoutDone: Bool,
        cartDone: Bool,
        replyAfterMs: Int64 = 3_000
    ) -> [Message] {
        let done = checkoutDone && cartDone
        var parts: [MessagePart] = [
            C.text("pg-audit-x1", "I'll run two subagents, one per area."),
            C.subagentTask(
                "pg-audit-t1", child: childCheckout, agent: "explore", description: "Audit the checkout flow",
                checkoutDone ? .completed : .running, start: start + replyAfterMs + 2_000,
                output: checkoutDone ? "Checkout is fine: one coupon, applied after the member discount." : nil,
                childTools: [
                    C.childToolCall("pg-audit-t1-c1", "read", input: ["filePath": .string("src/checkout.ts")]),
                    C.childToolCall("pg-audit-t1-c2", "grep", input: ["pattern": .string("applyCoupon")], running: !checkoutDone),
                ]
            ),
            C.subagentTask(
                "pg-audit-t2", child: childCart, agent: "general", description: "Audit the cart totals",
                cartDone ? .completed : .running, start: start + replyAfterMs + 2_500,
                output: cartDone ? "Cart totals round once, at the end. No drift found." : nil,
                childTools: [
                    C.childToolCall("pg-audit-t2-c1", "read", input: ["filePath": .string("src/cart.ts")], running: !cartDone),
                ]
            ),
        ]
        if done { parts.append(C.text("pg-audit-x2", "Both audits are done. Nothing to fix.")) }
        return [
            C.userMessage("pg-audit-u", auditPrompt, start),
            C.assistant("pg-audit-a", start + replyAfterMs, parts, completedAt: now, running: !done),
        ]
    }

    private static func withAuditTurn(
        _ state: PlaygroundState,
        start: Int64,
        now: Int64,
        checkoutDone: Bool,
        cartDone: Bool,
        replyAfterMs: Int64 = 3_000
    ) -> PlaygroundState {
        // Keep each message's time from when it first landed.
        var copy = auditTurn(start: start, now: now, checkoutDone: checkoutDone, cartDone: cartDone, replyAfterMs: replyAfterMs)
            .reduce(state) { acc, message in
                let previous = acc.messages(root).first { $0.id == message.id }
                return acc.upsert(root, previous.map { message.replacing(sentAtEpochMs: $0.sentAtEpochMs) } ?? message)
            }
        copy.messages[childCheckout] = childTranscript(childCheckout, "Audit the checkout flow", now: now, running: !checkoutDone)
        copy.messages[childCart] = childTranscript(childCart, "Audit the cart totals", now: now, running: !cartDone)
        return copy
            .withActivity(root, checkoutDone && cartDone ? .idle : .running)
            .withActivity(childCheckout, checkoutDone ? .idle : .running)
            .withActivity(childCart, cartDone ? .idle : .running)
    }

    /// The audit running for three minutes after the base history.
    private static func auditRunning(_ title: String, now: Int64) -> PlaygroundState {
        withAuditTurn(
            state(title, now: now, C.baseHistory(now: now), children: auditChildren(now)),
            start: now - 3 * minute,
            now: now,
            checkoutDone: false,
            cartDone: false
        )
    }

    /// When the audit prompt was sent.
    private static func auditStart(_ state: PlaygroundState) -> Int64 {
        state.messages(root).first { $0.id == "pg-audit-u" }?.sentAtEpochMs ?? 0
    }

    /// A turn in progress after the base history: streaming text and a running command.
    private static func runningTurn(_ now: Int64) -> [Message] {
        let start = now - 40_000
        return [
            C.userMessage("pg-run-u", "Now write tests for expired coupon codes.", start),
            C.assistant("pg-run-a", start + 2_000, [
                C.reasoning("pg-run-r", "Expired codes need a fixed clock, so the test can pin `Date.now()`.", start: start + 3_000),
                C.read("pg-run-t1", "src/checkout/coupon.ts", start: start + 8_000),
                C.text("pg-run-x", "Writing the tests now. I'll pin the clock so the expiry check is stable", streaming: true),
                C.bash("pg-run-t2", "npm run test -- coupon", .running, start: start + 20_000),
            ], running: true),
        ]
    }

    private static func queued(_ id: String, _ text: String, _ at: Int64) -> Message {
        C.userMessage(id, text, at, delivery: .queue, waitingForPickup: true)
    }

    /// An assistant reply that is still running and has nothing to show yet.
    private static func working(_ id: String, _ now: Int64) -> Message {
        C.assistant(id, now, [], running: true)
    }

    private static func withPermission(_ state: PlaygroundState) -> PlaygroundState {
        var copy = state
        copy.permissions = [C.bashPermission("pg-perm-1", sessionID: childCheckout)]
        return copy
    }

    private static func withoutPermissions(_ state: PlaygroundState) -> PlaygroundState {
        var copy = state
        copy.permissions = []
        return copy
    }

    private static func complete(_ state: PlaygroundState, _ messageID: String, now: Int64) -> PlaygroundState {
        state.updateMessage(root, messageID) { message in
            C.withParts(message, message.parts.map { part in
                switch part {
                case var .text(text):
                    text.isStreaming = false
                    return .text(text)
                case let .reasoning(reasoning):
                    return .reasoning(ReasoningPart(
                        id: reasoning.id,
                        text: reasoning.text,
                        isStreaming: false,
                        timeStartEpochMs: reasoning.timeStartEpochMs,
                        timeEndEpochMs: reasoning.timeEndEpochMs ?? now
                    ))
                default:
                    return part
                }
            }).replacing(completedAtEpochMs: .some(now))
        }
    }

    private static func toolStatus(
        _ state: PlaygroundState,
        _ messageID: String,
        _ partID: String,
        _ status: ToolStatus,
        now: Int64,
        output: String? = nil
    ) -> PlaygroundState {
        state.updatePart(root, messageID, partID) { part in
            guard case let .tool(tool) = part else { return part }
            return .tool(ToolPart(
                id: tool.id,
                toolName: tool.toolName,
                status: status,
                title: tool.title,
                error: tool.error,
                callID: tool.callID,
                input: tool.input,
                output: output ?? tool.output,
                metadata: tool.metadata,
                timeStartEpochMs: tool.timeStartEpochMs,
                timeEndEpochMs: status == .completed || status == .error ? now : nil,
                isInterrupted: tool.isInterrupted,
                isMalformed: tool.isMalformed,
                images: tool.images
            ))
        }
    }

    /// A step that streams `full` into one text or reasoning part, a few words a frame, through the
    /// same message updates a server sends. The part stays streaming; a later step settles it.
    private static func streamed(
        _ label: String,
        _ full: String,
        afterMs: Int64,
        write: @escaping (PlaygroundState, String, Int64) -> PlaygroundState
    ) -> PlaygroundStep {
        let words = words(full)
        let frameCount = (words.count + streamWordsPerFrame - 1) / streamWordsPerFrame
        let frames = (0..<frameCount).map { index in
            let shown = trimmingTrailingWhitespace(words.prefix((index + 1) * streamWordsPerFrame).joined())
            return PlaygroundFrame(afterMs: index == 0 ? afterMs : streamFrameMs) { state, now in write(state, shown, now) }
        }
        return PlaygroundStep(label: label, frames: frames)
    }

    private static func streamText(_ label: String, _ messageID: String, _ full: String, afterMs: Int64 = 500) -> PlaygroundStep {
        streamed(label, full, afterMs: afterMs) { state, shown, _ in
            state.upsertPart(root, messageID, C.text("\(messageID)-x", shown, streaming: true))
        }
    }

    /// Each word with the whitespace after it, so joining a prefix gives the text so far.
    private static func words(_ text: String) -> [String] {
        var words: [String] = []
        var current = ""
        var inSpace = false
        for character in text {
            if character.isWhitespace {
                inSpace = true
            } else if inSpace {
                words.append(current)
                current = ""
                inSpace = false
            }
            current.append(character)
        }
        if !current.isEmpty { words.append(current) }
        return words
    }

    private static func trimmingTrailingWhitespace(_ text: String) -> String {
        var trimmed = text
        while trimmed.last?.isWhitespace == true { trimmed.removeLast() }
        return trimmed
    }

    // MARK: - Messages

    private static func longConversation(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .longConversation, initial: state("Cart cleanup", now: now, C.longHistory(now: now)))
    }

    private static func userMessages(_ now: Int64) -> PlaygroundScenario {
        let start = now - 30 * minute
        var minuteIndex: Int64 = 0
        func at() -> Int64 {
            defer { minuteIndex += 1 }
            return start + minuteIndex * minute
        }
        func ack(_ id: String, _ reply: String) -> Message {
            C.assistant(id, at(), [C.text("\(id)-x", reply)])
        }
        let messages: [Message] = [
            C.userMessage("pg-m2-u1", "Add a coupon code field.", at()),
            ack("pg-m2-a1", "Added it under the order summary."),
            C.userMessage(
                "pg-m2-u2",
                "Match the spacing of the email field above it, keep the Apply button inside the field on wide " +
                    "screens and below it on phones, and use the same error style as the card number field when a " +
                    "code is not valid. The copy is in the design file; ask me if anything is unclear before you " +
                    "change shared components.",
                at()
            ),
            ack("pg-m2-a2", "Understood. I'll leave the shared components alone."),
            C.userMessage("pg-m2-u3", "Three things:\n1. Trim spaces\n2. Upper-case the code\n3. Keep the old export", at()),
            ack("pg-m2-a3", "All three are in."),
            C.userMessage("pg-m2-u4", "Here is the design.", at(), files: [C.image("pg-m2-u4-img", "coupon-row.png", 0)]),
            ack("pg-m2-a4", "The row now matches the design."),
            C.userMessage(
                "pg-m2-u5",
                "And the three states:",
                at(),
                files: [
                    C.image("pg-m2-u5-img1", "valid.png", 0),
                    C.image("pg-m2-u5-img2", "expired.png", 1),
                    C.image("pg-m2-u5-img3", "unknown.png", 2),
                ]
            ),
            ack("pg-m2-a5", "Each state has its own message now."),
            C.userMessage(
                "pg-m2-u6",
                "The stacking rules are in the attached spec.",
                at(),
                files: [C.pdf("pg-m2-u6-pdf", "coupon-rules.pdf")]
            ),
            ack("pg-m2-a6", "The coupon now applies after the member discount, as the spec says."),
            C.userMessage(
                "pg-m2-u7",
                "/review src/checkout",
                at(),
                skills: [PromptSkill(id: "skill-review", name: "review", mentionText: "/review", mentionStart: 0, mentionEnd: 7)]
            ),
            ack("pg-m2-a7", "The review found nothing to fix."),
        ] + C.shellCommand(
            "pg-m2-sh",
            "git status --short",
            output: " M src/checkout/CheckoutForm.tsx\n?? src/checkout/coupon.ts\n?? src/checkout/coupon.test.ts",
            at()
        )
        return PlaygroundScenario(id: .userMessages, initial: state("Coupon code field", now: now, messages))
    }

    private static func agentReplies(_ now: Int64) -> PlaygroundScenario {
        let start = now - 25 * minute
        let child = C.session("pg-child-replies", "Find coupon call sites", now: now, parentID: root, agentName: "explore")
        let messages: [Message] = C.baseHistory(now: now) + [
            C.userMessage("pg-m3-u1", "How does the coupon validation work now?", start),
            C.assistant("pg-m3-a1", start + 5_000, [
                C.reasoning("pg-m3-a1-r", "They want the validator explained. A short list and the code should do.", start: start + 6_000),
                C.text("pg-m3-a1-x", C.markdownReply),
            ]),
            C.userMessage("pg-m3-u2", "Check the totals, then plan the remaining work.", start + 5 * minute),
            C.assistant("pg-m3-a2", start + 5 * minute + 4_000, [
                C.bash(
                    "pg-m3-t1", "npm run lint", .completed, start: start + 5 * minute + 5_000,
                    output: "> acme-web@1.0.0 lint\n> eslint src\n\n✔ No problems found"
                ),
                C.tool(
                    "pg-m3-t2", "grep", .completed, start: start + 5 * minute + 8_000,
                    input: ["pattern": .string("memberDiscount")],
                    output: "src/checkout/totals.ts:22\nsrc/account/member.ts:9"
                ),
                C.edit("pg-m3-t3", "src/checkout/totals.ts", start: start + 5 * minute + 12_000),
                C.tool(
                    "pg-m3-t4", "todowrite", .completed, start: start + 5 * minute + 15_000,
                    input: ["todos": C.todosJSON()],
                    output: "5 todos"
                ),
                C.bash(
                    "pg-m3-t5", "npm run typecheck", .error, start: start + 5 * minute + 17_000,
                    error: "src/checkout/totals.ts(31,7): error TS2322: Type 'string' is not assignable to type 'Money'."
                ),
                C.subagentTask(
                    "pg-m3-t6", child: child.id, agent: "explore", description: "Find coupon call sites",
                    .completed, start: start + 5 * minute + 20_000,
                    output: "Found 3 call sites: checkout form, cart summary, order API."
                ),
                C.text("pg-m3-a2-x", "The typecheck fails on one line in `totals.ts`. I'll fix it next."),
            ]),
            C.userMessage("pg-m3-u4", "Sketch the phone layout before you build it.", start + 12 * minute),
            C.assistant("pg-m3-a4", start + 12 * minute + 3_000, [
                C.text("pg-m3-a4-x1", "Here is the field with the button moved below it:"),
                .file(C.image("pg-m3-a4-img1", "coupon-phone.png", 1)),
                C.text("pg-m3-a4-x2", "And the two error states, expired and unknown:"),
                .file(C.image("pg-m3-a4-img2", "coupon-expired.png", 0)),
                .file(C.image("pg-m3-a4-img3", "coupon-unknown.png", 2)),
            ]),
            C.userMessage("pg-m3-u3", "Fix it and add tests for expired codes.", now - 30_000),
            C.assistant("pg-m3-a3", now - 28_000, [
                C.reasoning("pg-m3-a3-r", "Fix the type first, then pin the clock for the expiry tests.", start: now - 27_000),
                C.edit("pg-m3-t7", "src/checkout/totals.ts", start: now - 20_000),
                C.bash("pg-m3-t8", "npm run test -- coupon", .running, start: now - 12_000),
                C.tool(
                    "pg-m3-t9", "edit", .pending, start: now - 10_000,
                    input: ["filePath": .string("\(C.worktree)/src/checkout/coupon.test.ts")]
                ),
            ], running: true),
        ]
        var initial = state(
            "Coupon code field", now: now, messages,
            children: [child],
            childMessages: [child.id: childTranscript(child.id, "Find coupon call sites", now: now, running: false)]
        )
        initial.todos = C.todos(completed: 2, total: 5)
        return PlaygroundScenario(id: .agentReplies, initial: initial.withActivity(root, .running))
    }

    private static func noticesAndFailures(_ now: Int64) -> PlaygroundScenario {
        let start = now - 40 * minute
        let messages: [Message] = C.baseHistory(now: now - 10 * minute) + [
            C.notice("pg-m4-n1", .agentChanged(agent: "plan", previous: "build"), start + 3 * minute),
            C.notice(
                "pg-m4-n2",
                .modelChanged(
                    model: TimelineNoticeModel(providerID: C.providerID, modelID: "acme-mini"),
                    previous: TimelineNoticeModel(providerID: C.providerID, modelID: C.modelID)
                ),
                start + 4 * minute
            ),
            C.userMessage("pg-m4-u1", "Plan the coupon stacking rules.", start + 5 * minute),
            C.assistant(
                "pg-m4-a1", start + 5 * minute + 3_000,
                [C.text("pg-m4-a1-x", "The coupon should apply after the member discount, once per order.")]
            ),
            C.notice("pg-m4-n3", .moved(directory: C.worktreeCheckout), start + 8 * minute),
            C.notice("pg-m4-n4", .instructionsUpdated(sources: ["AGENTS.md"]), start + 9 * minute),
            C.compaction("pg-m4-c", start + 10 * minute),
            C.userMessage("pg-m4-u2", "Run the checkout tests in watch mode.", start + 12 * minute),
            C.assistant(
                "pg-m4-a2", start + 12 * minute + 3_000,
                [
                    C.text("pg-m4-a2-x", "Starting the tests in watch mode. First the form, then"),
                    C.bash(
                        "pg-m4-t1", "npm run test -- --watch", .error, start: start + 12 * minute + 5_000,
                        error: "Tool execution aborted", isInterrupted: true
                    ),
                ],
                stopReason: .interrupted
            ),
            C.userMessage("pg-m4-u3", "Run them once instead.", start + 15 * minute),
            C.assistant(
                "pg-m4-a3", start + 15 * minute + 3_000,
                [C.text("pg-m4-a3-x", "Running the suite once.")],
                failure: SessionFailure(
                    type: "provider.rate-limit",
                    message: "Rate limit reached for acme-coder-1. Wait a minute and try again.",
                    status: 429,
                    providerBody: nil
                )
            ),
            C.userMessage("pg-m4-u4", "Try again, please.", now - 20_000),
        ]
        let initial = state("Coupon stacking", now: now, messages).withActivity(root, .retrying(
            attempt: 2,
            nextAtMs: now + 10 * minute,
            message: "The acme provider is overloaded",
            errorType: "provider.overloaded",
            actionLabel: nil,
            actionLink: nil
        ))
        return PlaygroundScenario(id: .noticesAndFailures, initial: initial)
    }

    private static func emptySession(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .emptySession, initial: state("New session", now: now, []))
    }

    // MARK: - Live

    private static let l1Reasoning =
        "The field already calls `applyCoupon()` on blur. I need to find where the result is shown, then make " +
        "expired and unknown codes show an error instead of failing silently. The tests should pin the clock " +
        "so the expiry check is stable."

    private static let l1Reply =
        "Done. The coupon field now validates when it loses focus:\n\n" +
        "- **Valid** codes apply the discount at once.\n" +
        "- **Expired** codes show *This code has expired*.\n" +
        "- **Unknown** codes show *We don't recognise this code*.\n\n" +
        "I added three tests to `coupon.test.ts`, and all 9 checkout tests pass."

    private static func streamingTurn(_ now: Int64) -> PlaygroundScenario {
        let reply = "pg-l1-a"
        var initial = state("Coupon validation", now: now, C.baseHistory(now: now))
        initial.branch = C.branch
        return PlaygroundScenario(id: .streamingTurn, initial: initial, steps: [
            PlaygroundStep(afterMs: 800, label: "Prompt sent") { state, now in
                state
                    .upsert(root, C.userMessage("pg-l1-u", "Validate the coupon code when the field loses focus.", now))
                    .upsert(root, working(reply, now))
                    .withActivity(root, .running)
            },
            streamed("Reasoning streams", l1Reasoning, afterMs: 700) { state, shown, now in
                let started = state.messages(root).first { $0.id == reply }?.parts.lazy.compactMap { part -> Int64? in
                    if case let .reasoning(reasoning) = part { return reasoning.timeStartEpochMs }
                    return nil
                }.first ?? now
                return state.upsertPart(root, reply, C.reasoning("\(reply)-r", shown, start: started, streaming: true))
            },
            PlaygroundStep(afterMs: 700, label: "Tools pending") { state, now in
                state
                    .updatePart(root, reply, "\(reply)-r") { part in
                        guard case let .reasoning(reasoning) = part else { return part }
                        return .reasoning(ReasoningPart(
                            id: reasoning.id,
                            text: reasoning.text,
                            isStreaming: false,
                            timeStartEpochMs: reasoning.timeStartEpochMs,
                            timeEndEpochMs: now
                        ))
                    }
                    .upsertPart(root, reply, C.read("\(reply)-t1", "src/checkout/CheckoutForm.tsx", start: now, status: .pending))
                    .upsertPart(root, reply, C.tool(
                        "\(reply)-t2", "grep", .pending, start: now,
                        input: ["pattern": .string("applyCoupon")]
                    ))
            },
            PlaygroundStep(afterMs: 800, label: "Tools running") { state, now in
                toolStatus(toolStatus(state, reply, "\(reply)-t1", .running, now: now), reply, "\(reply)-t2", .running, now: now)
            },
            PlaygroundStep(afterMs: 1_200, label: "Tools done") { state, now in
                toolStatus(
                    toolStatus(state, reply, "\(reply)-t1", .completed, now: now),
                    reply, "\(reply)-t2", .completed, now: now,
                    output: "src/checkout/coupon.ts:12\nsrc/checkout/CheckoutForm.tsx:48"
                )
            },
            PlaygroundStep(afterMs: 900, label: "Edit and its diff") { state, now in
                state.upsertPart(root, reply, C.edit("\(reply)-t3", "src/checkout/CheckoutForm.tsx", start: now))
            },
            PlaygroundStep(afterMs: 800, label: "Tests run") { state, now in
                state.upsertPart(root, reply, C.bash("\(reply)-t4", "npm run test -- checkout", .running, start: now))
            },
            PlaygroundStep(afterMs: 2_000, label: "Tests pass") { state, now in
                toolStatus(state, reply, "\(reply)-t4", .completed, now: now, output: "Tests: 9 passed, 9 total")
            },
            streamText("Reply streams", reply, l1Reply),
            PlaygroundStep(afterMs: 700, label: "Turn done") { state, now in
                var done = complete(state, reply, now: now).withActivity(root, .idle)
                done.changedFiles = C.changedFiles
                done.turnDiff = C.turnDiff
                return done
            },
        ])
    }

    private static func followUps(_ now: Int64) -> PlaygroundScenario {
        let steer = "pg-l2-steer"
        let initial = state(
            "Coupon tests", now: now,
            C.baseHistory(now: now) + runningTurn(now) + [
                queued("pg-q1", "Then update the checkout README.", now - 8_000),
                queued("pg-q2", "And bump the version to 1.4.0.", now - 4_000),
            ]
        ).withActivity(root, .running)
        return PlaygroundScenario(id: .followUps, initial: initial, steps: [
            PlaygroundStep(afterMs: 1_500, label: "Steer sent") { state, now in
                state.upsert(
                    root,
                    C.userMessage(steer, "Also cover codes typed in lower case.", now, delivery: .steer, waitingForPickup: true)
                )
            },
            PlaygroundStep(afterMs: 2_500, label: "Steer delivered") { state, now in
                let finished = toolStatus(
                    state.upsertPart(
                        root, "pg-run-a",
                        C.text("pg-run-x", "Writing the tests now. I'll pin the clock so the expiry check is stable.")
                    ),
                    "pg-run-a", "pg-run-t2", .completed, now: now, output: "Tests: 6 passed"
                )
                return complete(finished, "pg-run-a", now: now)
                    .deliver(root, steer, now: now)
                    .upsert(root, working("pg-l2-a2", now))
            },
            streamText(
                "Steered reply streams",
                "pg-l2-a2",
                "Good idea. I added a lower-case case: `spring10` now finds `SPRING10`, and the test covers it."
            ),
            PlaygroundStep(afterMs: 600, label: "Turn finishes") { state, now in complete(state, "pg-l2-a2", now: now) },
            PlaygroundStep(afterMs: 1_200, label: "First queued prompt runs") { state, now in
                state
                    .deliver(root, "pg-q1", now: now)
                    .upsert(root, C.assistant("pg-l2-a3", now, [C.read("pg-l2-a3-t", "README.md", start: now, status: .running)], running: true))
            },
            PlaygroundStep(afterMs: 1_800, label: "Second queued prompt runs") { state, now in
                let read = toolStatus(state, "pg-l2-a3", "pg-l2-a3-t", .completed, now: now)
                    .upsertPart(root, "pg-l2-a3", C.text("pg-l2-a3-x", "The README now explains coupon codes."))
                return complete(read, "pg-l2-a3", now: now)
                    .deliver(root, "pg-q2", now: now)
                    .upsert(root, working("pg-l2-a4", now))
            },
            PlaygroundStep(afterMs: 1_800, label: "Queue done") { state, now in
                complete(
                    state.upsertPart(root, "pg-l2-a4", C.text("pg-l2-a4-x", "Bumped the version to 1.4.0 in `package.json`.")),
                    "pg-l2-a4",
                    now: now
                ).withActivity(root, .idle)
            },
        ])
    }

    private static func subagents(_ now: Int64) -> PlaygroundScenario {
        let start = now - 3 * minute
        return PlaygroundScenario(id: .subagents, initial: auditRunning("Checkout audit", now: now), steps: [
            PlaygroundStep(afterMs: 2_500, label: "explore asks permission") { state, _ in withPermission(state) },
            PlaygroundStep(afterMs: 4_000, label: "Permission allowed") { state, _ in withoutPermissions(state) },
            PlaygroundStep(afterMs: 2_500, label: "general finishes") { state, now in
                withAuditTurn(state, start: start, now: now, checkoutDone: false, cartDone: true)
            },
            PlaygroundStep(afterMs: 2_500, label: "explore finishes") { state, now in
                withAuditTurn(state, start: start, now: now, checkoutDone: true, cartDone: true)
            },
        ])
    }

    private static let l4Interrupted =
        "I'll move the subtotal, discount and tax maths into one `computeTotals()` function. First the subtotal: " +
        "it sums the line items, then the discount reads the coupon, and"

    private static func abortAndRetry(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .abortAndRetry, initial: state("Totals refactor", now: now, C.baseHistory(now: now)), steps: [
            PlaygroundStep(afterMs: 800, label: "Prompt sent") { state, now in
                state
                    .upsert(root, C.userMessage("pg-l4-u1", "Refactor the totals into one function.", now))
                    .upsert(root, working("pg-l4-a1", now))
                    .withActivity(root, .running)
            },
            streamText("Reply streams", "pg-l4-a1", l4Interrupted),
            PlaygroundStep(afterMs: 500, label: "Stop interrupts it") { state, now in
                state.aborted(root).updateMessage(root, "pg-l4-a1") {
                    $0.replacing(completedAtEpochMs: .some(now), stopReason: .some(.interrupted))
                }
            },
            PlaygroundStep(afterMs: 1_500, label: "Next prompt waits to retry") { state, now in
                state
                    .upsert(root, C.userMessage("pg-l4-u2", "Try again, and keep the old export.", now))
                    .withActivity(root, .retrying(
                        attempt: 1,
                        nextAtMs: now + retryWaitMs,
                        message: "The acme provider is overloaded",
                        errorType: "provider.overloaded",
                        actionLabel: nil,
                        actionLink: nil
                    ))
            },
            PlaygroundStep(afterMs: retryWaitMs, label: "Retry goes through") { state, now in
                state.upsert(root, working("pg-l4-a2", now)).withActivity(root, .running)
            },
            streamText(
                "Reply streams",
                "pg-l4-a2",
                "Done. `computeTotals()` now returns the subtotal, discount and tax together, and `cartTotal` " +
                    "stays exported as a thin wrapper so nothing else changes."
            ),
            PlaygroundStep(afterMs: 600, label: "Turn done") { state, now in
                complete(state, "pg-l4-a2", now: now).withActivity(root, .idle)
            },
        ])
    }

    // MARK: - Pills

    private static func idleOnBranch(_ now: Int64) -> PlaygroundScenario {
        var initial = state("Coupon code field", now: now, C.baseHistory(now: now))
        initial.branch = C.branch
        initial.changedFiles = C.changedFiles
        initial.turnDiff = C.turnDiff
        initial.todos = C.todos(completed: 3, total: 5)
        return PlaygroundScenario(id: .idleOnBranch, initial: initial)
    }

    private static func cleanWorktree(_ now: Int64) -> PlaygroundScenario {
        var initial = state("Coupon code field", now: now, C.baseHistory(now: now), directory: C.worktreeCheckout)
        initial.branch = C.branch
        return PlaygroundScenario(id: .cleanWorktree, initial: initial)
    }

    private static func runningQueued(_ now: Int64) -> PlaygroundScenario {
        let messages = C.baseHistory(now: now) + runningTurn(now) + [
            queued("pg-q1", "Then update the checkout README.", now - 8_000),
            queued("pg-q2", "And bump the version to 1.4.0.", now - 4_000),
        ]
        return PlaygroundScenario(id: .runningQueued, initial: state("Coupon tests", now: now, messages).withActivity(root, .running))
    }

    private static func backgroundWork(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .backgroundWork, initial: auditRunning("Checkout audit", now: now))
    }

    private static func needsInput(_ now: Int64) -> PlaygroundScenario {
        var initial = withPermission(auditRunning("Checkout audit", now: now))
        initial.questions = [C.couponQuestion("pg-question-1", sessionID: root)]
        return PlaygroundScenario(id: .needsInput, initial: initial, collapsesPermission: true)
    }

    private static func sideQuestionReady(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(
            id: .sideQuestionReady,
            initial: state("Coupon tests", now: now, C.baseHistory(now: now) + runningTurn(now)).withActivity(root, .running),
            readySideQuestions: ["What happens to expired codes?"]
        )
    }

    private static func requestFailed(_ now: Int64) -> PlaygroundScenario {
        let start = now - 2 * minute
        let messages = C.longHistory(now: now) + [
            C.userMessage("pg-f-u", "Run the full checkout test suite.", start),
            C.assistant(
                "pg-f-a", start + 2_000,
                [
                    C.bash("pg-f-t", "npm run test:e2e -- checkout", .completed, start: start + 3_000, output: "12 passed"),
                    C.text("pg-f-x", "The suite passed. Now checking"),
                ],
                completedAt: start + 30_000,
                failure: SessionFailure(
                    type: "provider.rate-limit",
                    message: "Rate limit reached for acme-coder-1. Wait a minute and try again.",
                    status: 429,
                    providerBody: nil
                )
            ),
        ]
        return PlaygroundScenario(id: .requestFailed, initial: state("Coupon tests", now: now, messages))
    }

    private static func busyCorner(_ now: Int64) -> PlaygroundScenario {
        var initial = withPermission(
            auditRunning("Checkout audit", now: now)
                .upsert(root, queued("pg-q1", "Then update the checkout README.", now - 8_000))
                .upsert(root, queued("pg-q2", "And bump the version to 1.4.0.", now - 4_000))
        )
        initial.branch = C.branch
        initial.changedFiles = C.changedFiles
        initial.todos = C.todos(completed: 2, total: 5)
        return PlaygroundScenario(
            id: .busyCorner,
            initial: initial,
            collapsesPermission: true,
            readySideQuestions: ["Which file holds the coupon rules?"],
            runningSideQuestions: ["Is the coupon applied before tax?"]
        )
    }

    private static func jumpToLatest(_ now: Int64) -> PlaygroundScenario {
        func reply(_ id: String, _ text: String, _ now: Int64) -> Message {
            C.assistant(id, now, [C.read("\(id)-t", "src/cart/totals.ts", start: now), C.text("\(id)-x", text)], completedAt: now)
        }
        return PlaygroundScenario(
            id: .jumpToLatest,
            initial: state("Cart cleanup", now: now, C.longHistory(now: now)),
            steps: [
                PlaygroundStep(afterMs: 6_000, label: "A prompt arrives") { state, now in
                    state.upsert(root, C.userMessage("pg-s-u", "Is the cart total still right with a coupon?", now)).withActivity(root, .running)
                },
                PlaygroundStep(afterMs: 2_000, label: "The reply") { state, now in
                    state.upsert(root, reply("pg-s-a1", "Yes. The coupon applies once, before tax, so the total matches the receipt.", now))
                },
                PlaygroundStep(afterMs: 2_000, label: "A follow-up prompt") { state, now in
                    state.upsert(root, C.userMessage("pg-s-u2", "And with two coupons?", now))
                },
            ],
            hint: "Scroll up now: messages arrive in 6 s"
        )
    }

    private static func keyboardUp(_ now: Int64) -> PlaygroundScenario {
        var initial = auditRunning("Checkout audit", now: now)
        initial.branch = C.branch
        initial.changedFiles = C.changedFiles
        initial.todos = C.todos(completed: 2, total: 5)
        return PlaygroundScenario(id: .keyboardUp, initial: initial)
    }

    private static func fullLifecycle(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .fullLifecycle, initial: state("Checkout audit", now: now, C.baseHistory(now: now)), steps: [
            PlaygroundStep(afterMs: 800, label: "Prompt sent") { state, now in
                state
                    .upsert(root, C.userMessage("pg-audit-u", auditPrompt, now))
                    .upsert(root, C.assistant("pg-audit-a", now, [C.text("pg-audit-x1", "I'll run two subagents, one per area.")], running: true))
                    .withActivity(root, .running)
            },
            PlaygroundStep(afterMs: 1_500, label: "A prompt is queued") { state, now in
                state.upsert(root, queued("pg-q1", "Then update the checkout README.", now))
            },
            PlaygroundStep(afterMs: 1_500, label: "Subagents start") { state, now in
                var started = state
                started.sessions += auditChildren(now)
                return withAuditTurn(started, start: auditStart(state), now: now, checkoutDone: false, cartDone: false, replyAfterMs: 0)
            },
            PlaygroundStep(afterMs: 2_500, label: "explore needs input") { state, _ in withPermission(state) },
            PlaygroundStep(afterMs: 3_000, label: "Permission allowed") { state, _ in withoutPermissions(state) },
            PlaygroundStep(afterMs: 2_500, label: "Subagents finish") { state, now in
                withAuditTurn(state, start: auditStart(state), now: now, checkoutDone: true, cartDone: true, replyAfterMs: 0)
                    // The queued prompt runs next, so the session stays at work.
                    .withActivity(root, .running)
            },
            PlaygroundStep(afterMs: 1_200, label: "Queued prompt runs") { state, now in
                state.deliver(root, "pg-q1", now: now).upsert(root, working("pg-l11-a2", now))
            },
            streamText("Reply streams", "pg-l11-a2", "Updated the checkout README: it now explains coupon codes and how they stack."),
            PlaygroundStep(afterMs: 600, label: "Done") { state, now in
                complete(state, "pg-l11-a2", now: now).withActivity(root, .idle)
            },
        ])
    }

    // MARK: - Sessions

    private static func childSession(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .childSession, initial: auditRunning("Checkout audit", now: now), boundSessionID: childCheckout)
    }
}
