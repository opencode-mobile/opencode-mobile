import FactoryKit
import SwiftUI

/// Every worktree of Home's projects, grouped by project, and removing one.
struct WorktreesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: WorktreesViewModel
    private let feed: HomeWorktreesFeed
    let homeShowsWorktrees: Bool
    let onOpenSession: (SessionListItem) -> Void

    init(
        feed: HomeWorktreesFeed,
        viewModel: WorktreesViewModel? = nil,
        homeShowsWorktrees: Bool,
        onOpenSession: @escaping (SessionListItem) -> Void
    ) {
        self.feed = feed
        _viewModel = State(initialValue: viewModel ?? Container.makeWorktreesViewModel(feed: feed))
        self.homeShowsWorktrees = homeShowsWorktrees
        self.onOpenSession = onOpenSession
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "worktrees.title",
                subtitle: .verbatim(WorktreesCopy.subtitle(
                    worktrees: viewModel.worktreeCount,
                    projects: viewModel.projectCount
                )),
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(viewModel.groups) { group in
                        groupSection(group)
                    }
                    note("worktrees.footer")
                        .padding(.top, 8)
                }
                .padding(.bottom, 88)
                .animation(.easeInOut(duration: 0.25), value: viewModel.groups)
            }
            .openCodeReadableColumn()
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .bottom) {
            OpenCodeSnackbarHost(
                message: viewModel.snackbar.map(Self.message),
                onDismissed: { viewModel.snackbarShown() },
                onAction: { _ in
                    if case let .failed(_, directory, _, _)? = viewModel.snackbar {
                        viewModel.retry(directory: directory)
                    }
                }
            )
        }
        .onAppear {
            viewModel.homeShowsWorktrees = homeShowsWorktrees
            viewModel.sync()
        }
        .onChange(of: feed.snapshot) { _, _ in viewModel.sync() }
        .onChange(of: viewModel.closeToken) { _, _ in dismiss() }
        .onChange(of: viewModel.sessionToOpen) { _, session in
            guard let session else { return }
            viewModel.consumeSessionToOpen()
            onOpenSession(session)
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.worktrees,
                presentation: ProductAnalytics.Presentation.route
            )
        }
        .openCodeBottomSheet(
            isPresented: Binding(
                get: { viewModel.actions != nil },
                set: { if !$0 { viewModel.dismissActions() } }
            ),
            title: .verbatim(viewModel.actions?.name ?? ""),
            pinnedTop: {
                if let actions = viewModel.actions {
                    Text(WorktreesCopy.actionsSubtitle(project: actions.projectName, sessions: actions.sessionCount))
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .openCodeBottomSheetContentInset()
                }
            },
            content: { actionsSheet }
        )
        .openCodeDialog(
            isPresented: Binding(
                get: { if case .confirm? = viewModel.dialog { true } else { false } },
                set: { if !$0 { viewModel.dismissDialog() } }
            ),
            title: .verbatim(WorktreesCopy.removeTitle(viewModel.dialog)),
            subtitle: .verbatim(WorktreesCopy.removeMessage(viewModel.dialog)),
            primaryOption: OpenCodeDialogOption(
                title: viewModel.dialog?.warnsAboutChanges == true ? "worktrees.remove.confirm_anyway" : "common.remove",
                destructive: true
            ) {
                Task { await viewModel.confirmRemove() }
            },
            secondaryOption: OpenCodeDialogOption(title: "common.cancel") { viewModel.dismissDialog() },
            notes: WorktreesCopy.removeNotes(viewModel.dialog)
        )
        .openCodeDialog(
            isPresented: Binding(
                get: { if case .blocked? = viewModel.dialog { true } else { false } },
                set: { if !$0 { viewModel.dismissDialog() } }
            ),
            title: .verbatim(WorktreesCopy.blockedTitle(viewModel.dialog)),
            subtitle: "worktrees.blocked.message",
            primaryOption: OpenCodeDialogOption(title: "worktrees.blocked.show_session") {
                viewModel.showRunningSession()
            },
            secondaryOption: OpenCodeDialogOption(title: "worktrees.blocked.ok") { viewModel.dismissDialog() }
        )
    }

    @ViewBuilder
    private func groupSection(_ group: WorktreesGroupItem) -> some View {
        OpenCodeSectionHeader(
            title: .verbatim(group.displayName),
            variant: .plain,
            trailing: group.worktrees.isEmpty ? nil : .count(group.worktrees.count)
        )
        OpenCodeListItem(
            title: "worktrees.main_checkout",
            subtitle: .verbatim(group.mainCheckout),
            caption: "worktrees.cannot_remove",
            startDecorator: .letter(letterCharacter(for: group.displayName), letterAccent(for: group.primaryPath))
        )
        ForEach(group.worktrees) { row in
            worktreeRow(row)
                .transition(.opacity.combined(with: .move(edge: .leading)))
        }
        if group.worktrees.isEmpty {
            note("worktrees.none_in_project")
        }
    }

    private func worktreeRow(_ row: WorktreeRowItem) -> some View {
        OpenCodeListItem(
            title: .verbatim(row.name),
            subtitle: .verbatim(WorktreesCopy.sessions(row.sessionCount)),
            detailSegments: [OpenCodeListItemSubtitleSegment(text: row.directory, tone: .tertiary)],
            status: row.isRemoving
                ? OpenCodeListItemStatus(text: "worktrees.removing", tone: .stale)
                : row.isRunning ? OpenCodeListItemStatus(text: "session_list.status.running", tone: .interactive) : nil,
            titleTag: row.isRemoving ? nil : row.changeCount.flatMap { $0 > 0 ? .verbatim(WorktreesCopy.changed($0)) : nil },
            enabled: !row.isRemoving,
            startDecorator: .icon(.callSplit),
            action: { viewModel.openActions(directory: row.directory) },
            trailing: {
                if row.isRemoving {
                    ProgressView()
                        .controlSize(.small)
                        .padding(.horizontal, 12)
                } else {
                    OpenCodeButton(
                        variant: .iconCircle(.moreVert, accessibilityLabel: .verbatim(WorktreesCopy.more(row.name))),
                        contrast: .low,
                        action: { viewModel.openActions(directory: row.directory) }
                    )
                }
            }
        )
        .accessibilityIdentifier("worktreeRow:\(row.directory)")
    }

    private func note(_ text: OpenCodeText) -> some View {
        Text(openCode: text)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
    }

    @ViewBuilder
    private var actionsSheet: some View {
        if let actions = viewModel.actions {
            if actions.canShowOnHome {
                OpenCodeBottomSheetEntry(title: "worktrees.actions.show_on_home", startDecorator: .icon(.list)) {
                    viewModel.showOnHome(directory: actions.directory)
                }
            }
            OpenCodeBottomSheetEntry(title: "worktrees.actions.new_session", startDecorator: .icon(.add)) {
                viewModel.newSessionHere(directory: actions.directory)
            }
            OpenCodeBottomSheetEntry(
                title: "worktrees.actions.remove",
                destructive: true,
                startDecorator: .icon(.delete)
            ) {
                viewModel.requestRemove(directory: actions.directory)
            }
        }
    }

    private static func message(_ snackbar: WorktreesSnackbar) -> OpenCodeSnackbarMessage {
        switch snackbar {
        case let .removed(name, id):
            OpenCodeSnackbarMessage(text: WorktreesCopy.removed(name), id: id)
        case let .failed(name, _, message, id):
            OpenCodeSnackbarMessage(
                text: message.map { WorktreesCopy.removeFailed(name, message: $0) } ?? WorktreesCopy.removeFailedConnection(name),
                variant: .error,
                actionLabel: String(localized: "common.retry", defaultValue: "Retry"),
                id: id
            )
        }
    }
}

/// Worktrees copy; the keys match Android's `worktrees_*` strings.
enum WorktreesCopy {
    static func subtitle(worktrees: Int, projects: Int) -> String {
        let count = String(localized: LocalizedStringResource("worktrees.count", defaultValue: "\(worktrees) worktrees"))
        let projectCount = String(localized: LocalizedStringResource("home.project_count", defaultValue: "\(projects) projects"))
        return String(localized: LocalizedStringResource("worktrees.subtitle", defaultValue: "\(count) · \(projectCount)"))
    }

    static func sessions(_ count: Int) -> String {
        guard count > 0 else { return String(localized: "worktrees.no_sessions", defaultValue: "no sessions") }
        return String(localized: LocalizedStringResource("worktrees.session_count", defaultValue: "\(count) sessions"))
    }

    static func changed(_ count: Int) -> String {
        String(localized: LocalizedStringResource("worktrees.changed", defaultValue: "\(count) changed"))
    }

    static func more(_ name: String) -> String {
        String(localized: LocalizedStringResource("worktrees.more", defaultValue: "More actions for \(name)"))
    }

    static func actionsSubtitle(project: String, sessions count: Int) -> String {
        let sessions = String(localized: LocalizedStringResource("worktrees.session_count", defaultValue: "\(count) sessions"))
        return String(localized: LocalizedStringResource("worktrees.actions.subtitle", defaultValue: "\(project) · \(sessions)"))
    }

    static func removeTitle(_ dialog: WorktreeRemoveDialog?) -> String {
        guard case let .confirm(_, name, _, _, _, _)? = dialog else { return "" }
        return String(localized: LocalizedStringResource("worktrees.remove.title", defaultValue: "Remove worktree “\(name)”?"))
    }

    static func removeMessage(_ dialog: WorktreeRemoveDialog?) -> String {
        guard case let .confirm(directory, _, _, _, _, _)? = dialog else { return "" }
        return String(localized: LocalizedStringResource("worktrees.remove.message", defaultValue: "Deletes \(directory) on the server."))
    }

    static func removeNotes(_ dialog: WorktreeRemoveDialog?) -> [OpenCodeDialogNote] {
        guard case let .confirm(_, _, changeCount, changesUnknown, sessionCount, changesUnchecked)? = dialog else {
            return []
        }
        var notes: [OpenCodeDialogNote] = []
        if let changes = changeCount, changes > 0 {
            notes.append(OpenCodeDialogNote(
                text: OpenCodeText(LocalizedStringResource(
                    "worktrees.remove.changes",
                    defaultValue: "\(changes) uncommitted changes will be lost."
                )),
                tone: .warning
            ))
        } else if changesUnknown {
            notes.append(OpenCodeDialogNote(text: "worktrees.remove.changes_unknown", tone: .warning))
        } else if changesUnchecked {
            notes.append(OpenCodeDialogNote(text: "worktrees.remove.changes_unchecked", tone: .warning))
        }
        if sessionCount > 0 {
            notes.append(OpenCodeDialogNote(text: OpenCodeText(LocalizedStringResource(
                "worktrees.remove.sessions",
                defaultValue: "\(sessionCount) sessions use this worktree. They stay on Home but can’t run here again."
            ))))
        }
        return notes
    }

    static func blockedTitle(_ dialog: WorktreeRemoveDialog?) -> String {
        guard case let .blocked(name, _)? = dialog else { return "" }
        return String(localized: LocalizedStringResource("worktrees.blocked.title", defaultValue: "Can’t remove “\(name)” yet"))
    }

    static func removed(_ name: String) -> String {
        String(localized: LocalizedStringResource("worktrees.removed", defaultValue: "Removed worktree “\(name)”"))
    }

    static func removeFailed(_ name: String, message: String) -> String {
        String(localized: LocalizedStringResource("worktrees.remove_failed", defaultValue: "Couldn’t remove “\(name)”. \(message)"))
    }

    static func removeFailedConnection(_ name: String) -> String {
        String(localized: LocalizedStringResource(
            "worktrees.remove_failed_connection",
            defaultValue: "Couldn’t remove “\(name)”. Check the connection and try again."
        ))
    }

    /// ⋯ menu subtitle: "N across M projects", None, or nothing before Home listed worktrees.
    static func menuSubtitle(_ snapshot: HomeWorktreesSnapshot) -> String? {
        guard snapshot.worktreesLoaded else { return nil }
        let count = snapshot.worktreeCount
        guard count > 0 else { return String(localized: "worktrees.menu_none", defaultValue: "None") }
        let projects = snapshot.projectsWithWorktrees
        let projectCount = String(localized: LocalizedStringResource("home.project_count", defaultValue: "\(projects) projects"))
        return String(localized: LocalizedStringResource("worktrees.menu_subtitle", defaultValue: "\(count) across \(projectCount)"))
    }
}
