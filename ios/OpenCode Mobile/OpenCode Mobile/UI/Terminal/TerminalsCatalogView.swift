import Factory
import SwiftUI

struct TerminalsCatalogRoute: Hashable {}

struct TerminalsCatalogView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: TerminalsCatalogViewModel
    var onOpen: (String, String, String) -> Void

    init(
        viewModel: TerminalsCatalogViewModel? = nil,
        onOpen: @escaping (String, String, String) -> Void
    ) {
        _viewModel = State(initialValue: viewModel ?? Container.makeTerminalsCatalogViewModel())
        self.onOpen = onOpen
    }

    var body: some View {
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
                                        startDecorator: .letter(
                                            letterCharacter(for: row.projectTitle),
                                            letterAccent(for: row.worktree)
                                        ),
                                        action: {
                                            onOpen(row.worktree, row.projectTitle, row.id)
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
            .openCodeReadableColumn()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
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
            VStack(spacing: 0) {
                if let createError = viewModel.createError {
                    Text(createError)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
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
                                        onOpen(row.worktree, row.projectTitle, row.id)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
