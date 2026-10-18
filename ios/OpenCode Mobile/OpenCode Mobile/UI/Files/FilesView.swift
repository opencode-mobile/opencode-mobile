import FactoryKit
import SwiftUI

/// Navigation value for the Files screen pushed from Session Chat.
struct FilesRoute: Hashable, Identifiable {
    var id: String { worktree }
    let worktree: String
}

/// Read-only File Browser for one project worktree. Phones push a File Viewer per file;
/// a wide window (iPad, iPhone Duo open) keeps the tree beside a tabbed viewer.
struct FilesView: View {
    @Environment(\.dismiss) private var dismiss
    var splitGate = OpenCodeSplitLayoutGate()
    @State private var viewModel: FilesViewModel
    @State private var pushedPath: String?
    @State private var treeMenuPresented = false
    /// Query Analytics fires once per screen; the query text is never sent.
    @State private var loggedQueryEdit = false

    init(worktree: String, viewModel: FilesViewModel? = nil) {
        _viewModel = State(initialValue: viewModel ?? Container.makeFilesViewModel(worktree: worktree))
    }

    private var isSplitLayout: Bool {
        splitGate.usesSplitLayout
    }

    var body: some View {
        Group {
            if isSplitLayout {
                contentSplit
            } else {
                stackedFiles
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .overlay {
            OpenCodeSnackbarHost(message: viewModel.state.snackbar, onDismissed: viewModel.consumeSnackbar)
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.files,
                presentation: ProductAnalytics.Presentation.route
            )
            viewModel.start()
        }
        .onChange(of: isSplitLayout) { _, split in
            if split {
                pushedPath = nil
            } else if let active = viewModel.state.activePath {
                pushedPath = active
            }
        }
        .onChange(of: pushedPath) { old, new in
            // Phones show one file at a time: popping the viewer drops its cached content.
            if let old, new == nil, !isSplitLayout {
                viewModel.closeTab(old)
            }
        }
    }

    private var stackedFiles: some View {
        treeColumn(selectedPath: nil, constrainWidth: true) { path in
            viewModel.openFile(path)
            pushedPath = path
        }
        .navigationDestination(item: $pushedPath) { path in
            FileViewerScreen(viewModel: viewModel, relativePath: path)
        }
    }

    private var contentSplit: some View {
        OpenCodeSplitPane(screen: .files, onCollapsedChange: { _ in
            logAction(ProductAnalytics.Action.splitPaneToggleSidebar)
        }) {
            treeColumn(selectedPath: viewModel.state.activePath, constrainWidth: false) { path in
                viewModel.openFile(path)
            }
        } trailing: { control in
            FilesTabbedViewer(viewModel: viewModel, detailControl: control)
        }
    }

    private func treeColumn(
        selectedPath: String?,
        constrainWidth: Bool,
        onOpenFile: @escaping (String) -> Void
    ) -> some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(isPresented: $treeMenuPresented, entries: treeMenuEntries) {
                OpenCodeTopBar(
                    title: "Files",
                    subtitle: viewModel.state.projectName,
                    leading: .back(action: exitFiles),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options") {
                            treeMenuPresented = true
                        },
                    ],
                    showDivider: true
                )
            }
            VStack(spacing: 0) {
                OpenCodeTextInput(
                    text: Binding(
                        get: { viewModel.state.query },
                        set: { value in
                            logQueryEditOnce()
                            viewModel.setQuery(value)
                        }
                    ),
                    hint: "Find file",
                    accessibilityLabel: "Find file",
                    kind: .filter
                )
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

                Group {
                    if viewModel.state.isFinding {
                        findContent(selectedPath: selectedPath, onOpenFile: onOpenFile)
                    } else {
                        treeContent(selectedPath: selectedPath, onOpenFile: onOpenFile)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .modifier(FilesReadableColumnModifier(enabled: constrainWidth))
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var treeMenuEntries: [OpenCodeOverflowMenuEntry] {
        let showHidden = viewModel.state.showHidden
        return [
            OpenCodeOverflowMenuEntry(
                id: "files-show-hidden",
                title: "Show hidden files",
                icon: showHidden ? .check : nil,
                accessibilityLabel: showHidden ? "Show hidden files, on" : "Show hidden files, off",
                action: {
                    logAction(ProductAnalytics.Action.filesToggleHidden)
                    treeMenuPresented = false
                    viewModel.toggleHidden()
                }
            ),
            OpenCodeOverflowMenuEntry(
                id: "files-refresh",
                title: "Refresh",
                icon: nil,
                action: {
                    logAction(ProductAnalytics.Action.filesRefresh)
                    treeMenuPresented = false
                    viewModel.refresh()
                }
            ),
        ]
    }

    @ViewBuilder
    private func treeContent(selectedPath: String?, onOpenFile: @escaping (String) -> Void) -> some View {
        switch viewModel.state.rootPhase {
        case .loading:
            OpenCodeStatusStack(title: "Loading files", subtitle: "Fetching the folder…")
        case .failed:
            OpenCodeEmptyState(
                title: "Couldn't load folder",
                message: "Check the connection and try again.",
                primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                    logAction(ProductAnalytics.Action.filesRetry)
                    viewModel.retryRoot()
                }
            )
        case .loaded:
            if viewModel.state.treeRows.isEmpty {
                OpenCodeEmptyState(
                    title: "Empty folder",
                    message: "This project has no files to show.",
                    icon: .folder
                )
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(viewModel.state.treeRows) { row in
                            treeRow(row, selectedPath: selectedPath, onOpenFile: onOpenFile)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 16)
                }
                .accessibilityIdentifier("filesTree")
                .refreshable {
                    logAction(ProductAnalytics.Action.filesRefresh)
                    viewModel.refresh()
                }
            }
        }
    }

    @ViewBuilder
    private func treeRow(
        _ row: FilesTreeRow,
        selectedPath: String?,
        onOpenFile: @escaping (String) -> Void
    ) -> some View {
        switch row {
        case let .entry(entry, depth, isExpanded, isLoading):
            OpenCodeTreeRow(
                title: entry.name,
                kind: entry.isDirectory ? .folder(isExpanded: isExpanded) : .file,
                depth: depth,
                state: isLoading ? .loading : (entry.relativePath == selectedPath ? .selected : .normal),
                isDimmed: entry.isIgnored,
                tag: entry.isIgnored ? "ignored" : nil
            ) {
                if entry.isDirectory {
                    logAction(ProductAnalytics.Action.filesToggleFolder)
                    viewModel.toggleDirectory(entry.relativePath)
                } else {
                    logAction(ProductAnalytics.Action.filesOpenFile)
                    onOpenFile(entry.relativePath)
                }
            }
        case let .emptyFolder(_, depth):
            Text("Empty folder")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.leading, OpenCodeTreeRow.contentInset(depth: depth))
                .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
        case let .folderError(parentPath, depth):
            OpenCodeInlineNotice(
                message: "Couldn't load folder",
                tone: .error,
                action: OpenCodeInlineNoticeAction(title: "Retry") {
                    logAction(ProductAnalytics.Action.filesRetry)
                    viewModel.retryFolder(parentPath)
                }
            )
            .padding(.leading, OpenCodeTreeRow.contentInset(depth: depth))
            .padding(.trailing, 12)
            .frame(minHeight: 36)
        }
    }

    @ViewBuilder
    private func findContent(selectedPath: String?, onOpenFile: @escaping (String) -> Void) -> some View {
        switch viewModel.state.find {
        case .idle, .searching:
            OpenCodeStatusStack(spinner: "Finding files")
        case .failed:
            OpenCodeEmptyState(
                title: "Couldn't search files",
                message: "Check the connection and try again.",
                primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                    logAction(ProductAnalytics.Action.filesRetry)
                    viewModel.retryFind()
                }
            )
        case let .results(results):
            if results.isEmpty {
                OpenCodeEmptyState(
                    title: "No files match “\(viewModel.state.query.trimmingCharacters(in: .whitespacesAndNewlines))”",
                    message: "Server search skips ignored folders.",
                    icon: .search
                )
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(results) { entry in
                            OpenCodeTreeRow(
                                title: entry.name,
                                subtitle: entry.parentPath.isEmpty ? "/" : entry.parentPath,
                                kind: .file,
                                state: entry.relativePath == selectedPath ? .selected : .normal
                            ) {
                                logAction(ProductAnalytics.Action.filesOpenFile)
                                onOpenFile(entry.relativePath)
                            }
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 16)
                }
                .scrollDismissesKeyboard(.immediately)
                .accessibilityIdentifier("filesFindResults")
            }
        }
    }

    private func exitFiles() {
        logAction(ProductAnalytics.Action.filesBack)
        dismiss()
    }

    private func logQueryEditOnce() {
        guard !loggedQueryEdit else { return }
        loggedQueryEdit = true
        logAction(ProductAnalytics.Action.filesUpdateQuery)
    }

    /// File paths and query text stay out of Analytics.
    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(screenId: ProductAnalytics.Screen.files, actionId: actionId)
    }
}

/// Trailing iPad pane: open-file tabs on top, then the active file's header and body.
private struct FilesTabbedViewer: View {
    let viewModel: FilesViewModel
    let detailControl: OpenCodeSplitPaneDetailControl
    @State private var menuPresented = false

    var body: some View {
        let active = viewModel.state.activeViewer
        VStack(spacing: 0) {
            OpenCodeFileTabs(
                tabs: viewModel.state.tabs.map { OpenCodeFileTab(id: $0.relativePath, title: $0.name) },
                selectedTabId: viewModel.state.activePath,
                onSelect: { path in
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.files,
                        actionId: ProductAnalytics.Action.filesSelectTab
                    )
                    viewModel.selectTab(path)
                },
                onClose: { path in
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.files,
                        actionId: ProductAnalytics.Action.filesCloseTab
                    )
                    viewModel.closeTab(path)
                }
            )
            OpenCodeTopBarOverflowHost(
                isPresented: $menuPresented,
                entries: active.map { FileViewerMenu.entries(viewModel: viewModel, relativePath: $0.relativePath, dismissMenu: { menuPresented = false }) } ?? []
            ) {
                OpenCodeTopBar(
                    title: active?.name ?? "Files",
                    subtitle: active?.relativePath,
                    leading: OpenCodeSplitPane.detailLeading(detailControl, back: {}),
                    actions: active == nil ? [] : [
                        .icon(.moreVert, accessibilityLabel: "More options") { menuPresented = true },
                    ],
                    showDivider: true
                )
            }
            if let active {
                FileViewerContent(viewModel: viewModel, relativePath: active.relativePath)
                    .id(active.relativePath)
            } else {
                OpenCodeEmptyState(
                    title: "Open a file from the tree",
                    message: "Opened files stay as tabs",
                    icon: .description
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }
}

private struct FilesReadableColumnModifier: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.openCodeReadableColumn()
        } else {
            content
        }
    }
}
