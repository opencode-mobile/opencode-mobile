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

/// The background work panel list: a Running / Done segmented control over the rows (#966).
/// Running holds waiting and running subagents, then running shells; Done holds finished,
/// failed and stopped subagents, newest first. Rows carry live timers. Tapping a subagent
/// with a known session opens it; tapping a shell opens its output.
struct SessionChatBackgroundWorkList: View {
    let subagents: [SessionChatSubagent]
    var shells: [AgentShell] = []
    @Binding var tab: SessionChatBackgroundWorkTab
    var doneUnseen = false
    let onOpen: (SessionChatSubagent) -> Void
    var onOpenShell: (AgentShell) -> Void = { _ in }

    var body: some View {
        let tabs = SessionChatBackgroundWork.tabs(subagents)
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let nowMs = Int64(context.date.timeIntervalSince1970 * 1_000)
            VStack(alignment: .leading, spacing: 8) {
                OpenCodeFilterTabs(
                    tabs: [
                        OpenCodeFilterTab(id: Self.runningTabID, title: Self.runningTitle, count: tabs.running.count + shells.count),
                        OpenCodeFilterTab(id: Self.doneTabID, title: Self.doneTitle, count: tabs.done.count, unseen: doneUnseen),
                    ],
                    selectedTabId: tab == .done ? Self.doneTabID : Self.runningTabID,
                    style: .segmented,
                    onTabSelected: { id in tab = id == Self.doneTabID ? .done : .running }
                )
                .padding(.bottom, 4)
                switch tab {
                case .running:
                    runningRows(tabs.running, nowMs: nowMs)
                case .done:
                    if tabs.done.isEmpty { emptyText(Self.doneEmptyText) }
                    ForEach(tabs.done) { subagentRow($0, nowMs: nowMs) }
                }
            }
            .openCodeBottomSheetContentInset()
        }
    }

    @ViewBuilder
    private func runningRows(_ running: [SessionChatSubagent], nowMs: Int64) -> some View {
        if running.isEmpty, shells.isEmpty { emptyText(Self.runningEmptyText) }
        let sectioned = !running.isEmpty && !shells.isEmpty
        if sectioned { OpenCodeActivitySectionLabel(text: "Subagents") }
        ForEach(running) { subagentRow($0, nowMs: nowMs) }
        if !shells.isEmpty {
            if sectioned {
                OpenCodeActivitySectionLabel(text: SessionChatShellPresentation.sectionTitle)
                    .padding(.top, 8)
            }
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

    private func subagentRow(_ subagent: SessionChatSubagent, nowMs: Int64) -> some View {
        let text = SessionChatBackgroundWork.rowText(subagent, nowMs: nowMs)
        return OpenCodeTaskRow(
            title: text.title,
            state: subagent.status.taskRowState,
            tag: text.tag,
            detail: text.detail,
            detailCaption: text.detailCaption,
            time: text.time,
            action: subagent.childSessionID == nil ? nil : { onOpen(subagent) }
        )
    }

    private func emptyText(_ text: String) -> some View {
        Text(text)
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textWeak)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
    }

    private static let runningTabID = "running"
    private static let doneTabID = "done"
    static let runningTitle = "Running"
    static let doneTitle = "Done"
    static let runningEmptyText = "Nothing running. Finished work is under Done."
    static let doneEmptyText = "Nothing finished yet."
}

/// Opens on Running each time; reports while Done is on screen so the view model can clear
/// its dot.
struct SessionChatBackgroundWorkSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let subagents: [SessionChatSubagent]
    let shells: [AgentShell]
    let doneUnseen: Bool
    let onDoneVisibleChange: (Bool) -> Void
    let onOpen: (SessionChatSubagent) -> Void
    let onOpenShell: (AgentShell) -> Void
    @State private var tab: SessionChatBackgroundWorkTab = .running

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: presentedBinding,
                title: Self.title,
                content: {
                    SessionChatBackgroundWorkList(
                        subagents: subagents,
                        shells: shells,
                        tab: $tab,
                        doneUnseen: doneUnseen,
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
            .onChange(of: isPresented) { _, presented in
                if presented { tab = .running }
                onDoneVisibleChange(presented && tab == .done)
            }
            .onChange(of: tab) { _, selected in
                onDoneVisibleChange(isPresented && selected == .done)
            }
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
    var taskRowState: OpenCodeTaskRowState {
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
        doneUnseen: Bool = false,
        onDoneVisibleChange: @escaping (Bool) -> Void = { _ in },
        onOpen: @escaping (SessionChatSubagent) -> Void,
        onOpenShell: @escaping (AgentShell) -> Void
    ) -> some View {
        modifier(
            SessionChatBackgroundWorkSheetModifier(
                isPresented: isPresented,
                subagents: subagents,
                shells: shells,
                doneUnseen: doneUnseen,
                onDoneVisibleChange: onDoneVisibleChange,
                onOpen: onOpen,
                onOpenShell: onOpenShell
            )
        )
    }
}
