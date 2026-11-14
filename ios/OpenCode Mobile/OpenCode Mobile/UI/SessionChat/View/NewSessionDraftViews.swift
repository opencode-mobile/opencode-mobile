import SwiftUI

/// The empty timeline of a New session draft (#1004): the Run session in card, centred between
/// the header and the composer.
struct NewSessionDraftContent: View {
    let draft: SessionChatDraftUiState
    let keyboardVisible: Bool
    let bottomInset: CGFloat
    let onOpenRow: (NewSessionCardRowKind) -> Void

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        RunSessionInCard(
            caption: OpenCodeText(NewSessionDraftCopy.runInCaption),
            entries: draft.rows.map { row in
                RunSessionInCard<String>.Entry(id: row.id, row: row.row) { onOpenRow(row.kind) }
            },
            footnote: footnote,
            compact: keyboardVisible
        )
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, bottomInset)
        .accessibilityIdentifier("newSessionDraftCard")
    }

    private var footnote: OpenCodeText? {
        guard let path = draft.checkoutPath else { return nil }
        guard case let .verbatim(string) = path.storage else { return path }
        return .verbatim(compactHomePath(string, hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix))
    }
}

/// Home's Add project flow, offered to the draft's Project sheet. `start` opens it and calls
/// back with the added path.
struct HomeAddProjectAction {
    let start: (@escaping @MainActor (String) -> Void) -> Void
}

private struct HomeAddProjectKey: EnvironmentKey {
    static let defaultValue: HomeAddProjectAction? = nil
}

private struct HomeRunningCountsKey: EnvironmentKey {
    static let defaultValue: [String: Int] = [:]
}

extension EnvironmentValues {
    /// Nil outside Home (the Session Chat Playground): the Project sheet hides Add project….
    var homeAddProject: HomeAddProjectAction? {
        get { self[HomeAddProjectKey.self] }
        set { self[HomeAddProjectKey.self] = newValue }
    }

    /// Running sessions per canonical checkout, for the draft's Worktree sheet.
    var homeRunningCounts: [String: Int] {
        get { self[HomeRunningCountsKey.self] }
        set { self[HomeRunningCountsKey.self] = newValue }
    }
}

extension View {
    /// The Project, Worktree and Starts from sheets of a New session draft.
    func newSessionDraftSheets(
        draft: SessionChatDraftUiState?,
        presented: Binding<NewSessionCardRowKind?>,
        addProject: HomeAddProjectAction?,
        onSelectProject: @escaping (String) -> Void,
        onProjectAdded: @escaping @MainActor (String) -> Void,
        onSelectPlace: @escaping (NewSessionPlace) -> Void,
        onSelectBase: @escaping (String?) -> Void,
        onLoadBases: @escaping () -> Void
    ) -> some View {
        modifier(
            NewSessionDraftSheets(
                draft: draft,
                presented: presented,
                addProject: addProject,
                onSelectProject: onSelectProject,
                onProjectAdded: onProjectAdded,
                onSelectPlace: onSelectPlace,
                onSelectBase: onSelectBase,
                onLoadBases: onLoadBases
            )
        )
    }
}

private struct NewSessionDraftSheets: ViewModifier {
    let draft: SessionChatDraftUiState?
    @Binding var presented: NewSessionCardRowKind?
    let addProject: HomeAddProjectAction?
    let onSelectProject: (String) -> Void
    let onProjectAdded: @MainActor (String) -> Void
    let onSelectPlace: (NewSessionPlace) -> Void
    let onSelectBase: (String?) -> Void
    let onLoadBases: () -> Void

    @State private var projectQuery = ""

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: binding(.project),
                title: OpenCodeText(NewSessionDraftCopy.project),
                height: .half,
                keyboard: .keep,
                pinnedTop: {
                    OpenCodeTextInput(
                        text: $projectQuery,
                        hint: OpenCodeText(NewSessionDraftCopy.searchProjects),
                        kind: .search
                    )
                    .openCodeBottomSheetContentInset()
                    .padding(.bottom, 8)
                    .accessibilityIdentifier("newSessionProjectSearch")
                },
                content: {
                    NewSessionProjectSheet(
                        options: draft.map { NewSessionDraftPresentation.filter($0.projectOptions, query: projectQuery) } ?? [],
                        showsNoMatches: !projectQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                        canAddProject: addProject != nil,
                        onSelect: { key in
                            presented = nil
                            onSelectProject(key)
                        },
                        onAddProject: {
                            presented = nil
                            addProject?.start(onProjectAdded)
                        }
                    )
                }
            )
            .openCodeBottomSheet(
                isPresented: binding(.worktree),
                title: draft?.worktreeSheetTitle ?? OpenCodeText(NewSessionDraftCopy.worktree),
                height: .fitAllContent
            ) {
                if let options = draft?.worktreeOptions {
                    NewSessionWorktreeSheet(options: options) { place in
                        presented = nil
                        onSelectPlace(place)
                    }
                }
            }
            .openCodeBottomSheet(
                isPresented: binding(.startsFrom),
                title: OpenCodeText(NewSessionDraftCopy.startsFrom),
                height: .half
            ) {
                NewSessionBaseBranchSheet(
                    rows: draft?.baseBranchRows ?? [],
                    load: draft?.baseBranchesLoad ?? .idle,
                    onRetry: onLoadBases
                ) { ref in
                    presented = nil
                    onSelectBase(ref)
                }
            }
            .onChange(of: presented) { _, kind in
                switch kind {
                case .project: projectQuery = ""
                case .startsFrom: onLoadBases()
                case .worktree, nil: break
                }
            }
            .onChange(of: draft == nil) { _, ended in
                if ended { presented = nil }
            }
    }

    private func binding(_ kind: NewSessionCardRowKind) -> Binding<Bool> {
        Binding(
            get: { presented == kind && draft != nil },
            set: { shown in
                if !shown, presented == kind { presented = nil }
            }
        )
    }
}

/// Project sheet body: Home's projects (Filter by project look), then Add project….
struct NewSessionProjectSheet: View {
    let options: [NewSessionProjectOption]
    let showsNoMatches: Bool
    let canAddProject: Bool
    let onSelect: (String) -> Void
    let onAddProject: () -> Void

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        VStack(spacing: 0) {
            if options.isEmpty, showsNoMatches {
                OpenCodeListItem(title: OpenCodeText(NewSessionDraftCopy.noProjectMatches), enabled: false)
            }
            ForEach(options) { option in
                OpenCodeListItem(
                    title: .verbatim(option.name),
                    subtitle: .verbatim(compactHomePath(
                        option.path,
                        hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
                    )),
                    selected: option.selected,
                    startDecorator: .letter(option.letter, option.accent),
                    action: { onSelect(option.key) },
                    trailing: { selectedCheck(option.selected) }
                )
                .accessibilityAddTraits(option.selected ? .isSelected : [])
                .accessibilityIdentifier("newSessionProjectOption")
            }
            if canAddProject {
                OpenCodeListItem(
                    title: OpenCodeText(NewSessionDraftCopy.addProject),
                    startDecorator: .iconCircle(.createNewFolder),
                    action: onAddProject
                )
                .accessibilityIdentifier("newSessionAddProject")
            }
        }
    }
}

/// Worktree sheet body: Local, New worktree, then the project's worktrees.
struct NewSessionWorktreeSheet: View {
    let options: NewSessionWorktreeOptions
    let onSelect: (NewSessionPlace) -> Void

    var body: some View {
        VStack(spacing: 0) {
            row(options.local)
            if let newWorktree = options.newWorktree {
                row(newWorktree)
            }
            if !options.worktrees.isEmpty {
                OpenCodeSectionHeader(title: OpenCodeText(NewSessionDraftCopy.worktreesSection))
                ForEach(options.worktrees) { option in
                    row(option)
                }
            }
        }
    }

    private func row(_ option: NewSessionWorktreeOption) -> some View {
        OpenCodeListItem(
            title: option.title,
            subtitle: option.subtitle,
            selected: option.selected,
            startDecorator: .icon(option.icon),
            action: { onSelect(option.place) },
            trailing: { selectedCheck(option.selected) }
        )
        .accessibilityAddTraits(option.selected ? .isSelected : [])
        .accessibilityIdentifier(option.accessibilityIdentifier)
    }
}

/// Starts from sheet body: the main checkout's branch (default), then the server's branches.
struct NewSessionBaseBranchSheet: View {
    let rows: [WorktreeCreateBaseOption]
    let load: NewSessionBaseBranchesLoad
    let onRetry: () -> Void
    let onSelect: (String?) -> Void

    var body: some View {
        VStack(spacing: 0) {
            if let first = rows.first {
                row(first)
            }
            switch load {
            case .idle, .loading:
                OpenCodeListItem(title: OpenCodeText(WorktreeCreateCopy.baseLoading), enabled: false)
                    .accessibilityIdentifier("newSessionBaseLoading")
            case .failed:
                OpenCodeListItem(
                    title: OpenCodeText(WorktreeCreateCopy.baseFailed),
                    subtitle: "timeline.state.retry",
                    action: onRetry
                )
                .accessibilityIdentifier("newSessionBaseFailed")
            case .loaded:
                ForEach(rows.dropFirst()) { option in
                    row(option)
                }
            }
        }
    }

    private func row(_ option: WorktreeCreateBaseOption) -> some View {
        OpenCodeListItem(
            title: option.title,
            selected: option.isSelected,
            startDecorator: .icon(.gitBranch),
            action: { onSelect(option.ref) },
            trailing: { selectedCheck(option.isSelected) }
        )
        .accessibilityAddTraits(option.isSelected ? .isSelected : [])
        .accessibilityIdentifier("newSessionBaseOption:" + (option.ref ?? ""))
    }
}

@ViewBuilder
private func selectedCheck(_ selected: Bool) -> some View {
    if selected {
        OpenCodeIconImage(icon: .check, size: 18)
            .foregroundStyle(OpenCodeColors.textInteractiveBase)
            .padding(.trailing, 16)
            .accessibilityHidden(true)
    }
}
