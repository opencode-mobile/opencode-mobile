import FactoryKit
import SwiftUI

struct ChangesView: View {
    @Environment(\.dismiss) private var dismiss
    var splitGate = OpenCodeSplitLayoutGate()
    @State private var viewModel: ChangesViewModel
    @State private var sourceMenuPresented = false
    @State private var splitSelection: String?
    @State private var pushedPath: String?
    @State private var didAttemptDeepLink = false
    /// Filter Analytics fires once per screen; the query text is never sent.
    @State private var loggedFilterEdit = false
    private let initialFilePath: String?

    init(
        sessionID: String,
        worktree: String,
        messageID: String?,
        initialSource: ChangeSource = .lastTurn,
        initialFilePath: String? = nil,
        viewModel: ChangesViewModel? = nil
    ) {
        self.initialFilePath = initialFilePath
        _viewModel = State(initialValue: viewModel ?? ChangesViewModel(
            sessionID: sessionID,
            worktree: worktree,
            messageID: messageID,
            initialSource: initialSource,
            repository: Container.shared.sessionRepository(),
            capabilitiesProvider: { Container.shared.activeConnectionManager().capabilities },
            lastTurnReviewChangesStore: Container.shared.lastTurnReviewChangesStore(),
            foregroundEventStream: Container.shared.foregroundEventStream()
        ))
    }

    /// Same breakpoint as Home: a wide window keeps the file list beside the diff.
    private var isSplitLayout: Bool {
        splitGate.usesSplitLayout
    }

    var body: some View {
        Group {
            if isSplitLayout {
                contentSplit
            } else {
                stackedChanges
            }
        }
        .onChange(of: isSplitLayout) { _, split in
            if split {
                if splitSelection == nil { splitSelection = pushedPath }
                pushedPath = nil
            } else if pushedPath == nil {
                // Drop the split selection so a later pop does not restore it.
                pushedPath = splitSelection
                splitSelection = nil
            }
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.changes,
                presentation: ProductAnalytics.Presentation.route
            )
            await viewModel.load()
            attemptDeepLinkIfNeeded()
        }
        .task {
            await viewModel.observeSessionIdle()
        }
        .onChange(of: viewModel.state.phase) { _, _ in
            attemptDeepLinkIfNeeded()
        }
        .onDisappear {
            if splitSelection == nil, pushedPath == nil {
                viewModel.clearFilter()
            }
        }
    }

    private var stackedChanges: some View {
        changesColumn(selectedPath: nil, constrainWidth: true) { pushedPath = $0 }
            .navigationDestination(item: $pushedPath) { path in
                if let file = viewModel.content?.files.first(where: { $0.path == path }) {
                    FileViewerView(file: file)
                }
            }
    }

    private var contentSplit: some View {
        OpenCodeSplitPane(screen: .changes, onCollapsedChange: { _ in
            logAction(ProductAnalytics.Action.splitPaneToggleSidebar)
        }) {
            changesColumn(selectedPath: splitSelection, constrainWidth: false) { splitSelection = $0 }
        } trailing: { control in
            if let path = splitSelection, let file = viewModel.content?.files.first(where: { $0.path == path }) {
                FileViewerView(file: file, detailControl: control, usesReadableColumn: false)
                    .id(path)
            } else {
                VStack(spacing: 0) {
                    OpenCodeTopBar(
                        title: "Changes",
                        leading: OpenCodeSplitPane.detailLeading(control, back: {})
                    )
                    OpenCodeEmptyState(
                        title: "Select a file",
                        message: "Choose a changed file to see the diff.",
                        icon: .list
                    )
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(OpenCodeColors.backgroundBase)
            }
        }
    }

    private func changesColumn(
        selectedPath: String?,
        constrainWidth: Bool,
        onOpenFile: @escaping (String) -> Void
    ) -> some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(isPresented: $sourceMenuPresented, entries: sourceMenuEntries) {
                OpenCodeTopBar(
                    title: "Changes",
                    subtitle: .unlocalized(viewModel.state.selectedSource.displayName),
                    leading: .back(action: exitChanges),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "Choose change source") {
                            logAction(ProductAnalytics.Action.changesOpenSourceMenu)
                            sourceMenuPresented = true
                        },
                    ],
                    showDivider: true
                )
            }
            columnContent(selectedPath: selectedPath, onOpenFile: onOpenFile)
                .modifier(ReadableColumnModifier(enabled: constrainWidth))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
    }

    private func attemptDeepLinkIfNeeded() {
        guard !didAttemptDeepLink, let initialFilePath, !initialFilePath.isEmpty else { return }
        guard case .content(let changeSet) = viewModel.state.phase else { return }
        if changeSet.files.contains(where: { $0.path == initialFilePath }) {
            if isSplitLayout {
                splitSelection = initialFilePath
            } else {
                pushedPath = initialFilePath
            }
        }
        didAttemptDeepLink = true
    }

    private func exitChanges() {
        logAction(ProductAnalytics.Action.changesBack)
        viewModel.clearFilter()
        dismiss()
    }

    /// File paths and filter text stay out of Analytics.
    private func logAction(_ actionId: String, parameters: [String: String] = [:]) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.changes,
            actionId: actionId,
            parameters: parameters
        )
    }

    @ViewBuilder private func columnContent(
        selectedPath: String?,
        onOpenFile: @escaping (String) -> Void
    ) -> some View {
        switch viewModel.state.phase {
        case .loading:
            OpenCodeStatusStack(title: "Loading changes", subtitle: "Fetching the diff…")
        case .noTurn:
            refreshableMessage("No completed turn", detail: "Send a prompt and wait for it to complete, then try again.")
        case .noChanges:
            refreshableMessage(.unlocalized(viewModel.state.selectedSource.emptyTitle), detail: .unlocalized(viewModel.state.selectedSource.emptyDetail))
        case let .content(changeSet):
            changedFiles(changeSet, selectedPath: selectedPath, onOpenFile: onOpenFile)
        case let .error(error):
            OpenCodeEmptyState(
                title: "Unable to load changes",
                message: .verbatim(error, fallback: "The changes didn't load."),
                primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                    logAction(ProductAnalytics.Action.changesRetry)
                    Task { await viewModel.retry() }
                }
            )
        }
    }

    private var sourceMenuEntries: [OpenCodeOverflowMenuEntry] {
        viewModel.state.availableSources.map { source in
            let selected = source == viewModel.state.selectedSource
            return OpenCodeOverflowMenuEntry(
                id: source.rawValue,
                title: .unlocalized(source.displayName),
                icon: selected ? .check : nil,
                accessibilityLabel: .unlocalized(selected ? "\(source.displayName), selected" : source.displayName),
                action: {
                    splitSelection = nil
                    pushedPath = nil
                    Task { await viewModel.selectSource(source) }
                }
            )
        }
    }

    private func changedFiles(
        _ changeSet: ChangesContentState,
        selectedPath: String?,
        onOpenFile: @escaping (String) -> Void
    ) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                aggregateSummary(changeSet)

                if let refreshError = viewModel.state.refreshError {
                    refreshFailure(refreshError)
                }

                OpenCodeTextInput(
                    text: Binding(
                        get: { viewModel.content?.filter ?? "" },
                        set: { value in
                            logFilterEditOnce()
                            viewModel.updateFilter(value)
                        }
                    ),
                    hint: "Filter",
                    accessibilityLabel: "Filter changed files"
                )

                if changeSet.filteredFiles.isEmpty {
                    VStack(spacing: 12) {
                        message("No matching files", detail: "Try another path or clear the filter.")
                        OpenCodeButton(
                            variant: .compactText(title: "Clear filter"),
                            contrast: .low,
                            action: {
                                logAction(ProductAnalytics.Action.changesClearFilter)
                                viewModel.clearFilter()
                            }
                        )
                    }
                } else {
                    ForEach(changeSet.fileGroups) { group in
                        VStack(alignment: .leading, spacing: 8) {
                            OpenCodeSectionHeader(
                                title: .verbatim(group.directory),
                                variant: .plain,
                                trailing: .count(group.files.count),
                                inset: .flush
                            )
                            OpenCodeListGroup(group.files, id: \.path) { file in
                                fileRow(file, changeSet: changeSet, selectedPath: selectedPath, onOpenFile: onOpenFile)
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .accessibilityIdentifier("changesContent")
        .id(viewModel.state.selectedSource)
        .refreshable {
            logAction(ProductAnalytics.Action.changesRefresh)
            await viewModel.refresh()
        }
    }

    private func logFilterEditOnce() {
        guard !loggedFilterEdit else { return }
        loggedFilterEdit = true
        logAction(ProductAnalytics.Action.changesUpdateFilter)
    }

    private func aggregateSummary(_ changeSet: ChangesContentState) -> some View {
        (
            Text(changeSet.summary + "  ")
                .foregroundColor(OpenCodeColors.textBase)
                + OpenCodeDiffStat.text(
                    additions: changeSet.additions,
                    deletions: changeSet.deletions,
                    separator: .wide
                )
        )
        .font(OpenCodeTypography.caption)
        .accessibilityLabel(changeSet.aggregateAccessibilityLabel)
    }

    private func refreshFailure(_ detail: String) -> some View {
        OpenCodeInlineNotice(
            message: "Refresh failed: \(detail)",
            action: OpenCodeInlineNoticeAction(title: "Retry") {
                logAction(ProductAnalytics.Action.changesRetry)
                Task { await viewModel.refresh() }
            }
        )
        .accessibilityElement(children: .combine)
    }

    private func fileRow(
        _ file: FileDiff,
        changeSet: ChangesContentState,
        selectedPath: String?,
        onOpenFile: @escaping (String) -> Void
    ) -> some View {
        OpenCodeListItem(title: .verbatim(changedFileName(file.path)), selected: file.path == selectedPath, accessory: .disclosure, action: {
            logAction(ProductAnalytics.Action.changesOpenFile)
            onOpenFile(file.path)
        },         trailing: {
            OpenCodeDiffStat(
                additions: file.additions,
                deletions: file.deletions,
                style: .label,
                separator: .split
            )
        })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(changeSet.fileAccessibilityLabel(file))
        .accessibilityAddTraits(.isButton)
    }

    private func message(_ title: OpenCodeText, detail: OpenCodeText) -> some View {
        OpenCodeEmptyState(title: title, message: detail)
    }

    private func refreshableMessage(_ title: OpenCodeText, detail: OpenCodeText) -> some View {
        ScrollView {
            message(title, detail: detail)
                .frame(minHeight: 360)
        }
        .refreshable {
            logAction(ProductAnalytics.Action.changesRefresh)
            await viewModel.refresh()
        }
    }
}

private struct ReadableColumnModifier: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.openCodeReadableColumn()
        } else {
            content
        }
    }
}

private struct FileViewerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var state: FileViewerState
    private let detailControl: OpenCodeSplitPaneDetailControl?
    private let usesReadableColumn: Bool

    init(
        file: FileDiff,
        detailControl: OpenCodeSplitPaneDetailControl? = nil,
        usesReadableColumn: Bool = true
    ) {
        _state = State(initialValue: FileViewerState(file: file))
        self.detailControl = detailControl
        self.usesReadableColumn = usesReadableColumn
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: .verbatim(state.file.path),
                leading: OpenCodeSplitPane.detailLeading(detailControl, back: {
                    Self.logAction(ProductAnalytics.Action.fileViewerBack)
                    dismiss()
                }),
                showDivider: true
            )

            Group {
            if state.hasPreview {
                viewerContent
            } else {
                VStack(spacing: 8) {
                    Text("Preview unavailable").font(OpenCodeTypography.heading)
                    Text("A text preview is unavailable for this file.")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(24)
            }
            }
            .modifier(ReadableColumnModifier(enabled: usesReadableColumn))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.changesFileViewer,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }

    /// The viewed file path is never reported.
    private static func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.changesFileViewer,
            actionId: actionId
        )
    }

    private var viewerContent: some View {
        GeometryReader { geometry in
            ScrollViewReader { proxy in
                ZStack(alignment: .bottom) {
                    ScrollView {
                        VStack(spacing: 0) {
                            OpenCodeUnifiedDiff(
                                file: state.file,
                                document: state.document,
                                contextMode: state.contextMode,
                                selectedHunkID: state.selectedHunkID
                            )
                            .frame(width: max(0, geometry.size.width - 32), alignment: .leading)
                            .padding(.horizontal, 16)

                            Color.clear
                                .frame(height: max(84, geometry.size.height - 20))
                        }
                    }
                    .contentMargins(.top, 16, for: .scrollContent)

                    OpenCodeHunkNavigationPill(
                        previousEnabled: state.canSelectPreviousHunk,
                        nextEnabled: state.canSelectNextHunk,
                        contextEnabled: state.canExpandContext,
                        contextExpanded: state.contextMode == .expanded,
                        onPrevious: {
                            Self.logAction(ProductAnalytics.Action.fileViewerHunkPrevious)
                            state.selectPreviousHunk()
                        },
                        onToggleContext: {
                            Self.logAction(ProductAnalytics.Action.fileViewerToggleContext)
                            state.toggleContextMode()
                        },
                        onNext: {
                            Self.logAction(ProductAnalytics.Action.fileViewerHunkNext)
                            state.selectNextHunk()
                        }
                    )
                    .padding(.bottom, 12)
                }
                .onChange(of: state.selectedHunkID) { _, selectedHunkID in
                    guard let selectedHunkID else { return }
                    withAnimation {
                        proxy.scrollTo(UnifiedDiffScrollTarget(rowID: selectedHunkID), anchor: .top)
                    }
                }
            }
        }
    }
}
