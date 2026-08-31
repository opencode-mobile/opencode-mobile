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
            if viewModel.rows.isEmpty {
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
                                            Task { await viewModel.close(id: row.id, directory: row.worktree) }
                                        }
                                    )
                                }
                            )
                            .padding(.horizontal, 16)
                        }
                    }
                }
            }
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
    }
}
