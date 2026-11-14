import Foundation

/// What the home top bar says about the agent. The first that applies: Reconnecting… while the
/// event stream is down; Needs input while a question or approval is pending; Working `m:ss` while
/// the session is busy, timed from when the turn started; else Idle.
enum AgentState: Equatable, Sendable {
    /// Cold start: until the stream first reads the state back (app plan, "Cold start").
    case connecting
    case reconnecting
    case needsInput
    case working(since: Date)
    case idle

    static func resolve(streamUp: Bool, pendingInput: Bool, busySince: Date?) -> AgentState {
        if !streamUp { return .reconnecting }
        if pendingInput { return .needsInput }
        if let busySince { return .working(since: busySince) }
        return .idle
    }

    /// The top bar's state slot at `date`.
    func topBar(at date: Date) -> HexTopBarState {
        switch self {
        case .connecting:
            HexTopBarState(tone: .connecting, label: String(localized: "home.state.connecting", defaultValue: "Connecting…"))
        case .reconnecting:
            HexTopBarState(tone: .reconnecting, label: String(localized: "home.state.reconnecting", defaultValue: "Reconnecting…"))
        case .needsInput:
            HexTopBarState(tone: .needsYou, label: String(localized: "home.state.needs_input", defaultValue: "Needs input"))
        case let .working(since):
            HexTopBarState(
                tone: .working,
                label: String(localized: "home.state.working", defaultValue: "Working"),
                timer: Self.clock(date.timeIntervalSince(since)),
                spoken: Self.spokenWorking(date.timeIntervalSince(since))
            )
        case .idle:
            HexTopBarState(tone: .idle, label: String(localized: "home.state.idle", defaultValue: "Idle"))
        }
    }

    /// "Working, 2 minutes": whole minutes, so VoiceOver doesn't hear it change every second.
    static func spokenWorking(_ elapsed: TimeInterval) -> String {
        let minutes = max(0, Int(elapsed / 60))
        let time = minutes == 0
            ? String(localized: "a11y.working.short", defaultValue: "less than a minute")
            : Duration.seconds(minutes * 60).formatted(.units(allowed: [.hours, .minutes], width: .wide))
        return String(localized: "a11y.working", defaultValue: "Working, \(time)")
    }

    /// Announced when the state becomes Needs input or Reconnecting.
    var announcement: String? {
        switch self {
        case .needsInput: String(localized: "home.state.needs_input", defaultValue: "Needs input")
        case .reconnecting: String(localized: "home.state.reconnecting", defaultValue: "Reconnecting…")
        default: nil
        }
    }

    /// Whole elapsed seconds as `m:ss` (0:07, 4:12, 75:03); never negative.
    static func clock(_ elapsed: TimeInterval) -> String {
        let total = max(0, Int(elapsed.rounded(.down)))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
