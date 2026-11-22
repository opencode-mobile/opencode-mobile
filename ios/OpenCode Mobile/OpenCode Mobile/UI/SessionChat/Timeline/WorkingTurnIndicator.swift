import CoreGraphics
import Foundation

/// The lifecycle of the turn pill. A turn is mounted while the session is not idle, a large
/// prompt body is on the wire, or a permission or question waits on the user. When it goes
/// idle the last label holds through a short settle, so a turn that resumes shows no Done.
/// Then Done holds and fades. A stopped turn, or one that ended while disconnected, leaves
/// quietly, and a failed turn hands the pill to its failure at once.
enum WorkingTurnIndicator {
    static let idleLabel = "Idle"
    /// How long the last active label stays after the session goes idle.
    static let turnSettleMilliseconds: Int64 = 500
    static let turnDoneHoldMilliseconds: Int64 = 3_000
    static let turnFadeMilliseconds: Int64 = 240
    /// How long after a reconnect an idle status still counts as "ended while disconnected".
    /// The status refresh that follows a reconnect lands inside this window.
    static let reconnectConfirmMilliseconds: Int64 = 5_000

    struct Chrome: Equatable {
        var label: String?
        var fading: Bool

        static let unmounted = Chrome(label: nil, fading: false)
    }

    /// The turn went idle at `startedAtMs` showing `label`. `quietFadeAtMs` is when a quiet
    /// ending started to fade; nil while the turn is on its way to Done.
    struct Hold: Equatable {
        var startedAtMs: Int64
        var label: String
        var quietFadeAtMs: Int64?
    }

    struct Resolution: Equatable {
        var chrome: Chrome
        var hold: Hold?
        var wakeAfterMs: Int64?
    }

    /// How the turn that just went idle ended.
    enum Ending: Equatable {
        /// Finished normally: settle, then Done.
        case completed
        /// Stopped by the user, or ended while disconnected: settle, then leave with no Done.
        case quiet
        /// Failed: the failure state replaces the pill immediately.
        case failed
    }

    static func showsIdleMark(label: String) -> Bool {
        label == idleLabel
    }

    /// Fixed height of the `OpenCodeWorkingIndicator` component.
    static let rowHeight: CGFloat = 40

    /// The active label, highest priority first: Retrying or Compacting, Sending, Working. A
    /// pending request keeps the turn active even if the status is momentarily nil.
    static func labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Bool = false,
        isRetrying: Bool = false,
        isAwaitingUser: Bool = false,
        sendProgressPercent: Int? = nil
    ) -> String? {
        if isCompacting { return SessionCompactChrome.statusLabel }
        if isRetrying, let statusLabel { return statusLabel }
        if let sendProgressPercent {
            return PromptSendProgress.label(percent: sendProgressPercent)
        }
        return statusLabel ?? (isAwaitingUser ? OpenCodeActivityText.working : nil)
    }

    /// Whether the user stopped the last turn: its newest assistant reply carries the server's
    /// interrupted stop reason, with no sent prompt after it.
    static func lastTurnInterrupted(_ messages: [Message]) -> Bool {
        for message in messages.reversed() {
            switch message.role {
            case .assistant:
                return message.stopReason == .interrupted
            case .user:
                if !message.isWaitingForPickup { return false }
            default:
                continue
            }
        }
        return false
    }

    /// The next pill chrome. `connectionLost` keeps the last state while the status cannot be
    /// trusted. `hideImmediately` is an unbound session.
    static func resolveFooter(
        activeLabel: String?,
        ending: Ending = .completed,
        connectionLost: Bool = false,
        hideImmediately: Bool,
        previous: Chrome,
        hold: Hold?,
        nowMs: Int64
    ) -> Resolution {
        if hideImmediately { return Resolution(chrome: .unmounted, hold: nil) }
        if let activeLabel {
            return Resolution(chrome: Chrome(label: activeLabel, fading: false), hold: nil)
        }
        if hold == nil, previous.label == nil { return Resolution(chrome: .unmounted, hold: nil) }
        if ending == .failed { return Resolution(chrome: .unmounted, hold: nil) }
        if hold == nil, connectionLost { return Resolution(chrome: previous, hold: nil) }
        var next = hold ?? Hold(startedAtMs: nowMs, label: previous.label ?? "")
        let settleEndsAt = next.startedAtMs + turnSettleMilliseconds
        if nowMs < settleEndsAt {
            return Resolution(
                chrome: Chrome(label: next.label, fading: false),
                hold: next,
                wakeAfterMs: settleEndsAt - nowMs
            )
        }
        let doneFadesAt = settleEndsAt + turnDoneHoldMilliseconds
        if ending == .quiet, next.quietFadeAtMs == nil, nowMs < doneFadesAt {
            next.quietFadeAtMs = nowMs
        }
        if let quietFadeAt = next.quietFadeAtMs {
            let remainingMs = quietFadeAt + turnFadeMilliseconds - nowMs
            if remainingMs <= 0 { return Resolution(chrome: .unmounted, hold: nil) }
            // A stop reason that arrives after Done showed fades Done, not the older label.
            let label = previous.label == idleLabel ? idleLabel : next.label
            return Resolution(chrome: Chrome(label: label, fading: true), hold: next, wakeAfterMs: remainingMs)
        }
        if nowMs < doneFadesAt {
            return Resolution(
                chrome: Chrome(label: idleLabel, fading: false),
                hold: next,
                wakeAfterMs: doneFadesAt - nowMs
            )
        }
        let fadeEndsAt = doneFadesAt + turnFadeMilliseconds
        if nowMs < fadeEndsAt {
            return Resolution(
                chrome: Chrome(label: idleLabel, fading: true),
                hold: next,
                wakeAfterMs: fadeEndsAt - nowMs
            )
        }
        return Resolution(chrome: .unmounted, hold: nil)
    }
}
