import Foundation

/// A user prompt sent with steer delivery during a run, as the timeline shows it.
/// Queued prompts never get one: they stay out of the timeline.
nonisolated struct SessionChatSteer: Equatable, Hashable, Sendable {
    /// Waiting for the next step; the server has not delivered it yet.
    var isPending: Bool
    /// First of a run of consecutive steers, so it draws the shared label.
    var showsLabel: Bool
}
