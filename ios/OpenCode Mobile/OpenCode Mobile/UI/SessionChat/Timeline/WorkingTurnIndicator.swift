import CoreGraphics
import Foundation

/// Last-timeline-row Session Running chrome. Show iff this session's
/// status is not idle, or a large prompt body is still on the wire.
/// Optimistic busy on send is the same status. Hide when a permission
/// or question dock is waiting on the user. Streaming Thinking is the
/// same reserved footer — change the label in place. Do not collapse
/// under a thought row or the footer goes blank between labels.
/// After busy ends, show Idle for 5s, then collapse. A new send snaps back.
enum WorkingTurnIndicator {
    static let idleLabel = "Idle"
    static let thinkingLabel = "Thinking"
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

    static func isStreamingThinking(messages: [Message]) -> Bool {
        messages.contains { message in
            message.parts.contains { part in
                if case .reasoning(let reasoning) = part {
                    return reasoning.isStreaming
                }
                return false
            }
        }
    }

    static func shouldCollapseReservedRow(lastIsThinking _: Bool, label _: String?) -> Bool {
        false
    }

    static func showsIdleMark(label: String) -> Bool {
        label == idleLabel
    }

    /// Label the timeline mounts as its last row. A collapsed footer is not a
    /// row: zero-height cells are not visible cells, so a later expand could
    /// not re-measure them. Collapse = remove, expand = insert.
    static func mountedLabel(label: String?, collapsed: Bool) -> String? {
        collapsed ? nil : label
    }

    /// Fixed footer row height so the list's height estimate equals the real height and pin-to-end never needs an estimate→real correction for this row.
    static let rowHeight: CGFloat = 40

    static func labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Bool = false,
        isAwaitingUser: Bool = false,
        sendProgressPercent: Int? = nil,
        isThinking: Bool = false
    ) -> String? {
        if isAwaitingUser { return nil }
        if isCompacting { return SessionCompactChrome.statusLabel }
        if let sendProgressPercent {
            return PromptSendProgress.label(percent: sendProgressPercent)
        }
        if isThinking, statusLabel != nil { return thinkingLabel }
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
