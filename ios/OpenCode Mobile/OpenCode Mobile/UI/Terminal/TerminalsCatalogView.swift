import FactoryKit
import SwiftUI

struct TerminalsCatalogView: View {
    @Environment(\.dismiss) private var dismiss
    var splitGate = OpenCodeSplitLayoutGate()
    @State private var viewModel: TerminalsCatalogViewModel
    @State private var selected: TerminalRoute?
    var onOpen: (String, String, String) -> Void

    init(
        viewModel: TerminalsCatalogViewModel? = nil,
        onOpen: @escaping (String, String, String) -> Void
    ) {
        _viewModel = State(initialValue: viewModel ?? Container.makeTerminalsCatalogViewModel())
        self.onOpen = onOpen
    }

    /// Same breakpoint as Home: a wide window keeps the list beside the shell.
    private var isSplitLayout: Bool {
        splitGate.usesSplitLayout
    }

    var body: some View {
        Group {
            if isSplitLayout {
                contentSplit
            } else {
                catalogColumn(selectedID: nil, constrainWidth: true) { worktree, projectTitle, ptyId in
                    onOpen(worktree, projectTitle, ptyId)
                }
            }
        }
        .onChange(of: isSplitLayout) { _, split in
            guard !split, let selected else { return }
            self.selected = nil
            onOpen(selected.worktree, selected.projectTitle, selected.ptyId ?? "")
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.terminalsCatalog,
                presentation: ProductAnalytics.Presentation.route
            )
            await viewModel.appear()
        }
        .onAppear {
            Task { await viewModel.appear() }
        }
        .openCodeDialog(
            isPresented: Binding(
                get: { viewModel.pendingClose != nil },
                set: { if !$0 { viewModel.dismissClose() } }
            ),
            title: viewModel.pendingClose.map { "Close “\($0.title)”?" } ?? "Close this shell?",
            subtitle: "This ends the shell on the server.",
            primaryOption: OpenCodeDialogOption(title: "Close", destructive: true) {
                Task { await viewModel.confirmClose() }
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                viewModel.dismissClose()
            }
        )
        .openCodeBottomSheet(
            isPresented: Binding(
                get: { viewModel.isProjectPickerVisible },
                set: { if !$0 { viewModel.dismissProjectPicker() } }
            ),
            title: "New shell"
        ) {
            projectPicker
        }
    }

    private var contentSplit: some View {
        OpenCodeSplitPane(screen: .terminals, onCollapsedChange: { _ in
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.terminalsCatalog,
                actionId: ProductAnalytics.Action.splitPaneToggleSidebar
            )
        }) {
            catalogColumn(selectedID: selected?.ptyId, constrainWidth: false) { worktree, projectTitle, ptyId in
                selected = TerminalRoute(worktree: worktree, projectTitle: projectTitle, ptyId: ptyId)
            }
        } trailing: { control in
            if let selected {
                TerminalView(
                    worktree: selected.worktree,
                    projectTitle: selected.projectTitle,
                    ptyId: selected.ptyId,
                    detailControl: control,
                    usesReadableColumn: false
                )
                .id(selected.ptyId)
            } else {
                VStack(spacing: 0) {
                    OpenCodeTopBar(
                        title: "Terminal",
                        leading: OpenCodeSplitPane.detailLeading(control, back: {})
                    )
                    OpenCodeEmptyState(
                        title: "Select a shell",
                        message: "Choose a shell from the list.",
                        icon: .terminal
                    )
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(OpenCodeColors.backgroundBase)
            }
        }
    }

    private func catalogColumn(
        selectedID: String?,
        constrainWidth: Bool,
        onOpenShell: @escaping (String, String, String) -> Void
    ) -> some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Terminals",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            VStack(spacing: 0) {
                if viewModel.isLoading {
                    OpenCodeStatusStack(
                        title: "Loading terminals",
                        subtitle: "Checking server…"
                    )
                } else {
                    if viewModel.rows.isEmpty {
                        OpenCodeEmptyState(
                            title: "No running shells",
                            message: "Start one for a project. It is not tied to a chat session."
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        ScrollView {
                            VStack(spacing: 0) {
                                ForEach(viewModel.rows, id: \.worktreeAndId) { row in
                                    OpenCodeListItem(
                                        title: row.title,
                                        subtitle: row.projectTitle,
                                        selected: row.id == selectedID,
                                        startDecorator: .letter(
                                            letterCharacter(for: row.projectTitle),
                                            letterAccent(for: row.worktree)
                                        ),
                                        action: {
                                            onOpenShell(row.worktree, row.projectTitle, row.id)
                                        },
                                        trailing: {
                                            OpenCodeButton(
                                                variant: .iconCircle(.close, accessibilityLabel: "Close"),
                                                contrast: .low,
                                                action: {
                                                    viewModel.requestClose(row)
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            .padding(.bottom, 88)
                        }
                    }
                    OpenCodeButton(
                        variant: .fullWidthText(title: "New shell"),
                        action: { viewModel.showProjectPicker() }
                    )
                    .padding(.horizontal, 20)
                    .padding(.vertical, 24)
                }
            }
            .modifier(TerminalsReadableColumnModifier(enabled: constrainWidth))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .onChange(of: viewModel.rows.map(\.id)) { _, ids in
            guard let selected, !ids.contains(selected.ptyId ?? "") else { return }
            self.selected = nil
        }
    }

    private var projectPicker: some View {
        VStack(spacing: 0) {
            if let createError = viewModel.createError {
                Text(createError)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .openCodeBottomSheetContentInset()
                    .padding(.bottom, 8)
            }
            if viewModel.projects.isEmpty {
                OpenCodeEmptyState(
                    title: "No projects yet",
                    message: "Add a project on home, then start a shell here.",
                    fillsAvailableSpace: false
                )
            } else {
                ForEach(viewModel.projects, id: \.worktree) { project in
                    OpenCodeListItem(
                        title: project.title,
                        startDecorator: .letter(
                            letterCharacter(for: project.title),
                            letterAccent(for: project.worktree)
                        ),
                        action: {
                            Task {
                                if let row = await viewModel.createShell(worktree: project.worktree) {
                                    openCreated(row)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private func openCreated(_ row: TerminalCatalogRow) {
        if isSplitLayout {
            selected = TerminalRoute(worktree: row.worktree, projectTitle: row.projectTitle, ptyId: row.id)
        } else {
            onOpen(row.worktree, row.projectTitle, row.id)
        }
    }
}

private struct TerminalsReadableColumnModifier: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.openCodeReadableColumn()
        } else {
            content
        }
    }
}
