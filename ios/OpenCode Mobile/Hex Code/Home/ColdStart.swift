import Foundation

/// Cold start (app plan, "Cold start"): the home appears in one piece. Until today's session has
/// its first state (`TodaySession.hasFirstState`), at most `limit`, only the launch screen's
/// look shows: the logo on the plain background, fading out as the home appears. Then the top bar state, the goal line, the dock and the card show
/// in the same frame; past the limit the frame shows and the card fades in when it arrives. Coming
/// back from the background is not a cold start: once shown, the home stays shown.
struct ColdStart: Equatable {
    static let limit: Duration = .milliseconds(1500)

    private enum Stage {
        /// The launch screen's look: the logo on the plain background.
        case waiting
        /// The limit passed: the top bar, goal line and dock, without the card yet.
        case frame
        /// Everything.
        case whole
    }

    private var stage: Stage = .waiting

    var showsHome: Bool { stage != .waiting }
    var showsCard: Bool { stage == .whole }

    /// The first state is ready. True when the card fades in: the frame was already on screen.
    mutating func ready() -> Bool {
        defer { stage = .whole }
        return stage == .frame
    }

    /// The limit passed.
    mutating func timedOut() {
        if stage == .waiting { stage = .frame }
    }
}
