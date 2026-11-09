import FactoryKit
import SwiftUI

/// Archived sessions of a Home scope, pushed from Home's ⋯ menu. `onOpenSession` opens a session
/// in Session Chat.
struct ArchivedSessionsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.homeProjects) private var homeProjects
    @State private var viewModel: ArchivedSessionsViewModel
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    let scopeID: String
    var onOpenSession: (SessionListItem) -> Void

    init(
        scopeID: String,
        viewModel: ArchivedSessionsViewModel? = nil,
        onOpenSession: @escaping (SessionListItem) -> Void
    ) {
        self.scopeID = scopeID
        _viewModel = State(initialValue: viewModel ?? Container.makeArchivedSessionsViewModel())
        self.onOpenSession = onOpenSession
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "archived_sessions.title",
                subtitle: .verbatim(viewModel.scopeTitle),
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            content
                .openCodeReadableColumn()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .bottom) {
            OpenCodeSnackbarHost(
                message: viewModel.snackbar?.message,
                onDismissed: { viewModel.consumeSnackbar() },
                onAction: { message in
                    guard let snackbar = viewModel.snackbar, snackbar.message.id == message.id,
                          let sessionID = snackbar.undoSessionID
                    else { return }
                    Task { await viewModel.undoUnarchive(sessionID: sessionID) }
                }
            )
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.archivedSessions,
                presentation: ProductAnalytics.Presentation.route
            )
            await viewModel.appear(
                scopeID: scopeID,
                projects: homeProjects,
                worktreesEnabled: homeDisplayPreferences.showsWorktrees
            )
        }
        .openCodeBottomSheet(
            isPresented: Binding(
                get: { viewModel.sheetVisible },
                set: { if !$0 { viewModel.dismissActions() } }
            ),
            title: .verbatim(viewModel.actionTarget?.title ?? "")
        ) {
            actionsSheet
        }
        .openCodeDialog(
            isPresented: Binding(
                get: { viewModel.deleteConfirmVisible },
                set: { if !$0 { viewModel.dismissDeleteConfirm() } }
            ),
            title: "home.delete.title",
            subtitle: viewModel.deleteTarget.map { target in
                OpenCodeText(LocalizedStringResource(
                    "archived_sessions.delete_dialog.message",
                    defaultValue: "\u{201C}\(target.title)\u{201D} is deleted from the server. This can\u{2019}t be undone."
                ))
            },
            primaryOption: OpenCodeDialogOption(title: "common.delete", destructive: true) {
                Task { await viewModel.confirmDelete() }
            },
            secondaryOption: OpenCodeDialogOption(title: "common.cancel") {
                viewModel.dismissDeleteConfirm()
            }
        )
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.content {
        case .loading:
            OpenCodeStatusStack(title: "common.loading_sessions", subtitle: "common.checking_server")
        case let .failed(message):
            OpenCodeEmptyState(
                title: "archived_sessions.title",
                message: .verbatim(message),
                icon: .archive,
                primaryAction: OpenCodeEmptyStateAction(title: "common.retry") {
                    Task { await viewModel.retry() }
                }
            )
        case let .loaded(sections, canShowAllProjects):
            if sections.isEmpty {
                OpenCodeEmptyState(
                    title: "archived_sessions.empty.title",
                    message: OpenCodeText(LocalizedStringResource(
                        "archived_sessions.empty.message",
                        defaultValue: "Sessions you archive in \(viewModel.scopeTitle) show up here."
                    )),
                    icon: .archive,
                    primaryAction: canShowAllProjects
                        ? OpenCodeEmptyStateAction(title: "archived_sessions.show_all_projects") {
                            withAnimation { viewModel.showAllProjects() }
                        }
                        : nil
                )
            } else {
                list(sections)
            }
        }
    }

    private func list(_ sections: [ArchivedSessionSection]) -> some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(sections) { section in
                    OpenCodeSectionHeader(title: section.period.title)
                    ForEach(section.rows) { row in
                        SessionListRow(
                            session: row.row,
                            rowStyle: homeDisplayPreferences.sessionRowStyle,
                            showsStatus: false,
                            action: { viewModel.openActions(row) },
                            onLongPress: { viewModel.openActions(row) }
                        )
                        .accessibilityIdentifier("archivedSessionRow:\(row.id)")
                        .transition(.opacity.combined(with: .move(edge: .leading)))
                    }
                }
                Text("archived_sessions.footer")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
            }
            .animation(.easeInOut(duration: 0.25), value: sections)
            .padding(.bottom, 88)
        }
    }

    private var actionsSheet: some View {
        VStack(spacing: 0) {
            if let target = viewModel.actionTarget {
                Text(LocalizedStringResource(
                    "archived_sessions.archived_at",
                    defaultValue: "Archived \(RelativeTimeFormatting.format(epochMs: target.archivedAtEpochMs))"
                ))
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(maxWidth: .infinity, alignment: .leading)
                .openCodeBottomSheetContentInset()
                .padding(.bottom, 8)
            }
            OpenCodeBottomSheetEntry(title: "archived_sessions.unarchive", startDecorator: .icon(.unarchive)) {
                Task { await viewModel.chooseUnarchive() }
            }
            OpenCodeBottomSheetEntry(title: "common.open", startDecorator: .icon(.chevronRight)) {
                if let row = viewModel.chooseOpen() {
                    onOpenSession(SessionListItem(id: row.id, title: row.title, worktree: row.directory))
                }
            }
            OpenCodeBottomSheetEntry(title: "archived_sessions.delete", destructive: true, startDecorator: .icon(.close)) {
                viewModel.chooseDelete()
            }
        }
    }
}

private extension ArchivedSessionPeriod {
    var title: OpenCodeText {
        switch self {
        case .thisWeek: "archived_sessions.section.this_week"
        case .earlier: "archived_sessions.section.earlier"
        }
    }
}
