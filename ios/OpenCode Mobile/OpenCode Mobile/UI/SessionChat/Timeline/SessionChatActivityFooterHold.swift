import Foundation

/// What a live activity card's footer band shows (#779): a tool, running
/// (title, "+N", elapsed) or in its settled form (settled title only), or idle text.
enum SessionChatActivityFooter: Equatable, Sendable {
    case tool(Tool)
    case idle(String)

    struct Tool: Equatable, Sendable {
        let stepID: String
        let icon: OpenCodeActivityLineIcon
        let title: String
        let running: Bool
        let extraRunning: Int
        let startedAtMs: Int64?
    }

    static let thinking = SessionChatActivityCardBlock.idleText(hasRunningChildren: false, awaitingUser: false)

    /// The footer the steps ask for right now: the latest running tool, else idle text.
    static func desired(_ steps: [SessionChatStep]) -> SessionChatActivityFooter {
        let card = SessionChatActivityCardBlock(id: "", steps: steps)
        if let latest = card.latestRunningTool {
            return .tool(Tool(
                stepID: latest.id,
                icon: latest.activityIcon,
                title: latest.runningTitle,
                running: true,
                extraRunning: card.runningTools.count - 1,
                startedAtMs: latest.startedAtMs
            ))
        }
        return .idle(SessionChatActivityCardBlock.idleText(
            hasRunningChildren: !card.runningChildren.isEmpty,
            awaitingUser: false
        ))
    }

    /// `step` in its settled form: settled title, no "+N", no elapsed.
    static func settled(_ step: SessionChatStep) -> SessionChatActivityFooter {
        .tool(Tool(
            stepID: step.id,
            icon: step.activityIcon,
            title: step.settledTitle,
            running: false,
            extraRunning: 0,
            startedAtMs: nil
        ))
    }

    /// Everything the band paints, for timeline row invalidation.
    var revisionKey: String {
        switch self {
        case .tool(let tool): "\(contentKey):\(tool.title):\(tool.extraRunning):\(tool.startedAtMs ?? -1)"
        case .idle: contentKey
        }
    }

    /// Identity the footer crossfades on: a tool's step and form, or the idle
    /// text. A running tool's "+N" and elapsed change in place.
    var contentKey: String {
        switch self {
        case .tool(let tool): "tool:\(tool.stepID):\(tool.running ? "running" : "settled")"
        case .idle(let text): "idle:\(text)"
        }
    }
}

/// Footer hold for one live activity card (#779). What the band shows is
/// latched for `hold` so short tools stay readable:
///
/// 1. The first value shows at once.
/// 2. A change inside the hold waits; only the latest one counts.
/// 3. A shown tool that settles inside the hold switches in place to its
///    settled form, without restarting the hold.
/// 4. When the hold ends, the latest value shows and the hold restarts. When
///    nothing runs and a tool that started after the shown one was never shown,
///    the newest such tool shows settled for one hold first (one catch-up per hold).
/// 5. An unchanged value is a no-op.
///
/// Pure and clock-injected: `submit` takes the card's visible steps (entry
/// debounced steps left out) on every update and timer tick; `expiresAtMs`
/// says when to tick next.
struct SessionChatActivityFooterHold: Sendable {
    static let holdMs: Int64 = 1_200

    private(set) var displayed: SessionChatActivityFooter?
    private var shownAtMs: Int64 = 0
    private var holding = false
    private var catchUp = false
    private var steps: [SessionChatStep] = []
    /// Tool steps on the card when `displayed` was latched; a catch-up only picks tools that came later.
    private var knownToolIDs: Set<String> = []
    private var shownToolIDs: Set<String> = []
    private let holdMs: Int64

    init(holdMs: Int64 = SessionChatActivityFooterHold.holdMs) {
        self.holdMs = holdMs
    }

    /// When the hold ends, or nil with no hold running.
    var expiresAtMs: Int64? { holding ? shownAtMs + holdMs : nil }

    /// The latest value waiting for the hold to end, or nil.
    var pending: SessionChatActivityFooter? {
        guard let displayed else { return nil }
        let desired = SessionChatActivityFooter.desired(steps)
        return desired.contentKey == displayed.contentKey ? nil : desired
    }

    @discardableResult
    mutating func submit(_ steps: [SessionChatStep], nowMs: Int64) -> SessionChatActivityFooter {
        self.steps = steps
        let desired = SessionChatActivityFooter.desired(steps)
        guard let shown = displayed else { return show(desired, nowMs: nowMs, catchUp: false) }
        let current = refreshed(shown, desired: desired)
        displayed = current
        if holding, nowMs < shownAtMs + holdMs { return current }
        holding = false
        return advance(nowMs: nowMs)
    }

    /// Ends the hold when it is due; same as a `submit` of the last steps.
    @discardableResult
    mutating func expire(nowMs: Int64) -> SessionChatActivityFooter? {
        guard displayed != nil else { return nil }
        return submit(steps, nowMs: nowMs)
    }

    private mutating func advance(nowMs: Int64) -> SessionChatActivityFooter {
        let desired = SessionChatActivityFooter.desired(steps)
        if case .idle = desired, !catchUp, let step = catchUpStep() {
            return show(.settled(step), nowMs: nowMs, catchUp: true)
        }
        catchUp = false
        if let displayed, desired.contentKey == displayed.contentKey { return displayed }
        return show(desired, nowMs: nowMs, catchUp: false)
    }

    /// The same running tool picks up its new "+N"; a shown tool that settled turns settled (rule 3).
    private func refreshed(
        _ shown: SessionChatActivityFooter,
        desired: SessionChatActivityFooter
    ) -> SessionChatActivityFooter {
        guard case .tool(let tool) = shown, tool.running else { return shown }
        if case .tool(let next) = desired, next.stepID == tool.stepID { return desired }
        guard let step = steps.first(where: { $0.id == tool.stepID }) else { return shown }
        return step.status.isInFlight ? shown : .settled(step)
    }

    private func catchUpStep() -> SessionChatStep? {
        steps.enumerated()
            .filter { _, step in
                step.kind == .tool && !step.status.isInFlight
                    && !knownToolIDs.contains(step.id) && !shownToolIDs.contains(step.id)
            }
            .max { lhs, rhs in
                (lhs.element.startedAtMs ?? .min, lhs.offset) < (rhs.element.startedAtMs ?? .min, rhs.offset)
            }?
            .element
    }

    private mutating func show(
        _ footer: SessionChatActivityFooter,
        nowMs: Int64,
        catchUp: Bool
    ) -> SessionChatActivityFooter {
        displayed = footer
        shownAtMs = nowMs
        holding = true
        self.catchUp = catchUp
        knownToolIDs = Set(steps.filter { $0.kind == .tool }.map(\.id))
        if case .tool(let tool) = footer { shownToolIDs.insert(tool.stepID) }
        return footer
    }
}

/// Step entry debounce (#779): a tool or thought renders running only once the
/// client has seen it in flight for `delayMs`; before that it is hidden, and
/// one that settles sooner first appears settled. Child sessions are never
/// hidden. On the first update (history, reconnect) a step the server says
/// started at least `delayMs` ago shows at once.
///
/// Pure and clock-injected: `submit` takes every step on screen on every
/// update and timer tick and returns the ids to hide; `nextDueMs` says when to tick next.
struct SessionChatStepEntryDebounce: Sendable {
    static let delayMs: Int64 = 300

    private let delayMs: Int64
    private var firstSeenMs: [String: Int64] = [:]
    private var primed = false
    /// Hidden step ids from the last `submit`.
    private(set) var hidden: Set<String> = []
    /// When the next hidden step comes due, or nil.
    private(set) var nextDueMs: Int64?

    init(delayMs: Int64 = SessionChatStepEntryDebounce.delayMs) {
        self.delayMs = delayMs
    }

    @discardableResult
    mutating func submit(_ steps: [SessionChatStep], nowMs: Int64) -> Set<String> {
        let inFlight = steps.filter { $0.kind != .childSession && $0.status.isInFlight }
        let inFlightIDs = Set(inFlight.map(\.id))
        firstSeenMs = firstSeenMs.filter { inFlightIDs.contains($0.key) }
        let firstUpdate = !primed
        primed = true
        var nextHidden: Set<String> = []
        var due: Int64?
        for step in inFlight {
            let seen: Int64
            if let known = firstSeenMs[step.id] {
                seen = known
            } else {
                if firstUpdate, let started = step.startedAtMs, nowMs - started >= delayMs {
                    seen = nowMs - delayMs
                } else {
                    seen = nowMs
                }
                firstSeenMs[step.id] = seen
            }
            let shownAt = seen + delayMs
            if nowMs < shownAt {
                nextHidden.insert(step.id)
                due = min(due ?? shownAt, shownAt)
            }
        }
        hidden = nextHidden
        nextDueMs = due
        return nextHidden
    }
}

extension SessionChatStepRailBlock {
    /// The rail without the entry-debounced steps in `hidden`, branches
    /// recomputed; "Thinking…" follows the visible steps. `self` when nothing is hidden.
    func hiding(_ hidden: Set<String>) -> SessionChatStepRailBlock {
        guard !hidden.isEmpty, steps.contains(where: { hidden.contains($0.id) }) else { return self }
        let visible = SessionChatTimelineBlocks.markBranches(steps.filter { !hidden.contains($0.id) })
        return SessionChatStepRailBlock(id: id, steps: visible, isLive: isLive)
    }
}
