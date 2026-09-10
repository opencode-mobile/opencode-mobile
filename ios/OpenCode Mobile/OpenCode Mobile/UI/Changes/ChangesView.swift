import Factory
import SwiftUI

struct ChangesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ChangesViewModel
    @State private var sourceMenuPresented = false
    @State private var selectedPath: String?
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
            capabilitiesProvider: { Container.shared.activeConnectionManager().capabilities }
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(isPresented: $sourceMenuPresented, entries: sourceMenuEntries) {
                OpenCodeTopBar(
                    title: "Changes",
                    subtitle: viewModel.state.selectedSource.displayName,
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
            content
                .openCodeReadableColumn()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.changes,
                presentation: ProductAnalytics.Presentation.route
            )
            await viewModel.load()
            attemptDeepLinkIfNeeded()
        }
        .onChange(of: viewModel.state.phase) { _, _ in
            attemptDeepLinkIfNeeded()
        }
        .onDisappear {
            if selectedPath == nil {
                viewModel.clearFilter()
            }
        }
        .navigationDestination(item: $selectedPath) { path in
            if let file = viewModel.content?.files.first(where: { $0.path == path }) {
                FileViewerView(file: file)
            }
        }
    }

    private func attemptDeepLinkIfNeeded() {
        guard !didAttemptDeepLink, let initialFilePath, !initialFilePath.isEmpty else { return }
        guard case .content(let changeSet) = viewModel.state.phase else { return }
        if changeSet.files.contains(where: { $0.path == initialFilePath }) {
            selectedPath = initialFilePath
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

    @ViewBuilder private var content: some View {
        switch viewModel.state.phase {
        case .loading:
            ProgressView("Loading changes…")
        case .noTurn:
            refreshableMessage("No completed turn", detail: "Send a prompt and wait for it to complete, then try again.")
        case .noChanges:
            refreshableMessage(viewModel.state.selectedSource.emptyTitle, detail: viewModel.state.selectedSource.emptyDetail)
        case let .content(changeSet):
            changedFiles(changeSet)
        case let .error(error):
            VStack(spacing: 12) {
                message("Unable to load changes", detail: error)
                OpenCodeButton(variant: .compactText(title: "Retry"), contrast: .low) {
                    logAction(ProductAnalytics.Action.changesRetry)
                    Task { await viewModel.retry() }
                }
            }
        }
    }

    private var sourceMenuEntries: [OpenCodeOverflowMenuEntry] {
        viewModel.state.availableSources.map { source in
            let selected = source == viewModel.state.selectedSource
            return OpenCodeOverflowMenuEntry(
                id: source.rawValue,
                title: source.displayName,
                icon: selected ? .check : nil,
                accessibilityLabel: selected ? "\(source.displayName), selected" : source.displayName,
                action: {
                    selectedPath = nil
                    Task { await viewModel.selectSource(source) }
                }
            )
        }
    }

    private func changedFiles(_ changeSet: ChangesContentState) -> some View {
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
                            folderHeader(group)
                            OpenCodeListGroup(group.files, id: \.path) { file in
                                fileRow(file, changeSet: changeSet)
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
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
                + Text("+\(changeSet.additions)")
                .foregroundColor(OpenCodeColors.diffAdd)
                + Text("  ")
                + Text("−\(changeSet.deletions)")
                .foregroundColor(OpenCodeColors.diffDelete)
        )
        .font(OpenCodeTypography.caption)
        .accessibilityLabel(changeSet.aggregateAccessibilityLabel)
    }

    private func refreshFailure(_ detail: String) -> some View {
        HStack(spacing: 12) {
            Text("Refresh failed: \(detail)")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
            OpenCodeButton(variant: .compactText(title: "Retry"), contrast: .low) {
                logAction(ProductAnalytics.Action.changesRetry)
                Task { await viewModel.refresh() }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func folderHeader(_ group: ChangedFileGroup) -> some View {
        HStack {
            Text(group.directory)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
            Spacer(minLength: 8)
            Text("\(group.files.count)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(group.directory), \(group.files.count) \(group.files.count == 1 ? "file" : "files")")
    }

    private func fileRow(_ file: FileDiff, changeSet: ChangesContentState) -> some View {
        OpenCodeListItem(title: changedFileName(file.path), action: {
            logAction(ProductAnalytics.Action.changesOpenFile)
            selectedPath = file.path
        }, trailing: {
            HStack(spacing: 12) {
                Text("+\(file.additions)")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.diffAdd)
                Text("−\(file.deletions)")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.diffDelete)
                OpenCodeIconImage(icon: .chevronRight, size: 20)
            }
        })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(changeSet.fileAccessibilityLabel(file))
        .accessibilityAddTraits(.isButton)
    }

    private func message(_ title: String, detail: String) -> some View {
        VStack(spacing: 8) {
            Text(title).font(OpenCodeTypography.heading)
            Text(detail)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }

    private func refreshableMessage(_ title: String, detail: String) -> some View {
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

private struct FileViewerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var state: FileViewerState

    init(file: FileDiff) {
        _state = State(initialValue: FileViewerState(file: file))
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: state.file.path,
                leading: .back(action: {
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
            .openCodeReadableColumn()
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
