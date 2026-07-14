import Factory
import SwiftUI

struct ChangesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ChangesViewModel
    @State private var filePickerPresented = false
    @State private var sourceMenuPresented = false

    init(
        sessionID: String,
        worktree: String,
        messageID: String?,
        initialSource: ChangeSource = .lastTurn,
        viewModel: ChangesViewModel? = nil
    ) {
        _viewModel = State(initialValue: viewModel ?? ChangesViewModel(
            sessionID: sessionID,
            worktree: worktree,
            messageID: messageID,
            initialSource: initialSource,
            repository: Container.shared.sessionRepository()
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $sourceMenuPresented,
                entries: sourceMenuEntries
            ) {
                OpenCodeTopBar(
                    title: "Changes",
                    subtitle: viewModel.state.selectedSource.displayName,
                    leading: .back(action: { dismiss() }),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "Choose change source") {
                            sourceMenuPresented = true
                        },
                    ],
                    showDivider: true
                )
            }
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task { await viewModel.load() }
        .openCodeBottomSheet(
            isPresented: $filePickerPresented,
            title: "Changed files",
            pinnedContent: fileFilter,
            content: filePickerContent
        )
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state.phase {
        case .loading:
            ProgressView("Loading changes…")
        case .noTurn:
            message("No completed turn", detail: "Send a prompt and wait for it to complete, then try again.")
        case .noChanges:
            message(viewModel.state.selectedSource.emptyTitle, detail: viewModel.state.selectedSource.emptyDetail)
        case let .content(changeSet):
            changeSetContent(changeSet)
        case let .error(error):
            VStack(spacing: 12) {
                message("Unable to load changes", detail: error)
                OpenCodeButton(variant: .compactText(title: "Retry"), contrast: .low) { Task { await viewModel.retry() } }
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
                action: { Task { await viewModel.selectSource(source) } }
            )
        }
    }

    private func changeSetContent(_ changeSet: ChangesContentState) -> some View {
        let selectedFile = changeSet.selectedFile
        return VStack(spacing: 0) {
            HStack {
                Text("\(changeSet.files.count) files  ·  +\(changeSet.additions)  −\(changeSet.deletions)")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel(changeSet.aggregateAccessibilityLabel)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            OpenCodeListItem(
                title: selectedFile.path,
                subtitle: changeSet.selectedFileSummary,
                style: .card,
                action: { filePickerPresented = true }
            ) {
                OpenCodeIconImage(icon: .chevronRight)
            }
            .padding(.horizontal, 16)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Choose file. \(changeSet.selectedFileAccessibilityLabel)")
            .accessibilityAddTraits(.isButton)

            HStack {
                OpenCodeButton(
                    variant: .compactText(title: changeSet.contextMode == .collapsed ? "Expand context" : "Collapse context"),
                    contrast: .low,
                    action: viewModel.toggleContextMode
                )
                Spacer()
                OpenCodeButton(
                    variant: .compactText(title: changeSet.selectedFileReviewed ? "Mark unreviewed" : "Mark reviewed"),
                    contrast: .low,
                    action: viewModel.toggleSelectedFileReviewed
                )
            }
            .padding(.horizontal, 16)

            HStack {
                OpenCodeButton(
                    variant: .compactText(title: "Previous hunk"),
                    enabled: changeSet.canSelectPreviousHunk,
                    contrast: .low,
                    action: viewModel.selectPreviousHunk
                )
                Spacer()
                Text(changeSet.selectedHunkPositionLabel)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .accessibilityLabel(changeSet.selectedHunkPositionLabel)
                Spacer()
                OpenCodeButton(
                    variant: .compactText(title: "Next hunk"),
                    enabled: changeSet.canSelectNextHunk,
                    contrast: .low,
                    action: viewModel.selectNextHunk
                )
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            ScrollViewReader { proxy in
                ZStack(alignment: .bottom) {
                    ScrollView {
                        VStack(spacing: 0) {
                            Color.clear
                                .frame(height: 0)
                                .id(Self.diffTopAnchorID)
                            if changeSet.selectedFileHasPreview {
                                OpenCodeUnifiedDiff(
                                    file: selectedFile,
                                    document: changeSet.selectedDocument,
                                    contextMode: changeSet.contextMode,
                                    reviewed: changeSet.selectedFileReviewed,
                                    selectedHunkID: changeSet.selectedHunkID
                                )
                            } else {
                                message(selectedFile.path, detail: "A text preview is unavailable for this file.")
                            }
                        }
                        .padding(.bottom, Self.fileNavigationContentInset)
                    }
                    .padding(16)
                    .refreshable { await viewModel.refresh() }

                    OpenCodeFileNavigationPill(
                        additions: selectedFile.additions,
                        deletions: selectedFile.deletions,
                        previousEnabled: changeSet.canSelectPrevious,
                        nextEnabled: changeSet.canSelectNext,
                        enabled: !viewModel.state.isRefreshing,
                        onPrevious: viewModel.selectPreviousFile,
                        onNext: viewModel.selectNextFile
                    )
                    .padding(.bottom, 12)
                }
                .onChange(of: changeSet.selectedHunkID) { _, selectedHunkID in
                    guard let selectedHunkID else { return }
                    withAnimation { proxy.scrollTo(selectedHunkID, anchor: .top) }
                }
                .onChange(of: changeSet.selectedPath) { _, _ in
                    proxy.scrollTo(Self.diffTopAnchorID, anchor: .top)
                }
            }
        }
    }

    private static let diffTopAnchorID = "changes-diff-top"
    private static let fileNavigationContentInset: CGFloat = 68

    @ViewBuilder private func fileFilter() -> some View {
        if let changeSet = viewModel.content {
            VStack(alignment: .leading, spacing: 8) {
                OpenCodeTextInput(
                    label: "Filter paths",
                    text: Binding(
                        get: { viewModel.content?.filter ?? "" },
                        set: viewModel.updateFilter
                    ),
                    placeholder: "Search changed paths"
                )
                if !changeSet.filter.isEmpty {
                    OpenCodeButton(
                        variant: .compactText(title: "Clear filter"),
                        contrast: .low,
                        action: viewModel.clearFilter
                    )
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
        }
    }

    @ViewBuilder private func filePickerContent() -> some View {
        if let changeSet = viewModel.content {
            if changeSet.filteredFiles.isEmpty {
                message("No matching files", detail: "Try another path or clear the filter.")
            } else {
                VStack(spacing: 0) {
                    ForEach(changeSet.filteredFiles) { file in
                        OpenCodeListItem(
                            title: file.path,
                            subtitle: changeSet.filePickerSummary(file),
                            selected: file.path == changeSet.selectedPath,
                            action: {
                                viewModel.selectFile(file.path)
                                filePickerPresented = false
                            }
                        ) {
                            if changeSet.reviewedPaths.contains(file.path) {
                                Text("Reviewed").font(OpenCodeTypography.caption)
                            } else if file.path == changeSet.selectedPath {
                                Text("Selected").font(OpenCodeTypography.caption)
                            }
                        }
                        .padding(.horizontal, 12)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(changeSet.fileAccessibilityLabel(file))
                        .accessibilityAddTraits(.isButton)
                    }
                }
            }
        }
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
}
