import SwiftUI

/// Background work pill for Session Chat: "3 subagents" with a spinner, or the amber
/// "general needs input · 2 running" with a pulsing dot. Opens the Subagents panel.
struct SessionChatBackgroundWorkPillView: View {
    let pill: SessionChatBackgroundWorkPill
    var compact = false
    let action: () -> Void

    var body: some View {
        OpenCodeFloatingPill(
            layout: .counter(label: pill.label, waiting: pill.needsInput),
            state: pill.needsInput ? .needsAttention : .live,
            compact: compact,
            accessibilityLabel: pill.accessibilityLabel,
            action: action
        )
    }
}

/// The Subagents panel: each subagent with its status and a live timer. Tapping a
/// row with a known session opens it.
struct SessionChatSubagentsList: View {
    let subagents: [SessionChatSubagent]
    let onOpen: (SessionChatSubagent) -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let nowMs = Int64(context.date.timeIntervalSince1970 * 1_000)
            VStack(alignment: .leading, spacing: 8) {
                ForEach(subagents) { subagent in
                    OpenCodeChildSessionRow(
                        title: subagent.agentName,
                        subtitle: subagent.title,
                        elapsed: subagent.elapsed(nowMs: nowMs),
                        status: subagent.status.rowStatus,
                        action: subagent.childSessionID == nil ? nil : { onOpen(subagent) }
                    )
                }
            }
            .openCodeBottomSheetContentInset()
        }
    }
}

struct SessionChatSubagentsSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let subagents: [SessionChatSubagent]
    let onOpen: (SessionChatSubagent) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: presentedBinding,
                title: "Subagents",
                content: {
                    SessionChatSubagentsList(subagents: subagents) { subagent in
                        isPresented = false
                        onOpen(subagent)
                    }
                }
            )
    }

    private var presentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented, isPresented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionSubagents,
                        actionId: ProductAnalytics.Action.subagentsDismiss
                    )
                }
                isPresented = presented
            }
        )
    }
}

extension SessionChatSubagentStatus {
    var rowStatus: OpenCodeChildSessionRowStatus {
        switch self {
        case .running: .running
        case .waitingForInput: .waitingForInput
        case .done: .done
        }
    }
}

extension View {
    func sessionChatSubagentsSheet(
        isPresented: Binding<Bool>,
        subagents: [SessionChatSubagent],
        onOpen: @escaping (SessionChatSubagent) -> Void
    ) -> some View {
        modifier(SessionChatSubagentsSheetModifier(isPresented: isPresented, subagents: subagents, onOpen: onOpen))
    }
}
