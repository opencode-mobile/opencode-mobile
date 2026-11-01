import SwiftUI

/// Bottom-left live activity slot: one pill, a "+N" button, and the stack "+N" springs up.
struct SessionChatActivitySlotView: View {
    let slot: SessionChatActivitySlot
    @Binding var stackOpen: Bool
    let onTap: (SessionChatActivityItem) -> Void
    let onClose: (SessionChatActivityItem) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: Self.spacing) {
            if stackOpen, slot.showsOverflowButton {
                // Lowest priority on top, so the next most urgent sits nearest the pill.
                ForEach(slot.overflow.reversed()) { item in
                    pill(item, compact: false)
                        .transition(
                            reduceMotion
                                ? .opacity
                                : .move(edge: .bottom).combined(with: .opacity)
                        )
                }
            }
            if let primary = slot.primary {
                HStack(spacing: Self.spacing) {
                    pill(primary, compact: slot.primaryCompact)
                        .id(primary.id)
                        .transition(.opacity)
                    if slot.showsOverflowButton {
                        OpenCodeFloatingPill(
                            layout: .label("+\(slot.overflow.count)"),
                            state: .ready,
                            accessibilityLabel: stackOpen
                                ? "Hide \(slot.overflow.count) more"
                                : "Show \(slot.overflow.count) more",
                            action: { stackOpen.toggle() }
                        )
                        .transition(.opacity)
                    }
                }
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.32, dampingFraction: 0.78), value: stackOpen)
        .animation(.easeInOut(duration: 0.2), value: slot)
        .onChange(of: slot.showsOverflowButton) { _, shows in
            if !shows { stackOpen = false }
        }
    }

    private func pill(_ item: SessionChatActivityItem, compact: Bool) -> some View {
        let tap: (() -> Void)? = Self.isTappable(item) ? {
            stackOpen = false
            onTap(item)
        } : nil
        let close: (() -> Void)? = Self.isClosable(item) ? { onClose(item) } : nil
        return OpenCodeFloatingPill(
            layout: Self.layout(item),
            state: Self.state(item),
            compact: compact,
            accessibilityLabel: Self.accessibilityLabel(item),
            action: tap,
            onClose: close
        )
    }

    static let spacing: CGFloat = 8

    static func layout(_ item: SessionChatActivityItem) -> OpenCodeFloatingPillLayout {
        switch item {
        case .reviewPermission:
            .iconLabel(icon: .arrowUpward, label: "Review permission")
        case .answerQuestion:
            .iconLabel(icon: .arrowUpward, label: "Answer question")
        case let .subagents(pill):
            .counter(label: pill.label, waiting: pill.needsInput)
        case .requestFailed:
            .closable(label: "Request failed")
        case let .sideQuestionsFailed(count):
            .closable(label: "/btw failed" + countSuffix(count) + " · Retry")
        case let .sideQuestionsReady(count):
            .iconLabel(icon: .check, label: "/btw answer ready" + countSuffix(count))
        case let .sideQuestionsRunning(count):
            .counter(label: "/btw" + countSuffix(count))
        case let .syncing(label):
            .counter(label: label)
        case let .queued(count):
            .label("\(count) queued")
        case let .finished(_, label, _):
            .label(label)
        }
    }

    static func state(_ item: SessionChatActivityItem) -> OpenCodeFloatingPillState {
        switch item.priority {
        case .needsInput: .needsAttention
        case .error: .error
        case .answerReady, .queued: .ready
        case .running: .live
        case .finished: .done
        }
    }

    static func isTappable(_ item: SessionChatActivityItem) -> Bool {
        switch item {
        case .reviewPermission, .answerQuestion, .subagents, .requestFailed, .sideQuestionsFailed, .sideQuestionsReady, .queued:
            true
        case .sideQuestionsRunning, .syncing, .finished: false
        }
    }

    static func isClosable(_ item: SessionChatActivityItem) -> Bool {
        switch item {
        case .requestFailed, .sideQuestionsFailed: true
        default: false
        }
    }

    static func accessibilityLabel(_ item: SessionChatActivityItem) -> String {
        switch item {
        case .reviewPermission:
            "Review permission request"
        case .answerQuestion:
            "Answer question"
        case let .subagents(pill):
            "\(pill.accessibilityLabel). \(pill.needsInput ? "Open request" : "Open subagents")"
        case .requestFailed:
            "Request failed. Show in chat"
        case let .sideQuestionsFailed(count):
            count > 1 ? "\(count) side questions failed. Retry" : "Side question failed. Retry"
        case let .sideQuestionsReady(count):
            count > 1 ? "\(count) side question answers ready. Open" : "Side question answer ready. Open"
        case let .sideQuestionsRunning(count):
            count > 1 ? "\(count) side questions running" : "Side question running"
        case let .syncing(label):
            label
        case let .queued(count):
            count == 1 ? "1 queued prompt. Open Up next" : "\(count) queued prompts. Open Up next"
        case let .finished(_, label, _):
            "\(label), Done"
        }
    }

    private static func countSuffix(_ count: Int) -> String {
        count > 1 ? " ×\(count)" : ""
    }
}

/// Top-left context pills: todos progress and the branch pill.
struct SessionChatContextSlotView: View {
    let items: [SessionChatContextItem]
    let todosAccessibilityLabel: String
    let onTodosTap: () -> Void
    let onBranchTap: () -> Void
    var onBranchCopied: () -> Void = {}

    var body: some View {
        HStack(spacing: SessionChatActivitySlotView.spacing) {
            ForEach(items, id: \.slotKey) { item in
                switch item {
                case let .todos(completed, total):
                    OpenCodeFloatingPill(
                        layout: .iconLabel(icon: .list, label: "\(completed)/\(total)"),
                        state: .ready,
                        accessibilityLabel: todosAccessibilityLabel,
                        action: onTodosTap
                    )
                    .transition(.opacity)
                case let .branch(branch):
                    OpenCodeSessionBranchPill(
                        summary: branch.summary,
                        onViewAllChanges: onBranchTap,
                        onBranchNameCopied: onBranchCopied
                    )
                    .transition(.opacity)
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: items.map(\.slotKey))
    }
}

/// Bottom-right navigation: a round ↓, or "↓ N new".
struct SessionChatNavigationSlotView: View {
    let pill: SessionChatNavigationPill
    let action: () -> Void

    var body: some View {
        switch pill {
        case let .scrollToEnd(newCount) where newCount > 0:
            OpenCodeFloatingPill(
                layout: .iconLabel(icon: .arrowDownward, label: "\(newCount) new"),
                state: .ready,
                accessibilityLabel: newCount == 1 ? "Scroll to end, 1 new message" : "Scroll to end, \(newCount) new messages",
                action: action
            )
        case .scrollToEnd:
            OpenCodeFloatingPill(
                layout: .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end"),
                state: .ready,
                accessibilityLabel: "Scroll to end",
                action: action
            )
        }
    }
}

private extension SessionChatContextItem {
    var slotKey: String {
        switch self {
        case .todos: "todos"
        case .branch: "branch"
        }
    }
}

/// Where the bottom pill row sits and what it reserves in the timeline.
enum SessionChatCornerSlotsLayout {
    static let horizontalPadding: CGFloat = 16
    /// Gap between the composer (or a shown snackbar) and the pill row.
    static let gapAboveComposer: CGFloat = 8

    /// Bottom padding of the pill row. `overlayInset` is the composer clearance
    /// plus the bottom fade overhang that the timeline's end pin keeps clear.
    static func rowBottom(overlayInset: CGFloat) -> CGFloat {
        max(overlayInset - OpenCodeBottomFade.overhang, 0) + gapAboveComposer
    }

    /// Extra timeline clearance while an activity pill shows, so it never covers the last message.
    /// The navigation pill only shows away from the end, so it reserves nothing.
    static func reservedHeight(hasActivity: Bool) -> CGFloat {
        hasActivity ? OpenCodeFloatingPill.minHeight + gapAboveComposer : 0
    }
}

/// Activity slot for timelines that are not ready yet (loading, empty): no scroll, no failure.
struct SessionChatTimelineActivityOnlySlot: View {
    let props: SessionChatTimelineViewProps
    @State private var stackOpen = false

    var body: some View {
        SessionChatActivitySlotView(
            slot: SessionChatPillSlots.activity(
                items: props.activityItems,
                keyboardVisible: props.keyboardVisible,
                now: Date()
            ),
            stackOpen: $stackOpen,
            onTap: props.onActivityTap,
            onClose: props.onActivityClose
        )
        .padding(.horizontal, SessionChatCornerSlotsLayout.horizontalPadding)
        .padding(.bottom, SessionChatCornerSlotsLayout.rowBottom(overlayInset: props.bottomOverlayInset))
    }
}
