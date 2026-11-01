import Foundation

/// The scenarios in the playground menu, in menu order.
enum PlaygroundScenarioID: String, CaseIterable {
    case everything
    case quiet
    case working
    case subagents
    case needsInput
    case sideQuestionReady
    case requestFailed
    case busyCorner
    case scrolledUp
    case keyboardUp
    case childSession

    var title: String {
        switch self {
        case .everything: "Everything"
        case .quiet: "Quiet"
        case .working: "Working"
        case .subagents: "Subagents"
        case .needsInput: "Needs input"
        case .sideQuestionReady: "Side question ready"
        case .requestFailed: "Request failed"
        case .busyCorner: "Busy corner"
        case .scrolledUp: "Scrolled up"
        case .keyboardUp: "Keyboard up"
        case .childSession: "Child session"
        }
    }

    var summary: String {
        switch self {
        case .everything: "Every message, part and tool state the timeline renders"
        case .quiet: "Finished session on a branch with changes and todos"
        case .working: "A turn running with two queued prompts"
        case .subagents: "Two subagents run, one asks for permission, both finish"
        case .needsInput: "A subagent permission collapsed to its pill, a question behind it"
        case .sideQuestionReady: "A /btw answer waits while a turn runs"
        case .requestFailed: "The last request failed; the pill jumps to it"
        case .busyCorner: "Five activity items at once, four behind \"+4\""
        case .scrolledUp: "Scroll up within 6s: three messages arrive below"
        case .keyboardUp: "Tap the composer: compact pill, top pills hide"
        case .childSession: "Inside a subagent's session, composer locked"
        }
    }
}

/// One scripted change: `afterMs` after the previous step, `apply` runs on the world.
struct PlaygroundStep {
    let afterMs: Int64
    let label: String
    let apply: (PlaygroundState, Int64) -> PlaygroundState
}

struct PlaygroundScenario {
    let id: PlaygroundScenarioID
    let initial: PlaygroundState
    /// The session the chat opens; a child session for `.childSession`.
    var boundSessionID = PlaygroundContent.rootSessionID
    var steps: [PlaygroundStep] = []
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
    private static let childCheckout = "pg-child-checkout"
    private static let childCart = "pg-child-cart"

    static func build(_ id: PlaygroundScenarioID, now: Int64) -> PlaygroundScenario {
        switch id {
        case .everything: everything(now)
        case .quiet: quiet(now)
        case .working: working(now)
        case .subagents: subagents(now)
        case .needsInput: needsInput(now)
        case .sideQuestionReady: sideQuestionReady(now)
        case .requestFailed: requestFailed(now)
        case .busyCorner: busyCorner(now)
        case .scrolledUp: scrolledUp(now)
        case .keyboardUp: keyboardUp(now)
        case .childSession: childSession(now)
        }
    }

    // MARK: - Shared pieces

    private static func state(
        _ title: String,
        now: Int64,
        _ messages: [Message],
        children: [Session] = [],
        childMessages: [String: [Message]] = [:]
    ) -> PlaygroundState {
        var all = childMessages
        all[root] = messages
        return PlaygroundState(
            rootSessionID: root,
            sessions: [C.session(root, title, now: now, agentName: C.agent)] + children,
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

    /// The parent turn that started both audits; `running` keeps the tasks going.
    private static func auditTurn(_ now: Int64, running: Bool) -> [Message] {
        let start = now - 3 * minute
        let status: ToolStatus = running ? .running : .completed
        var parts: [MessagePart] = [
            C.text("pg-audit-x1", "I'll run two subagents, one per area."),
            C.subagentTask(
                "pg-audit-t1", child: childCheckout, agent: "explore", description: "Audit the checkout flow",
                status, start: start + 5_000,
                output: running ? nil : "Checkout is fine: one coupon, applied after the member discount."
            ),
            C.subagentTask(
                "pg-audit-t2", child: childCart, agent: "general", description: "Audit the cart totals",
                status, start: start + 5_500,
                output: running ? nil : "Cart totals round once, at the end. No drift found."
            ),
        ]
        if !running { parts.append(C.text("pg-audit-x2", "Both audits are done. Nothing to fix.")) }
        return [
            C.userMessage("pg-audit-u", "Audit the checkout and the cart totals in parallel.", start),
            C.assistant("pg-audit-a", start + 3_000, parts, completedAt: now, running: running),
        ]
    }

    private static func auditRunning(_ state: PlaygroundState, now: Int64) -> PlaygroundState {
        var copy = state
        copy.messages[childCheckout] = childTranscript(childCheckout, "Audit the checkout flow", now: now, running: true)
        copy.messages[childCart] = childTranscript(childCart, "Audit the cart totals", now: now, running: true)
        return copy.withActivity(root, .running).withActivity(childCheckout, .running).withActivity(childCart, .running)
    }

    fileprivate static func auditFinished(_ state: PlaygroundState, now: Int64) -> PlaygroundState {
        var copy = state
        copy.permissions = []
        copy.messages[childCheckout] = childTranscript(childCheckout, "Audit the checkout flow", now: now, running: false)
        copy.messages[childCart] = childTranscript(childCart, "Audit the cart totals", now: now, running: false)
        copy = auditTurn(now, running: false).reduce(copy) { $0.upsert(root, $1) }
        return copy.withActivity(root, .idle).withActivity(childCheckout, .idle).withActivity(childCart, .idle)
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

    private static func withChanges(_ state: PlaygroundState, todos: Bool = true) -> PlaygroundState {
        var copy = state
        copy.branch = C.branch
        copy.changedFiles = C.changedFiles
        copy.turnDiff = C.turnDiff
        if todos { copy.todos = C.todos(completed: 2, total: 5) }
        return copy
    }

    // MARK: - Everything

    private static func everything(_ now: Int64) -> PlaygroundScenario {
        // After the base history (30 minutes ago), so the timeline keeps this order.
        let start = now - 28 * minute
        let child = C.session("pg-child-everything", "Find coupon call sites", now: now, parentID: root, agentName: "explore")
        let history: [Message] = C.baseHistory(now: now) + [
            C.userMessage(
                "pg-e-u2",
                "Here is the design for the coupon row. Match the spacing of the email field above it, keep " +
                    "the Apply button inside the field on wide screens and below it on phones, and use the " +
                    "same error style as the card number field when a code is not valid. The copy is in the " +
                    "design file; ask me if anything is unclear before you change shared components.",
                start + 10 * minute,
                files: [C.image("pg-e-u2-img", "coupon-row.png", 0)]
            ),
            C.assistant("pg-e-a2", start + 10 * minute + 5_000, [
                C.reasoning(
                    "pg-e-a2-r",
                    "The design puts the button inside the field. On phones it moves below. I'll write " +
                        "the validator first so the error states are real.",
                    start: start + 10 * minute + 6_000
                ),
                C.text("pg-e-a2-x", C.markdownReply),
            ]),
            Message(
                id: "pg-e-notice",
                role: .notice,
                text: "",
                parts: [.notice(TimelineNotice(id: "pg-e-notice", kind: .agentChanged(agent: "plan", previous: "build")))],
                model: nil,
                mode: nil,
                sentAtEpochMs: start + 14 * minute
            ),
            C.userMessage(
                "pg-e-u3",
                "The stacking rules are in the attached spec.",
                start + 15 * minute,
                files: [C.pdf("pg-e-u3-pdf", "coupon-rules.pdf")]
            ),
            C.assistant("pg-e-a3", start + 15 * minute + 4_000, [
                C.bash(
                    "pg-e-t1", "npm run lint", .completed, start: start + 15 * minute + 5_000,
                    output: "> acme-web@1.0.0 lint\n> eslint src\n\n✔ No problems found"
                ),
                C.tool(
                    "pg-e-t2", "grep", .completed, start: start + 15 * minute + 8_000,
                    input: ["pattern": .string("memberDiscount")],
                    output: "src/checkout/totals.ts:22\nsrc/account/member.ts:9"
                ),
                C.tool(
                    "pg-e-t3", "glob", .completed, start: start + 15 * minute + 9_000,
                    input: ["pattern": .string("src/**/*.test.ts")],
                    output: "src/cart/cart.test.ts\nsrc/checkout/coupon.test.ts"
                ),
                C.tool(
                    "pg-e-t4", "webfetch", .completed, start: start + 15 * minute + 10_000,
                    input: ["url": .string("https://example.com/docs/coupons")],
                    output: "# Coupons\nA coupon applies after member discounts."
                ),
                C.edit("pg-e-t5", "src/checkout/totals.ts", start: start + 15 * minute + 12_000),
                C.tool(
                    "pg-e-t6", "write", .completed, start: start + 15 * minute + 14_000,
                    input: [
                        "filePath": .string("\(C.worktree)/src/checkout/coupon.ts"),
                        "content": .string("export type CouponResult = Valid | Expired | NotFound\n"),
                    ],
                    output: "Wrote file successfully."
                ),
                C.tool(
                    "pg-e-t7", "todowrite", .completed, start: start + 15 * minute + 15_000,
                    input: ["todos": C.todosJSON()],
                    output: "5 todos"
                ),
                C.bash(
                    "pg-e-t8", "npm run typecheck", .error, start: start + 15 * minute + 17_000,
                    error: "src/checkout/totals.ts(31,7): error TS2322: Type 'string' is not assignable to type 'Money'."
                ),
                C.subagentTask(
                    "pg-e-t9", child: child.id, agent: "explore", description: "Find coupon call sites",
                    .completed, start: start + 15 * minute + 20_000,
                    output: "Found 3 call sites: checkout form, cart summary, order API."
                ),
                C.text("pg-e-a3-x", "Stacking follows the spec: the coupon applies after the member discount."),
            ]),
            C.userMessage(
                "pg-e-u4",
                "/review src/checkout",
                start + 20 * minute,
                skills: [PromptSkill(id: "skill-review", name: "review", mentionText: "/review", mentionStart: 0, mentionEnd: 7)]
            ),
            C.assistant(
                "pg-e-a4", start + 20 * minute + 3_000,
                [C.text("pg-e-a4-x", "Starting the review…")],
                failure: SessionFailure(
                    type: "provider.overloaded",
                    message: "The acme provider is overloaded. Try again in a moment.",
                    status: 529,
                    providerBody: nil
                )
            ),
            C.userMessage("pg-e-u5", "Try again, please.", start + 22 * minute),
            C.assistant(
                "pg-e-a5", start + 22 * minute + 3_000,
                [
                    C.text("pg-e-a5-x", "Reviewing `src/checkout`. First the form, then"),
                    C.bash(
                        "pg-e-a5-t", "npm run test -- --watch", .error, start: start + 22 * minute + 5_000,
                        error: "Tool execution aborted", isInterrupted: true
                    ),
                ],
                stopReason: .interrupted
            ),
            C.userMessage("pg-e-u6", "Show me the three coupon states.", start + 25 * minute),
            C.assistant("pg-e-a6", start + 25 * minute + 3_000, [
                C.tool(
                    "pg-e-a6-t", "screenshot", .completed, start: start + 25 * minute + 4_000,
                    input: ["url": .string("https://example.com/checkout")],
                    output: "3 screenshots",
                    title: "Checkout states",
                    images: [
                        C.image("pg-e-img1", "valid.png", 0),
                        C.image("pg-e-img2", "expired.png", 1),
                        C.image("pg-e-img3", "unknown.png", 2),
                    ]
                ),
                C.text("pg-e-a6-x", "Valid, expired and unknown codes:\n\n" + C.longReply),
            ]),
        ]
        let turnStart = now - 30_000
        let running: [Message] = [
            C.userMessage("pg-e-u7", "Now add tests for expired codes.", turnStart),
            C.assistant("pg-e-a7", turnStart + 2_000, [
                C.reasoning("pg-e-a7-r", "I'll pin the clock so expiry is stable.", start: turnStart + 3_000),
                C.text("pg-e-a7-x", "Adding the tests. First a fixed clock", streaming: true),
                C.bash("pg-e-a7-t1", "npm run test -- coupon", .running, start: turnStart + 9_000),
                C.tool(
                    "pg-e-a7-t2", "edit", .pending, start: turnStart + 10_000,
                    input: ["filePath": .string("\(C.worktree)/src/checkout/coupon.test.ts")]
                ),
            ], running: true),
            C.userMessage("pg-e-steer", "Also cover codes typed in lower case.", now - 5_000, delivery: .steer, waitingForPickup: true),
        ]
        var initial = withChanges(state(
            "Coupon code field", now: now, history + running,
            children: [child],
            childMessages: [child.id: childTranscript(child.id, "Find coupon call sites", now: now, running: false)]
        ))
        initial = initial.withActivity(root, .running)
        return PlaygroundScenario(id: .everything, initial: initial, steps: [
            PlaygroundStep(afterMs: 3_000, label: "Steer delivered") { state, now in
                state
                    .mapMessages(root) { $0.id == "pg-e-steer" ? $0.replacing(isWaitingForPickup: false) : $0 }
                    .mapMessages(root) { message in
                        guard message.id == "pg-e-a7" else { return message }
                        return message.replacing(parts: message.parts.map { part in
                            if case let .tool(tool) = part, tool.id == "pg-e-a7-t1" {
                                return .tool(tool.replacing(status: .completed, output: "Tests: 6 passed", endMs: now))
                            }
                            return part
                        })
                    }
            },
            PlaygroundStep(afterMs: 3_000, label: "Retry countdown") { state, now in
                state.withActivity(root, .retrying(
                    attempt: 1,
                    nextAtMs: now + 10_000,
                    message: "The acme provider is overloaded",
                    errorType: "provider.overloaded",
                    actionLabel: nil,
                    actionLink: nil
                ))
            },
            PlaygroundStep(afterMs: 10_000, label: "Running again") { state, _ in state.withActivity(root, .running) },
            PlaygroundStep(afterMs: 3_000, label: "Turn finished") { state, now in
                state.upsert(root, C.assistant("pg-e-a7", turnStart + 2_000, [
                    C.reasoning("pg-e-a7-r", "I'll pin the clock so expiry is stable.", start: turnStart + 3_000),
                    C.bash("pg-e-a7-t1", "npm run test -- coupon", .completed, start: turnStart + 9_000, output: "Tests: 8 passed"),
                    C.edit("pg-e-a7-t2", "src/checkout/coupon.test.ts", start: turnStart + 10_000),
                    C.text("pg-e-a7-x", "Added 8 tests, including lower-case codes. All pass."),
                ], completedAt: now)).withActivity(root, .idle)
            },
        ])
    }

    // MARK: - Pill scenarios

    private static func quiet(_ now: Int64) -> PlaygroundScenario {
        var initial = withChanges(state("Coupon code field", now: now, C.baseHistory(now: now)))
        initial.todos = C.todos(completed: 3, total: 5)
        return PlaygroundScenario(id: .quiet, initial: initial)
    }

    private static func working(_ now: Int64) -> PlaygroundScenario {
        let messages = C.baseHistory(now: now) + runningTurn(now) + [
            queued("pg-q1", "Then update the checkout README.", now - 8_000),
            queued("pg-q2", "And bump the version to 1.4.0.", now - 4_000),
        ]
        let initial = withChanges(state("Coupon tests", now: now, messages), todos: false).withActivity(root, .running)
        return PlaygroundScenario(id: .working, initial: initial)
    }

    private static func auditState(_ now: Int64, extra: [Message] = []) -> PlaygroundState {
        auditRunning(state("Checkout audit", now: now, C.baseHistory(now: now) + auditTurn(now, running: true) + extra, children: auditChildren(now)), now: now)
    }

    private static func subagents(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .subagents, initial: auditState(now), steps: [
            PlaygroundStep(afterMs: 3_000, label: "explore asks for permission") { state, _ in
                var copy = state
                copy.permissions = [C.bashPermission("pg-perm-1", sessionID: childCheckout)]
                return copy
            },
            PlaygroundStep(afterMs: 5_000, label: "Permission answered") { state, _ in
                var copy = state
                copy.permissions = []
                return copy
            },
            PlaygroundStep(afterMs: 3_000, label: "Both subagents finish") { state, now in auditFinished(state, now: now) },
        ])
    }

    private static func needsInput(_ now: Int64) -> PlaygroundScenario {
        var initial = auditState(now)
        initial.permissions = [C.bashPermission("pg-perm-1", sessionID: childCheckout)]
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
        var initial = withChanges(auditState(now, extra: [
            queued("pg-q1", "Then update the checkout README.", now - 8_000),
            queued("pg-q2", "And bump the version to 1.4.0.", now - 4_000),
        ]))
        initial.permissions = [C.bashPermission("pg-perm-1", sessionID: childCheckout)]
        return PlaygroundScenario(
            id: .busyCorner,
            initial: initial,
            collapsesPermission: true,
            readySideQuestions: ["Which file holds the coupon rules?"],
            runningSideQuestions: ["Is the coupon applied before tax?"]
        )
    }

    private static func scrolledUp(_ now: Int64) -> PlaygroundScenario {
        func reply(_ id: String, _ text: String, _ now: Int64) -> Message {
            C.assistant(id, now, [C.read("\(id)-t", "src/cart/totals.ts", start: now), C.text("\(id)-x", text)], completedAt: now)
        }
        return PlaygroundScenario(
            id: .scrolledUp,
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
            ]
        )
    }

    private static func keyboardUp(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .keyboardUp, initial: withChanges(auditState(now)))
    }

    private static func childSession(_ now: Int64) -> PlaygroundScenario {
        PlaygroundScenario(id: .childSession, initial: auditState(now), boundSessionID: childCheckout)
    }
}
