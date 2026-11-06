import Foundation

/// Where a draft goes: today's session (`TodaySession`), or a fake in tests.
@MainActor
protocol MessageSending: AnyObject {
    /// The agent is working (or waiting on you): a message can go after this turn or now.
    var agentBusy: Bool { get }
    /// Sends a message; returns its inbox id.
    func send(_ text: String, delivery: Delivery) async throws -> String
    /// Takes back a message still waiting to send.
    func cancelWaiting(_ itemID: String) async throws
    /// Returns once the event stream is up.
    func waitForConnection() async
}

extension TodaySession: MessageSending {}

/// The After turn / Now switch on a draft while the agent works.
enum SendMode: Hashable, Sendable {
    /// `queue`: after this turn (the default, and what every send goes back to).
    case afterTurn
    /// `steer`, held on the phone for 3 s with Undo, starting `[Correction] `.
    case now
}

/// A Now held on the phone before it goes.
struct HeldMessage: Equatable {
    let id = UUID()
    let text: String
    let draft: DictationDraft
}
