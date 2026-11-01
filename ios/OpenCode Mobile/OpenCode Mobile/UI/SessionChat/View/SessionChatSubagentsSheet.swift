import SwiftUI

/// Background work pill for Session Chat: "3 subagents" or "1 subagent · 2 shells" with a
/// spinner, or the amber "general needs input · 2 running" with a pulsing dot. Opens the
/// background work panel.
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

/// The background work panel: a Subagents section (each subagent with its status and a
/// live timer; tapping one with a known session opens it), then a Shells section (each
/// running shell; tapping one opens its output). A section shows only when it has rows.
struct SessionChatBackgroundWorkList: View {
    let subagents: [SessionChatSubagent]
    var shells: [AgentShell] = []
    let onOpen: (SessionChatSubagent) -> Void
    var onOpenShell: (AgentShell) -> Void = { _ in }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let nowMs = Int64(context.date.timeIntervalSince1970 * 1_000)
            VStack(alignment: .leading, spacing: 8) {
                if !subagents.isEmpty {
                    OpenCodeActivitySectionLabel(text: "Subagents")
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
                if !shells.isEmpty {
                    OpenCodeActivitySectionLabel(text: SessionChatShellPresentation.sectionTitle)
                        .padding(.top, subagents.isEmpty ? 0 : 8)
                    ForEach(shells) { shell in
                        OpenCodeShellRow(
                            command: shell.command,
                            description: shell.description,
                            elapsed: SessionChatShellPresentation.elapsed(shell, nowMs: nowMs),
                            action: { onOpenShell(shell) }
                        )
                    }
                }
            }
            .openCodeBottomSheetContentInset()
        }
    }
}

struct SessionChatBackgroundWorkSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let subagents: [SessionChatSubagent]
    let shells: [AgentShell]
    let onOpen: (SessionChatSubagent) -> Void
    let onOpenShell: (AgentShell) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: presentedBinding,
                title: Self.title,
                content: {
                    SessionChatBackgroundWorkList(
                        subagents: subagents,
                        shells: shells,
                        onOpen: { subagent in
                            isPresented = false
                            onOpen(subagent)
                        },
                        onOpenShell: { shell in
                            isPresented = false
                            onOpenShell(shell)
                        }
                    )
                }
            )
    }

    static let title = "Background work"

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
        case .failed: .failed
        case .stopped: .stopped
        }
    }
}

extension View {
    func sessionChatBackgroundWorkSheet(
        isPresented: Binding<Bool>,
        subagents: [SessionChatSubagent],
        shells: [AgentShell],
        onOpen: @escaping (SessionChatSubagent) -> Void,
        onOpenShell: @escaping (AgentShell) -> Void
    ) -> some View {
        modifier(
            SessionChatBackgroundWorkSheetModifier(
                isPresented: isPresented,
                subagents: subagents,
                shells: shells,
                onOpen: onOpen,
                onOpenShell: onOpenShell
            )
        )
    }
}
