import Factory
import SwiftUI

struct ChangesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ChangesViewModel
    @State private var filePickerPresented = false

    init(sessionID: String, worktree: String, messageID: String?, viewModel: ChangesViewModel? = nil) {
        _viewModel = State(initialValue: viewModel ?? ChangesViewModel(
            sessionID: sessionID,
            worktree: worktree,
            messageID: messageID,
            repository: Container.shared.sessionRepository()
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(title: "Changes", leading: .back(action: { dismiss() }), showDivider: true)
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
        switch viewModel.state {
        case .loading:
            ProgressView("Loading changes…")
        case .noTurn:
            message("No completed turn", detail: "Send a prompt and wait for it to complete, then try again.")
        case .noChanges:
            message("No changes", detail: "The latest completed turn did not change any files.")
        case let .content(changeSet):
            changeSetContent(changeSet)
        case let .error(error):
            VStack(spacing: 12) {
                message("Unable to load changes", detail: error)
                OpenCodeButton(variant: .compactText(title: "Retry"), contrast: .low) { Task { await viewModel.retry() } }
            }
        }
    }

    private func changeSetContent(_ changeSet: ChangesContentState) -> some View {
        let selectedFile = changeSet.selectedFile
        return VStack(spacing: 0) {
            Text("\(changeSet.files.count) files  ·  +\(changeSet.additions)  −\(changeSet.deletions)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .accessibilityLabel(changeSet.aggregateAccessibilityLabel)

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
                    variant: .compactText(title: "Previous file"),
                    enabled: changeSet.canSelectPrevious,
                    contrast: .low,
                    action: viewModel.selectPreviousFile
                )
                Spacer()
                OpenCodeButton(
                    variant: .compactText(title: "Next file"),
                    enabled: changeSet.canSelectNext,
                    contrast: .low,
                    action: viewModel.selectNextFile
                )
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            ScrollView {
                if selectedFile.patch?.isEmpty == false {
                    OpenCodeUnifiedDiff(file: selectedFile)
                } else {
                    message(selectedFile.path, detail: "A text preview is unavailable for this file.")
                }
            }
            .padding(16)
        }
    }

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
                            if file.path == changeSet.selectedPath {
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
