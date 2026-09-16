import Foundation

/// Last-timeline-row Session Running chrome. Show iff this session's
/// status is not idle, or a large prompt body is still on the wire.
/// Optimistic busy on send is the same status. Hide when a permission
/// or question dock is waiting on the user. When the last painted row
/// is Thinking, keep this label so the timeline can collapse the reserved
/// Working row in place — do not drop the list identity.
/// Do not hide for settled Thought, tools, or streaming text.
enum WorkingTurnIndicator {
    static let idleHoldMilliseconds: Int64 = 1_000

    struct Chrome: Equatable {
        var label: String?
        var collapsed: Bool
        var fading: Bool

        static let unmounted = Chrome(label: nil, collapsed: false, fading: false)
    }

    struct Hold: Equatable {
        var label: String
        var startedAtMs: Int64
        var fromCollapsed: Bool
    }

    struct Resolution: Equatable {
        var chrome: Chrome
        var hold: Hold?
        var wakeAfterMs: Int64?
    }

    static func lastIsThinking(messages: [SessionChatRow]) -> Bool {
        guard case .reasoning(_, _, let isStreaming, _)? = messages.last?.blocks.last else {
            return false
        }
        return isStreaming
    }

    static func shouldCollapseReservedRow(lastIsThinking: Bool, label: String?) -> Bool {
        lastIsThinking && label != nil
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
        let nextHold = hold ?? Hold(
            label: previous.label ?? "",
            startedAtMs: nowMs,
            fromCollapsed: previous.collapsed
        )
        if nextHold.fromCollapsed {
            return Resolution(
                chrome: Chrome(label: nextHold.label, collapsed: true, fading: false),
                hold: nextHold
            )
        }
        let remainingMs = idleHoldMilliseconds - (nowMs - nextHold.startedAtMs)
        if remainingMs > 0 {
            return Resolution(
                chrome: Chrome(label: nextHold.label, collapsed: false, fading: true),
                hold: nextHold,
                wakeAfterMs: remainingMs
            )
        }
        return Resolution(
            chrome: Chrome(label: nextHold.label, collapsed: true, fading: false),
            hold: nextHold
        )
    }
}
