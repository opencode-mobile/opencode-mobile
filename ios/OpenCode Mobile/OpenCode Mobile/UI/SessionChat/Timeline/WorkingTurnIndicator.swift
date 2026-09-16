import CoreGraphics
import Foundation

/// Last-timeline-row Session Running chrome. Show iff this session's
/// status is not idle, or a large prompt body is still on the wire.
/// Optimistic busy on send is the same status. Hide when a permission
/// or question dock is waiting on the user. When the last painted row
/// is Thinking, keep this label so the timeline can collapse the reserved
/// Working row in place — do not drop the list identity.
/// After busy ends, show Idle for 5s, then collapse. A new send snaps back.
enum WorkingTurnIndicator {
    static let idleLabel = "Idle"
    static let idleHoldMilliseconds: Int64 = 5_000

    struct Chrome: Equatable {
        var label: String?
        var collapsed: Bool
        var fading: Bool

        static let unmounted = Chrome(label: nil, collapsed: false, fading: false)
    }

    struct Hold: Equatable {
        var startedAtMs: Int64
    }

    struct Resolution: Equatable {
        var chrome: Chrome
        var hold: Hold?
        var wakeAfterMs: Int64?
    }

    static func lastIsThinking(messages: [SessionChatRow]) -> Bool {
        messages.contains { message in
            message.blocks.contains { block in
                if case .reasoning(_, _, let isStreaming, _) = block {
                    return isStreaming
                }
                return false
            }
        }
    }

    static func shouldCollapseReservedRow(lastIsThinking: Bool, label: String?) -> Bool {
        lastIsThinking && label != nil
    }

    static func showsIdleMark(label: String) -> Bool {
        label == idleLabel
    }

    /// Space the timeline must lift so the SwiftUI Working overlay sits on the
    /// composer pin line instead of inside the collection view's bottom inset.
    static let overlayFallbackHeight: CGFloat = 36

    static func overlayLift(label: String?, collapsed: Bool, measuredHeight: CGFloat) -> CGFloat {
        guard label != nil, !collapsed else { return 0 }
        return measuredHeight > 0.5 ? measuredHeight : overlayFallbackHeight
    }

    static func labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Bool = false,
        isAwaitingUser: Bool = false,
        sendProgressPercent: Int? = nil
    ) -> String? {
        if isAwaitingUser { return nil }
        if isCompacting { return SessionCompactChrome.statusLabel }
        if let sendProgressPercent {
            return PromptSendProgress.label(percent: sendProgressPercent)
        }
        return statusLabel
    }

    static func resolveFooter(
        activeLabel: String?,
        lastIsThinking: Bool,
        hideImmediately: Bool,
        previous: Chrome,
        hold: Hold?,
        nowMs: Int64
    ) -> Resolution {
        if hideImmediately {
            return Resolution(chrome: .unmounted, hold: nil)
        }
        if let activeLabel {
            return Resolution(
                chrome: Chrome(
                    label: activeLabel,
                    collapsed: shouldCollapseReservedRow(lastIsThinking: lastIsThinking, label: activeLabel),
                    fading: false
                ),
                hold: nil
            )
        }
        if previous.label == nil, hold == nil {
            return Resolution(chrome: .unmounted, hold: nil)
        }
        let nextHold = hold ?? Hold(startedAtMs: nowMs)
        let remainingMs = idleHoldMilliseconds - (nowMs - nextHold.startedAtMs)
        if remainingMs > 0 {
            return Resolution(
                chrome: Chrome(label: idleLabel, collapsed: false, fading: false),
                hold: nextHold,
                wakeAfterMs: remainingMs
            )
        }
        return Resolution(
            chrome: Chrome(label: idleLabel, collapsed: true, fading: false),
            hold: nextHold
        )
    }
}
