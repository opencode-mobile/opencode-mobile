import Foundation

/// New session draft text (#1004), resolved from the String Catalog (`new_session.*`, same keys on
/// Android). Project names and branches stay arguments.
nonisolated enum NewSessionDraftCopy {
    static var title: LocalizedStringResource { LocalizedStringResource("new_session.title", defaultValue: "New session") }
    static var draftSubtitle: LocalizedStringResource {
        LocalizedStringResource("new_session.draft_subtitle", defaultValue: "Draft \u{00B7} created when you send")
    }
    static var runInCaption: LocalizedStringResource {
        LocalizedStringResource("new_session.run_in_caption", defaultValue: "Run session in")
    }
    static var project: LocalizedStringResource { LocalizedStringResource("new_session.project", defaultValue: "Project") }
    static var worktree: LocalizedStringResource { LocalizedStringResource("new_session.worktree", defaultValue: "Worktree") }
    static var startsFrom: LocalizedStringResource { LocalizedStringResource("new_session.starts_from", defaultValue: "Starts from") }
    static var local: LocalizedStringResource { LocalizedStringResource("new_session.local", defaultValue: "Local") }
    static var newWorktree: LocalizedStringResource { LocalizedStringResource("new_session.new_worktree", defaultValue: "New worktree") }
    static var notGit: LocalizedStringResource {
        LocalizedStringResource("new_session.not_git", defaultValue: "None \u{00B7} not a Git repo")
    }
    static var discardDraft: LocalizedStringResource { LocalizedStringResource("new_session.discard_draft", defaultValue: "Discard draft") }
    static var addProject: LocalizedStringResource { LocalizedStringResource("new_session.add_project", defaultValue: "Add project\u{2026}") }
    static var newWorktreeSubtitle: LocalizedStringResource {
        LocalizedStringResource("new_session.new_worktree_subtitle", defaultValue: "Isolated copy, created when you send")
    }
    static var worktreesSection: LocalizedStringResource {
        LocalizedStringResource("new_session.worktrees_section", defaultValue: "Worktrees")
    }
    static var createdOnSend: LocalizedStringResource {
        LocalizedStringResource("new_session.created_on_send", defaultValue: "Created when you send")
    }
    static var searchProjects: LocalizedStringResource {
        LocalizedStringResource("new_session.search_projects", defaultValue: "Search projects")
    }
    static var noProjectMatches: LocalizedStringResource {
        LocalizedStringResource("new_session.no_project_matches", defaultValue: "No matching projects")
    }

    static func runIn(_ projectName: String) -> LocalizedStringResource {
        LocalizedStringResource("new_session.run_in_project", defaultValue: "Run in \(projectName)")
    }

    /// Local row subtitle when the main checkout's branch is known.
    static func currentCheckout(_ branch: String) -> LocalizedStringResource {
        LocalizedStringResource("new_session.current_checkout", defaultValue: "\(branch) \u{00B7} current checkout")
    }
}

/// What the draft screen knows from Home: its projects in Home order, the Worktrees setting
/// (#785), and the running sessions per checkout.
struct NewSessionDraftContext: Equatable {
    var projects: [HomeProject] = []
    var worktreesEnabled = true
    /// Running sessions by canonical checkout directory.
    var runningCounts: [String: Int] = [:]
}

/// The v2 branch list of the Starts from sheet.
enum NewSessionBaseBranchesLoad: Equatable {
    case idle
    case loading
    case loaded([String])
    case failed
}

/// Which card row a tap opened.
enum NewSessionCardRowKind: String, Equatable {
    case project
    case worktree
    case startsFrom
}

struct NewSessionCardRow: Equatable, Identifiable {
    let kind: NewSessionCardRowKind
    let row: RunSessionInRow

    var id: String { kind.rawValue }
}

/// A Project sheet row: Filter by project look, in Home order.
struct NewSessionProjectOption: Equatable, Identifiable {
    let key: String
    let name: String
    let path: String
    let letter: Character
    let accent: LetterAccent
    let selected: Bool

    var id: String { key }
}

/// A Worktree sheet row.
struct NewSessionWorktreeOption: Equatable, Identifiable {
    let place: NewSessionPlace
    let title: OpenCodeText
    let subtitle: OpenCodeText?
    let icon: OpenCodeIcon
    let selected: Bool
    let accessibilityIdentifier: String

    var id: String { accessibilityIdentifier }
}

struct NewSessionWorktreeOptions: Equatable {
    let local: NewSessionWorktreeOption
    /// Nil when `NewWorktreeAvailability` does not allow one.
    let newWorktree: NewSessionWorktreeOption?
    /// The project's other checkouts, by branch.
    let worktrees: [NewSessionWorktreeOption]
}

/// Everything the draft screen shows besides the composer.
struct SessionChatDraftUiState: Equatable {
    let rows: [NewSessionCardRow]
    /// Main checkout or worktree path; "Created when you send" for a New worktree.
    let checkoutPath: OpenCodeText?
    let projectOptions: [NewSessionProjectOption]
    let worktreeSheetTitle: OpenCodeText
    let worktreeOptions: NewSessionWorktreeOptions
    let baseBranchRows: [WorktreeCreateBaseOption]
    let baseBranchesLoad: NewSessionBaseBranchesLoad
    let isCreating: Bool
}

enum NewSessionDraftPresentation {
    static func uiState(
        draft: NewSessionDraft,
        context: NewSessionDraftContext,
        serverProtocol: ServerProtocol,
        serverCanCreateWorktrees: Bool,
        isCreating: Bool,
        baseBranches: NewSessionBaseBranchesLoad
    ) -> SessionChatDraftUiState {
        let project = project(of: draft, in: context.projects)
        let projectName = project?.displayName ?? HomeProjectGrouping.folderName(draft.projectDirectory)
        let mainBranch = project?.worktree(at: draft.projectDirectory)?.branch
        var rows = [
            NewSessionCardRow(
                kind: .project,
                row: RunSessionInRow(
                    tile: .letter(
                        letterCharacter(for: projectName),
                        letterAccent(for: project?.primaryPath ?? draft.projectDirectory)
                    ),
                    label: OpenCodeText(NewSessionDraftCopy.project),
                    value: .verbatim(projectName),
                    accessibilityIdentifier: "newSessionProjectRow"
                )
            ),
        ]
        if let worktreeRow = worktreeRow(draft: draft, project: project, worktreesEnabled: context.worktreesEnabled) {
            rows.append(NewSessionCardRow(kind: .worktree, row: worktreeRow))
        }
        if case let .newWorktree(base) = draft.place, serverProtocol == .v2 {
            let branch = base ?? mainBranch
            rows.append(
                NewSessionCardRow(
                    kind: .startsFrom,
                    row: RunSessionInRow(
                        tile: .icon(.gitBranch),
                        label: OpenCodeText(NewSessionDraftCopy.startsFrom),
                        value: branch.map { OpenCodeText.verbatim($0) } ?? OpenCodeText(WorktreeCreateCopy.currentCheckout),
                        monospacedValue: branch != nil,
                        accessibilityIdentifier: "newSessionStartsFromRow"
                    )
                )
            )
        }
        let checkoutPath: OpenCodeText? = switch draft.place {
        case .local: .verbatim(draft.projectDirectory)
        case let .worktree(directory, _): .verbatim(directory)
        case .newWorktree: OpenCodeText(NewSessionDraftCopy.createdOnSend)
        }
        return SessionChatDraftUiState(
            rows: rows,
            checkoutPath: checkoutPath,
            projectOptions: projectOptions(projects: context.projects, selectedKey: project?.key),
            worktreeSheetTitle: OpenCodeText(NewSessionDraftCopy.runIn(projectName)),
            worktreeOptions: worktreeOptions(
                draft: draft,
                project: project,
                context: context,
                serverCanCreateWorktrees: serverCanCreateWorktrees
            ),
            baseBranchRows: baseBranchRows(draft: draft, mainBranch: mainBranch, baseBranches: baseBranches),
            baseBranchesLoad: baseBranches,
            isCreating: isCreating
        )
    }

    /// The draft's Home project: by key, else the one that holds its directory.
    static func project(of draft: NewSessionDraft, in projects: [HomeProject]) -> HomeProject? {
        if let key = draft.projectKey, let match = projects.first(where: { $0.key == key }) {
            return match
        }
        return projects.first { $0.worktree(at: draft.projectDirectory) != nil || $0.hasMember(draft.projectDirectory) }
    }

    /// Hidden while worktrees are off and the draft runs on the main checkout; disabled for a
    /// folder that is not a Git repository.
    static func worktreeRow(draft: NewSessionDraft, project: HomeProject?, worktreesEnabled: Bool) -> RunSessionInRow? {
        guard let project else { return nil }
        if !worktreesEnabled, draft.place.isLocal { return nil }
        if !project.isRepository {
            return RunSessionInRow(
                tile: .icon(.folder),
                label: OpenCodeText(NewSessionDraftCopy.worktree),
                value: OpenCodeText(NewSessionDraftCopy.notGit),
                enabled: false,
                accessibilityIdentifier: "newSessionWorktreeRow"
            )
        }
        switch draft.place {
        case .local:
            return RunSessionInRow(
                tile: .icon(.folderCheck),
                label: OpenCodeText(NewSessionDraftCopy.worktree),
                value: OpenCodeText(NewSessionDraftCopy.local),
                accessibilityIdentifier: "newSessionWorktreeRow"
            )
        case let .worktree(directory, branch):
            let known = project.worktree(at: directory)
            let title = branch ?? known?.branch
            return RunSessionInRow(
                tile: .icon(.gitBranch),
                label: OpenCodeText(NewSessionDraftCopy.worktree),
                value: .verbatim(title ?? ServerPathPolicy.name(directory)),
                monospacedValue: title != nil,
                highlighted: true,
                accessibilityIdentifier: "newSessionWorktreeRow"
            )
        case .newWorktree:
            return RunSessionInRow(
                tile: .icon(.add),
                label: OpenCodeText(NewSessionDraftCopy.worktree),
                value: OpenCodeText(NewSessionDraftCopy.newWorktree),
                highlighted: true,
                accessibilityIdentifier: "newSessionWorktreeRow"
            )
        }
    }

    static func projectOptions(projects: [HomeProject], selectedKey: String?) -> [NewSessionProjectOption] {
        projects.map { project in
            NewSessionProjectOption(
                key: project.key,
                name: project.displayName,
                path: project.primaryPath,
                letter: letterCharacter(for: project.displayName),
                accent: letterAccent(for: project.primaryPath),
                selected: project.key == selectedKey
            )
        }
    }

    /// Project sheet search: name contains the query, ignoring case and accents.
    static func filter(_ options: [NewSessionProjectOption], query: String) -> [NewSessionProjectOption] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return options }
        return options.filter { $0.name.range(of: trimmed, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }

    /// Local, New worktree (when allowed), then the project's other checkouts by branch with
    /// their running count. No total session counts (#981).
    static func worktreeOptions(
        draft: NewSessionDraft,
        project: HomeProject?,
        context: NewSessionDraftContext,
        serverCanCreateWorktrees: Bool
    ) -> NewSessionWorktreeOptions {
        let mainBranch = project?.worktree(at: draft.projectDirectory)?.branch
        let local = NewSessionWorktreeOption(
            place: .local,
            title: OpenCodeText(NewSessionDraftCopy.local),
            subtitle: mainBranch.map { OpenCodeText(NewSessionDraftCopy.currentCheckout($0)) }
                ?? OpenCodeText(LocalizedStringResource("home.main_checkout", defaultValue: "main checkout")),
            icon: .folderCheck,
            selected: draft.place.isLocal,
            accessibilityIdentifier: "newSessionPlaceLocal"
        )
        let availability = project.map { project in
            NewWorktreeAvailability.runIn(
                worktreesEnabled: context.worktreesEnabled,
                isRepository: project.isRepository,
                projectID: project.projectID,
                serverCanCreate: serverCanCreateWorktrees
            )
        }
        let newWorktree = availability?.enabled == true ? NewSessionWorktreeOption(
            place: .newWorktree(baseBranch: nil),
            title: OpenCodeText(NewSessionDraftCopy.newWorktree),
            subtitle: OpenCodeText(NewSessionDraftCopy.newWorktreeSubtitle),
            icon: .add,
            selected: draft.place.isNewWorktree,
            accessibilityIdentifier: "newSessionPlaceNewWorktree"
        ) : nil
        let selectedDirectory: String? = if case let .worktree(directory, _) = draft.place {
            ServerPathPolicy.canonical(directory)
        } else {
            nil
        }
        let worktrees = (context.worktreesEnabled ? project?.worktrees ?? [] : [])
            .filter { !$0.isMain && $0.directory != ServerPathPolicy.canonical(draft.projectDirectory) }
            .map { worktree in
                let running = context.runningCounts[worktree.directory] ?? 0
                return NewSessionWorktreeOption(
                    place: .worktree(directory: worktree.directory, branch: worktree.branch),
                    title: .verbatim(worktree.title),
                    subtitle: running > 0 ? .verbatim(HomeProjectCopy.running(running)) : .verbatim(worktree.folderName),
                    icon: .gitBranch,
                    selected: worktree.directory == selectedDirectory,
                    accessibilityIdentifier: "newSessionPlaceWorktree:" + worktree.directory
                )
            }
        return NewSessionWorktreeOptions(local: local, newWorktree: newWorktree, worktrees: worktrees)
    }

    /// The main checkout's branch (the default) first, then the other refs the server listed.
    static func baseBranchRows(
        draft: NewSessionDraft,
        mainBranch: String?,
        baseBranches: NewSessionBaseBranchesLoad
    ) -> [WorktreeCreateBaseOption] {
        guard case let .newWorktree(selected) = draft.place else { return [] }
        let options: [String] = if case let .loaded(list) = baseBranches { list } else { [] }
        return WorktreeCreateFormState.baseRows(sourceBranch: mainBranch, options: options, selectedRef: selected)
    }
}
