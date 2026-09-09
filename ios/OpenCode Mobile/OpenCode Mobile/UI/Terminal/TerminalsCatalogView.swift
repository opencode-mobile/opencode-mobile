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
            Group {
            if viewModel.isLoading {
                OpenCodeStatusStack(
                    title: "Loading terminals",
                    subtitle: "Checking server…"
                )
            } else if viewModel.rows.isEmpty {
                OpenCodeEmptyState(
                    title: "No running shells",
                    message: "Open a session and use / terminal to start one for that project."
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
                }
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
    }
}
