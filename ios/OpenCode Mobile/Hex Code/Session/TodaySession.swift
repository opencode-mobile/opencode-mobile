import Foundation
import Observation

/// Today's session as home shows it: whether it opened, the agent's state for the top bar, a
/// pending question or approval, the waiting messages, and the status summary. Round 2 feeds it
/// from OpenCode (the event stream and the inbox); round 1 fills it with fakes.
@MainActor
@Observable
final class TodaySession {
    enum Setup: Equatable {
        /// Finding or creating today's session. Silent: the status area stays empty.
        case opening
        /// "Couldn't open today's session" with Try again; `retrying` while Try again runs.
        case failed(retrying: Bool)
        case ready(sessionID: String)
    }

    /// A question or approval the agent is waiting for.
    struct PendingInput: Equatable {
        enum Kind: Equatable {
            case question
            case approval
        }

        var kind: Kind
        /// The question's text, or what the approval is for.
        var text: String
    }

    private(set) var setup: Setup = .opening
    var streamUp = true
    var pendingInput: PendingInput?
    /// When the current turn started; nil while the session is idle.
    var busySince: Date?
    /// Messages queued in the inbox, sent after this turn.
    var waitingCount = 0
    let summary: StatusSummaryModel

    @ObservationIgnored private let dailySetup: DailySessionSetup
    @ObservationIgnored private let directory: () -> String
    @ObservationIgnored private let toasts: HexToaster
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeZone: TimeZone

    init(
        setup: DailySessionSetup,
        summary: StatusSummaryModel,
        directory: @escaping () -> String,
        toasts: HexToaster,
        now: @escaping () -> Date = Date.init,
        timeZone: TimeZone = .current
    ) {
        dailySetup = setup
        self.summary = summary
        self.directory = directory
        self.toasts = toasts
        self.now = now
        self.timeZone = timeZone
    }

    var agentState: AgentState {
        AgentState.resolve(streamUp: streamUp, pendingInput: pendingInput != nil, busySince: busySince)
    }

    /// On open and on each return to the foreground: find or create today's session. A new one
    /// shows the empty card (no request); an existing one refreshes the summary.
    func open() async {
        if case .failed = setup { setup = .failed(retrying: true) }
        let outcome = await dailySetup.open(today: DailySession.title(for: now(), timeZone: timeZone), directory: directory())
        switch outcome {
        case .failed:
            setup = .failed(retrying: false)
        case let .opened(sessionID, created, showsNewDayNotice):
            setup = .ready(sessionID: sessionID)
            if showsNewDayNotice { toasts.show(Self.newDayNotice()) }
            if created {
                summary.showEmpty(timeZone: timeZone)
            } else {
                await summary.refresh()
            }
        }
    }

    /// Try again, after "Couldn't open today's session".
    func retry() async {
        guard case .failed(retrying: false) = setup else { return }
        await open()
    }

    /// Tapping Needs input or its hint card opens the question sheet, which comes in M4.
    func openPendingInput() {}

    /// Tapping the progress or the waiting line opens Details, which comes in M4.
    func openDetails() {}

    static func newDayNotice() -> HexToastItem {
        HexToastItem(
            tone: .sun,
            title: String(localized: "home.newday.title", defaultValue: "New day, new session"),
            detail: String(localized: "home.newday.detail", defaultValue: "Yesterday’s is kept on your computer."),
            duration: 3.6
        )
    }
}
