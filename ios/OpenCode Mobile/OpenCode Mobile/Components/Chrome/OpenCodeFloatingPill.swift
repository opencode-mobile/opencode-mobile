import SwiftUI

/// What a floating pill shows. Every layout shares the btw pill height, type and chrome.
enum OpenCodeFloatingPillLayout: Equatable {
    /// Leading icon and a label, e.g. a branch or todos pill.
    case iconLabel(icon: OpenCodeIcon, label: String)
    /// Round, icon only. `accessibilityLabel` is what VoiceOver speaks.
    case iconOnly(icon: OpenCodeIcon, accessibilityLabel: String)
    /// Live counter such as "3 subagents". Shows a spinner while live; with
    /// `waiting`, needs attention swaps it for a pulsing dot.
    case counter(label: String, waiting: Bool = false)
    /// Label with a trailing close ✕ that calls `onClose`.
    case closable(label: String)
    /// Plain short label with no mark and no close, e.g. a "+3" overflow button.
    case label(String)

    var label: String {
        switch self {
        case let .iconLabel(_, label), let .counter(label, _), let .closable(label), let .label(label): label
        case let .iconOnly(_, accessibilityLabel): accessibilityLabel
        }
    }
}

/// Where the thing a floating pill stands for is.
enum OpenCodeFloatingPillState: CaseIterable, Equatable {
    /// Work is running.
    case live
    /// Something is ready to open.
    case ready
    /// Waiting on the user: amber text and a thin amber ring.
    case needsAttention
    /// Something failed.
    case error
    /// Finished: a green check.
    case done

    /// Spoken after the label.
    var spokenName: String {
        switch self {
        case .live: "In progress"
        case .ready: "Ready"
        case .needsAttention: "Needs attention"
        case .error: "Error"
        case .done: "Done"
        }
    }
}

/// Leading mark of a floating pill, resolved from layout and state.
enum OpenCodeFloatingPillLeading: Equatable {
    case none
    case icon(OpenCodeIcon)
    case spinner
    case pulsingDot
    case doneCheck
    /// Compact fallback for layouts without a mark: a small dot in the state's tone.
    case stateDot
}

/// Semantic colour role of a floating pill's text and icon.
enum OpenCodeFloatingPillTone: Equatable {
    case base
    case strong
    case warning
    case error
}

/// Pure mapping from layout and state to what the pill draws and speaks.
enum OpenCodeFloatingPillStyle {
    static func leading(layout: OpenCodeFloatingPillLayout, state: OpenCodeFloatingPillState) -> OpenCodeFloatingPillLeading {
        if state == .done { return .doneCheck }
        switch layout {
        case let .iconLabel(icon, _), let .iconOnly(icon, _):
            return .icon(icon)
        case let .counter(_, waiting):
            switch state {
            case .live: return .spinner
            case .needsAttention: return waiting ? .pulsingDot : .spinner
            case .ready, .error, .done: return .none
            }
        case .closable, .label:
            return .none
        }
    }

    /// Mark a compact pill draws in its round body: the regular mark, or a state dot when there is none.
    static func compactLeading(layout: OpenCodeFloatingPillLayout, state: OpenCodeFloatingPillState) -> OpenCodeFloatingPillLeading {
        let mark = leading(layout: layout, state: state)
        return mark == .none ? .stateDot : mark
    }

    /// Whether the pill shows its ✕: closable layouts with a close action, and never when compact.
    static func showsClose(layout: OpenCodeFloatingPillLayout, compact: Bool, hasCloseAction: Bool) -> Bool {
        guard case .closable = layout else { return false }
        return hasCloseAction && !compact
    }

    /// Live and waiting counters tell assistive tech their text changes often. Other pills stay quiet.
    static func announcesUpdates(layout: OpenCodeFloatingPillLayout, state: OpenCodeFloatingPillState) -> Bool {
        guard case .counter = layout else { return false }
        return state == .live || state == .needsAttention
    }

    static func tone(_ state: OpenCodeFloatingPillState) -> OpenCodeFloatingPillTone {
        switch state {
        case .live: .base
        case .ready, .done: .strong
        case .needsAttention: .warning
        case .error: .error
        }
    }

    static func hasRing(_ state: OpenCodeFloatingPillState) -> Bool {
        state == .needsAttention
    }

    /// What the pill speaks: the caller's override, or "label, state".
    static func accessibilityLabel(
        layout: OpenCodeFloatingPillLayout,
        state: OpenCodeFloatingPillState,
        override: String? = nil
    ) -> String {
        override ?? "\(layout.label), \(state.spokenName)"
    }

    static func closeAccessibilityLabel(layout: OpenCodeFloatingPillLayout, override: String? = nil) -> String {
        "Dismiss \(override ?? layout.label)"
    }
}

/// Shared floating pill for Session Chat: one look, five layouts, five states.
/// Without `action` the pill is a status, not a button. `compact` shrinks it to its
/// round mark (icon, spinner, dot or check) and hides the text and ✕, still speaking
/// the full label. `accessibilityLabel` replaces the default "label, state" speech.
struct OpenCodeFloatingPill: View {
    let layout: OpenCodeFloatingPillLayout
    let state: OpenCodeFloatingPillState
    let compact: Bool
    let accessibilityLabelOverride: String?
    let action: (() -> Void)?
    let onClose: (() -> Void)?

    init(
        layout: OpenCodeFloatingPillLayout,
        state: OpenCodeFloatingPillState,
        compact: Bool = false,
        accessibilityLabel: String? = nil,
        action: (() -> Void)? = nil,
        onClose: (() -> Void)? = nil
    ) {
        self.layout = layout
        self.state = state
        self.compact = compact
        self.accessibilityLabelOverride = accessibilityLabel
        self.action = action
        self.onClose = onClose
    }

    var body: some View {
        HStack(spacing: 0) {
            if let action {
                Button(action: action) { content }
                    .buttonStyle(OpenCodeFloatingPillPressStyle())
                    .accessibilityLabel(accessibilityLabel)
                    .accessibilityAddTraits(updateTraits)
                    .openCodeKeyboardFocusable(.capsule, activate: action)
            } else {
                content
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(accessibilityLabel)
                    .accessibilityAddTraits(updateTraits)
            }
            if OpenCodeFloatingPillStyle.showsClose(layout: layout, compact: compact, hasCloseAction: onClose != nil),
               let onClose {
                Button(action: onClose) {
                    OpenCodeIconImage(icon: .close, size: Self.iconSize)
                        .foregroundStyle(OpenCodeColors.iconBase)
                        .frame(width: Self.closeTouchTarget, height: Self.closeTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(OpenCodeFloatingPillPressStyle())
                // 44 pt hit area that lays out as the 36 pt visual square.
                .padding(-(Self.closeTouchTarget - Self.minHeight) / 2)
                .padding(.leading, -Self.paddingHorizontal / 2)
                .accessibilityLabel(
                    OpenCodeFloatingPillStyle.closeAccessibilityLabel(layout: layout, override: accessibilityLabelOverride)
                )
                .openCodeKeyboardFocusable(.circle, activate: onClose)
            }
        }
        .openCodeFloatingPillChrome(enabled: true)
        .overlay {
            if OpenCodeFloatingPillStyle.hasRing(state) {
                Capsule()
                    .strokeBorder(OpenCodeColors.warning, lineWidth: Self.ringWidth)
                    .allowsHitTesting(false)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if compact || isIconOnly {
            leadingMark
                .foregroundStyle(contentColor)
                .frame(width: Self.minHeight, height: Self.minHeight)
                .contentShape(Circle())
        } else {
            HStack(spacing: Self.iconSpacing) {
                leadingMark
                Text(layout.label)
                    .font(OpenCodeTypography.button)
                    .lineLimit(1)
            }
            .foregroundStyle(contentColor)
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.vertical, Self.paddingVertical)
            .frame(minHeight: Self.minHeight)
            .contentShape(Capsule())
        }
    }

    private var isIconOnly: Bool {
        if case .iconOnly = layout { return true }
        return false
    }

    private var updateTraits: AccessibilityTraits {
        OpenCodeFloatingPillStyle.announcesUpdates(layout: layout, state: state) ? .updatesFrequently : []
    }

    @ViewBuilder
    private var leadingMark: some View {
        let mark = compact
            ? OpenCodeFloatingPillStyle.compactLeading(layout: layout, state: state)
            : OpenCodeFloatingPillStyle.leading(layout: layout, state: state)
        switch mark {
        case .none:
            EmptyView()
        case let .icon(icon):
            OpenCodeIconImage(icon: icon, size: Self.iconSize)
        case .spinner:
            OpenCodeFloatingPillSpinner(color: contentColor)
        case .pulsingDot:
            OpenCodeFloatingPillPulsingDot()
        case .doneCheck:
            OpenCodeIconImage(icon: .check, size: Self.iconSize)
                .foregroundStyle(OpenCodeColors.success)
        case .stateDot:
            Circle()
                .fill(contentColor)
                .frame(width: Self.dotSize, height: Self.dotSize)
                .frame(width: Self.iconSize, height: Self.iconSize)
        }
    }

    private var contentColor: Color {
        switch OpenCodeFloatingPillStyle.tone(state) {
        case .base: OpenCodeColors.textBase
        case .strong: OpenCodeColors.textStrong
        case .warning: OpenCodeColors.warning
        case .error: OpenCodeColors.error
        }
    }

    private var accessibilityLabel: String {
        OpenCodeFloatingPillStyle.accessibilityLabel(layout: layout, state: state, override: accessibilityLabelOverride)
    }

    static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 6
    private static let ringWidth: CGFloat = 1
    fileprivate static let dotSize: CGFloat = 8
    private static let closeTouchTarget: CGFloat = 44
}

/// The btw pill spinner; a still arc when Reduce Motion is on.
private struct OpenCodeFloatingPillSpinner: View {
    let color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: 14, height: 14)
                .frame(width: 16, height: 16)
        } else {
            ProgressView()
                .controlSize(.small)
                .tint(color)
        }
    }
}

/// Amber dot that pulses while an item waits for the user; still under Reduce Motion.
private struct OpenCodeFloatingPillPulsingDot: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dim = false

    var body: some View {
        Circle()
            .fill(OpenCodeColors.warning)
            .frame(width: OpenCodeFloatingPill.dotSize, height: OpenCodeFloatingPill.dotSize)
            .opacity(reduceMotion ? 1 : (dim ? 0.35 : 1))
            .frame(width: 16, height: 16)
            .task(id: reduceMotion) {
                // Re-runs when Reduce Motion flips while the pill stays on screen.
                var reset = Transaction()
                reset.disablesAnimations = true
                withTransaction(reset) { dim = false }
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                    dim = true
                }
            }
    }
}

#if DEBUG
#Preview("States") {
    VStack(alignment: .leading, spacing: 12) {
        ForEach(OpenCodeFloatingPillState.allCases, id: \.self) { state in
            HStack(spacing: 8) {
                OpenCodeFloatingPill(layout: .iconLabel(icon: .callSplit, label: "+128 −14"), state: state, action: {})
                OpenCodeFloatingPill(layout: .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end"), state: state, action: {})
                OpenCodeFloatingPill(layout: .counter(label: "3 subagents", waiting: true), state: state)
            }
        }
    }
    .padding()
    .openCodeTheme()
}
#endif
