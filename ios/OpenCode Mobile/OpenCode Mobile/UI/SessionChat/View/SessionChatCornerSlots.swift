import SwiftUI

/// Bottom-left live activity slot: one pill, a "+N" button, and the stack "+N" springs up.
/// Away from the end the whole slot is one round pill that opens the stack of every item.
struct SessionChatActivitySlotView: View {
    let slot: SessionChatActivitySlot
    @Binding var stackOpen: Bool
    let onTap: (SessionChatActivityItem) -> Void
    let onClose: (SessionChatActivityItem) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Items the stack lists above the row: the rest behind "+N", or every item behind the round pill.
    private var stackItems: [SessionChatActivityItem] {
        if slot.isCollapsed { return slot.allItems }
        return slot.showsOverflowButton ? slot.overflow : []
    }

    private var rowTransition: AnyTransition {
        reduceMotion ? .opacity : .openCodePillRow(gapEdge: .trailing)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Self.spacing) {
            if stackOpen {
                // Lowest priority on top, so the most urgent sits nearest the row.
                ForEach(stackItems.reversed()) { item in
                    pill(item, compact: false, backing: .solid)
                        .transition(
                            reduceMotion
                                ? .opacity
                                : .move(edge: .bottom).combined(with: .opacity)
                        )
                }
            }
            HStack(spacing: 0) {
                if let primary = slot.primary {
                    // One pill whether collapsed or not, so folding to round shrinks it in place.
                    primaryPill(primary)
                        .id(primary.id)
                        .padding(.trailing, Self.spacing)
                        .transition(rowTransition)
                    if slot.showsOverflowButton {
                        OpenCodeFloatingPill(
                            layout: .label("+\(slot.overflow.count)"),
                            state: .ready,
                            accessibilityLabel: Self.overflowAccessibilityLabel(count: slot.overflow.count, stackOpen: stackOpen),
                            action: { stackOpen.toggle() }
                        )
                        .padding(.trailing, Self.spacing)
                        .transition(rowTransition)
                    }
                }
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.32, dampingFraction: 0.78), value: stackOpen)
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : OpenCodeFloatingPill.widthAnimation, value: slot)
        .onChange(of: slot.showsOverflowButton) { _, shows in
            if !shows, !slot.isCollapsed { stackOpen = false }
        }
        .onChange(of: slot.isCollapsed) { _, _ in
            // The reader crossed back or away: the stack belongs to the other mode.
            stackOpen = false
        }
        .onChange(of: slot.allItems.isEmpty) { _, isEmpty in
            if isEmpty { stackOpen = false }
        }
    }

    /// The row pill. Collapsed, it folds to the round summary of every item and opens their stack.
    /// No branching on `isCollapsed`, so SwiftUI keeps one view and animates its width.
    private func primaryPill(_ primary: SessionChatActivityItem) -> some View {
        let collapsed = slot.isCollapsed
        let count = slot.allItems.count
        let pending: Bool = if case .queued = primary { true } else { false }
        return pill(
            primary,
            compact: collapsed || slot.primaryCompact,
            summary: collapsed ? OpenCodeFloatingPillSummary(count: count, pending: pending) : nil,
            accessibilityLabel: collapsed
                ? Self.collapsedAccessibilityLabel(primary, count: count, stackOpen: stackOpen)
                : nil,
            action: collapsed ? { stackOpen.toggle() } : nil
        )
    }

    private func pill(
        _ item: SessionChatActivityItem,
        compact: Bool,
        summary: OpenCodeFloatingPillSummary? = nil,
        accessibilityLabel: String? = nil,
        action: (() -> Void)? = nil,
        backing: OpenCodeFloatingPillBacking = .glass
    ) -> some View {
        let tap: (() -> Void)? = Self.isTappable(item) ? {
            stackOpen = false
            onTap(item)
        } : nil
        let close: (() -> Void)? = Self.isClosable(item) ? { onClose(item) } : nil
        return OpenCodeFloatingPill(
            layout: Self.layout(item),
            state: Self.state(item),
            compact: compact,
            summary: summary,
            accessibilityLabel: accessibilityLabel ?? Self.accessibilityLabel(item),
            action: action ?? tap,
            onClose: close,
            backing: backing
        )
    }

    static let spacing: CGFloat = 8

    static func layout(_ item: SessionChatActivityItem) -> OpenCodeFloatingPillLayout {
        switch item {
        case .reviewPermission:
            .iconLabel(icon: .arrowUpward, label: String(localized: "session_chat.activity.review_permission", defaultValue: "Review permission"))
        case .answerQuestion:
            .iconLabel(icon: .arrowUpward, label: String(localized: "session_chat.activity.answer_question", defaultValue: "Answer question"))
        case let .subagents(pill):
            .counter(label: pill.label, waiting: pill.needsInput)
        case .requestFailed:
            .closable(label: String(localized: "session_chat.activity.request_failed", defaultValue: "Request failed"))
        case let .sideQuestionsFailed(count):
            .closable(label: btwFailedLabel(count))
        case let .sideQuestionsReady(count):
            .iconLabel(icon: .check, label: btwReadyLabel(count))
        case let .sideQuestionsRunning(count):
            // The `/btw` command and the count; no words to translate.
            .counter(label: count > 1 ? "/btw \u{00D7}\(count)" : "/btw")
        case let .queued(count):
            .label(String(localized: "session_chat.activity.queued", defaultValue: "\(count) queued"))
        case let .finished(_, label, _):
            .label(label)
        }
    }

    private static func btwFailedLabel(_ count: Int) -> String {
        guard count > 1 else {
            return String(localized: "session_chat.activity.btw_failed", defaultValue: "/btw failed · Retry")
        }
        return String(localized: "session_chat.activity.btw_failed_count", defaultValue: "/btw failed \u{00D7}\(count) · Retry")
    }

    private static func btwReadyLabel(_ count: Int) -> String {
        guard count > 1 else {
            return String(localized: "session_chat.activity.btw_ready", defaultValue: "/btw answer ready")
        }
        return String(localized: "session_chat.activity.btw_ready_count", defaultValue: "/btw answer ready \u{00D7}\(count)")
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
        case .sideQuestionsRunning, .finished: false
        }
    }

    static func isClosable(_ item: SessionChatActivityItem) -> Bool {
        switch item {
        case .requestFailed, .sideQuestionsFailed: true
        default: false
        }
    }

    /// What VoiceOver reads: the item's state, then what a tap does.
    static func accessibilityLabel(_ item: SessionChatActivityItem) -> String {
        let statusText = Self.status(item)
        guard let tapAction = Self.action(item) else { return statusText }
        let actionText = String(localized: tapAction)
        return String(
            localized: "session_chat.activity.status_action",
            defaultValue: "\(statusText). \(actionText)"
        )
    }

    /// The round pill that stands for every item: the primary's state, then that a tap shows or hides them.
    static func collapsedAccessibilityLabel(_ primary: SessionChatActivityItem, count: Int, stackOpen: Bool) -> String {
        let statusText = Self.status(primary)
        let toggleText = stackOpen
            ? String(localized: "session_chat.activity.collapsed.hide_count", defaultValue: "Hide \(count) activities")
            : String(localized: "session_chat.activity.collapsed.show_count", defaultValue: "Show \(count) activities")
        return String(
            localized: "session_chat.activity.status_action",
            defaultValue: "\(statusText). \(toggleText)"
        )
    }

    static func overflowAccessibilityLabel(count: Int, stackOpen: Bool) -> String {
        stackOpen
            ? String(localized: "session_chat.activity.hide_more", defaultValue: "Hide \(count) more")
            : String(localized: "session_chat.activity.show_more", defaultValue: "Show \(count) more")
    }

    /// The item's state, as read aloud.
    private static func status(_ item: SessionChatActivityItem) -> String {
        switch item {
        case .reviewPermission:
            String(localized: "session_chat.activity.review_permission_request", defaultValue: "Review permission request")
        case .answerQuestion:
            String(localized: "session_chat.activity.answer_question", defaultValue: "Answer question")
        case let .subagents(pill):
            pill.accessibilityLabel
        case .requestFailed:
            String(localized: "session_chat.activity.request_failed", defaultValue: "Request failed")
        case let .sideQuestionsFailed(count):
            count > 1
                ? String(localized: "session_chat.activity.side_questions_failed", defaultValue: "\(count) side questions failed")
                : String(localized: "session_chat.activity.side_question_failed", defaultValue: "Side question failed")
        case let .sideQuestionsReady(count):
            count > 1
                ? String(localized: "session_chat.activity.side_questions_ready", defaultValue: "\(count) side question answers ready")
                : String(localized: "session_chat.activity.side_question_ready", defaultValue: "Side question answer ready")
        case let .sideQuestionsRunning(count):
            count > 1
                ? String(localized: "session_chat.activity.side_questions_running", defaultValue: "\(count) side questions running")
                : String(localized: "session_chat.activity.side_question_running", defaultValue: "Side question running")
        case let .queued(count):
            String(localized: "session_chat.activity.queued_prompts", defaultValue: "\(count) queued prompts")
        case let .finished(_, label, _):
            String(localized: "session_chat.activity.finished", defaultValue: "\(label), Done")
        }
    }

    /// What a tap does, read after the state; nil when the pill does nothing.
    private static func action(_ item: SessionChatActivityItem) -> LocalizedStringResource? {
        switch item {
        case .reviewPermission, .answerQuestion, .sideQuestionsRunning, .finished:
            nil
        case let .subagents(pill):
            pill.needsInput
                ? LocalizedStringResource("session_chat.activity.action.open_request", defaultValue: "Open request")
                : LocalizedStringResource("session_chat.activity.action.open_background_work", defaultValue: "Open background work")
        case .requestFailed:
            LocalizedStringResource("session_chat.activity.action.show_in_chat", defaultValue: "Show in chat")
        case .sideQuestionsFailed:
            LocalizedStringResource("session_chat.activity.action.retry", defaultValue: "Retry")
        case .sideQuestionsReady:
            LocalizedStringResource("session_chat.activity.action.open", defaultValue: "Open")
        case .queued:
            LocalizedStringResource("session_chat.activity.action.open_up_next", defaultValue: "Open Up next")
        }
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
                        layout: .iconLabel(
                            icon: .list,
                            label: String(localized: "session_chat.todos.progress", defaultValue: "\(completed)/\(total)")
                        ),
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

/// What the bottom-right slot shows: Syncing, then scroll to end.
struct SessionChatTrailingSlotState: Equatable {
    var syncingLabel: String?
    /// Syncing shrinks to its round mark (reader away from the end, or keyboard up).
    var syncingCompact: Bool
    var navigation: SessionChatNavigationPill?
}

/// Bottom-right slot: Syncing beside "↓ Scroll to end" ("↓ N new" once messages arrive below).
/// Neither reserves room in the timeline; both are overlays that grow in by width.
struct SessionChatTrailingSlotView: View {
    let state: SessionChatTrailingSlotState
    let onNavigate: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var rowTransition: AnyTransition {
        reduceMotion ? .opacity : .openCodePillRow(gapEdge: .leading)
    }

    var body: some View {
        HStack(spacing: 0) {
            if let label = state.syncingLabel {
                OpenCodeFloatingPill(
                    layout: .counter(label: label),
                    state: .live,
                    compact: state.syncingCompact,
                    accessibilityLabel: label
                )
                .padding(.leading, SessionChatActivitySlotView.spacing)
                .transition(rowTransition)
            }
            if let pill = state.navigation {
                OpenCodeFloatingPill(
                    layout: .iconLabel(icon: .arrowDownward, label: pill.label),
                    state: .ready,
                    accessibilityLabel: pill.accessibilityLabel,
                    action: onNavigate
                )
                .padding(.leading, SessionChatActivitySlotView.spacing)
                .transition(rowTransition)
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : OpenCodeFloatingPill.widthAnimation, value: state)
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
    /// The pill row only: the end pin's resting gap (the bottom fade overhang less the gap above
    /// the composer) already supplies the space between the pill and the last message.
    /// Syncing and the navigation pill are overlays and reserve nothing.
    static func reservedHeight(hasActivity: Bool) -> CGFloat {
        hasActivity ? OpenCodeFloatingPill.minHeight : 0
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
