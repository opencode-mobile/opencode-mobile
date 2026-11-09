import Foundation

/// Cold start (app plan, "Cold start"): the home shows in the first frame, from what's saved on the
/// phone; nothing waits on the network. The card area shows at once when the saved card belongs to
/// today's saved session. Otherwise it stays empty until today's session is known (found, failed or
/// signed out), then its card fades in. Coming back from the background is not a cold start: once
/// shown, the card area stays shown.
struct ColdStart: Equatable {
    private(set) var showsCard: Bool

    /// `savedCard`: today's saved session's card was read from the phone.
    init(savedCard: Bool) {
        showsCard = savedCard
    }

    /// Today's session is known. True when the card fades in: the area was empty until now.
    mutating func sessionKnown() -> Bool {
        defer { showsCard = true }
        return !showsCard
    }
}
