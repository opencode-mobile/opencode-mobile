import Foundation

/// Last-timeline-row Session Running chrome. Show iff this session's
/// status is not idle. Optimistic busy on send is the same status. Do not
/// inspect messages, streaming, tools, or thought rows. Hide only when a
/// permission or question dock is waiting on the user.
enum WorkingTurnIndicator {
    static func labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Bool = false,
        isAwaitingUser: Bool = false
    ) -> String? {
        if isAwaitingUser { return nil }
        if isCompacting { return SessionCompactChrome.statusLabel }
        return statusLabel
    }
}
