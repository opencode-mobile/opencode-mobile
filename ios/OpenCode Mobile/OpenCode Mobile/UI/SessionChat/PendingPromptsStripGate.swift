import Foundation

/// When the pending prompts strip mounts. A v2 prompt to an idle session passes
/// through the server inbox for a few milliseconds (`inbox.enqueued`, then
/// `inbox.delivered`), so a server-waiting prompt only mounts the strip once it
/// has waited past a short grace. Otherwise the strip shows for a frame and
/// jolts the timeline inset. The app's own busy sends (settling rows) mount at
/// once. A mounted strip follows the list until it empties.
struct PendingPromptsStripGate: Equatable {
    static let graceMilliseconds: Int64 = 300

    struct Resolution: Equatable {
        var prompts: [PendingPrompt]
        var wakeAfterMs: Int64?
    }

    private(set) var waitingSinceMs: Int64?
    private(set) var isMounted = false

    mutating func resolve(_ prompts: [PendingPrompt], nowMs: Int64) -> Resolution {
        guard !prompts.isEmpty else {
            self = PendingPromptsStripGate()
            return Resolution(prompts: [], wakeAfterMs: nil)
        }
        if isMounted || prompts.contains(where: \.isSettling) {
            isMounted = true
            return Resolution(prompts: prompts, wakeAfterMs: nil)
        }
        let since = waitingSinceMs ?? nowMs
        waitingSinceMs = since
        let waited = nowMs - since
        guard waited >= Self.graceMilliseconds else {
            return Resolution(prompts: [], wakeAfterMs: Self.graceMilliseconds - waited)
        }
        isMounted = true
        return Resolution(prompts: prompts, wakeAfterMs: nil)
    }
}
