import SwiftUI

/// Short Add project sheet: server projects to add in one tap, then Browse folders.
struct AddProjectSuggestions: View {
    let sheet: AddProjectSheetState
    let onSelectSuggestion: (String) -> Void
    let onBrowse: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if sheet.isValidating {
                OpenCodeStatusStack(spinner: "Checking folder")
                    .frame(maxWidth: .infinity)
                    .padding(24)
            } else {
                if sheet.showSuggestions {
                    OpenCodeSectionHeader(title: "On this server")
                    ForEach(sheet.suggestions) { suggestion in
                        OpenCodeListItem(
                            title: suggestion.name,
                            subtitle: suggestion.path,
                            caption: suggestion.activityLabel,
                            startDecorator: .icon(.callSplit),
                            action: { onSelectSuggestion(suggestion.path) }
                        )
                    }
                } else if sheet.listError != nil, sheet.directory == nil {
                    OpenCodeEmptyState(
                        title: "Unable to list this folder",
                        message: sheet.listError ?? AddProjectSheetState.listErrorMessage,
                        fillsAvailableSpace: false,
                        primaryAction: OpenCodeEmptyStateAction(title: "Retry", action: onRetry)
                    )
                    .padding(20)
                } else if sheet.isLoading, sheet.suggestions.isEmpty {
                    OpenCodeStatusStack(spinner: "Loading folders")
                        .frame(maxWidth: .infinity)
                        .padding(24)
                }
                OpenCodeListItem(
                    title: AddProjectSheetState.browseFoldersTitle,
                    subtitle: "Pick any folder on this server",
                    enabled: !sheet.isValidating,
                    startDecorator: .icon(.folder),
                    accessory: .disclosure,
                    action: onBrowse
                )
            }
        }
    }
}

/// Full-screen folder browser. Back returns to the suggestion sheet.
struct AddProjectBrowser: View {
    let sheet: AddProjectSheetState
    let onBack: () -> Void
    let onFieldChange: (String) -> Void
    let onUp: () -> Void
    let onSelectRoot: (String) -> Void
    let onOpenFolder: (String) -> Void
    let onAddFolder: (String) -> Void
    let onAdd: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: browseTitle,
                leading: .back(action: onBack),
                actions: [
                    .label(title: "Add", enabled: sheet.addEnabled, action: onAdd),
                ],
                showDivider: true
            )
            if !sheet.addEnabled, !sheet.isLoading, sheet.listError == nil {
                Text(sheet.addTitle)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
            }
            AddProjectPathChrome(
                sheet: sheet,
                onFieldChange: onFieldChange,
                onUp: onUp,
                onSelectRoot: onSelectRoot,
                onAdd: onAdd
            )
            ScrollView {
                AddProjectFolderList(
                    sheet: sheet,
                    onOpenFolder: onOpenFolder,
                    onAddFolder: onAddFolder,
                    onRetry: onRetry
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(OpenCodeColors.backgroundBase)
    }

    private var browseTitle: String {
        guard let directory = sheet.directory, !directory.isEmpty else { return "Add project" }
        let name = ServerPathPolicy.name(directory)
        return name.isEmpty ? "Add project" : name
    }
}

/// Path field, Up, and root chips for the full-screen browser.
struct AddProjectPathChrome: View {
    let sheet: AddProjectSheetState
    let onFieldChange: (String) -> Void
    let onUp: () -> Void
    let onSelectRoot: (String) -> Void
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 8) {
                OpenCodeTextInput(
                    text: Binding(get: { sheet.fieldText }, set: onFieldChange),
                    hint: "Folder path",
                    kind: .filter,
                    enabled: !sheet.isValidating,
                    errorMessage: sheet.fieldError,
                    onSubmit: onAdd
                )
                OpenCodeButton(
                    variant: .iconSquare(.arrowUpward, accessibilityLabel: "Up one folder"),
                    size: .large,
                    enabled: sheet.upTarget != nil && !sheet.isValidating,
                    contrast: .low,
                    action: onUp
                )
            }
            .padding(.horizontal, 20)

            if !sheet.roots.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(sheet.roots) { root in
                            OpenCodeChoiceBadge(
                                label: root.label,
                                selected: root.selected,
                                enabled: !sheet.isValidating,
                                action: { onSelectRoot(root.path) }
                            )
                        }
                    }
                    .padding(.horizontal, 20)
                }
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 8)
    }
}

/// Folder rows in the browser. The row enters the folder; Add adds it without entering.
struct AddProjectFolderList: View {
    let sheet: AddProjectSheetState
    let onOpenFolder: (String) -> Void
    let onAddFolder: (String) -> Void
    let onRetry: () -> Void

    var body: some View {
        if sheet.isValidating {
            OpenCodeStatusStack(spinner: "Checking folder")
                .frame(maxWidth: .infinity)
                .padding(24)
        } else if sheet.isLoading {
            OpenCodeStatusStack(spinner: "Loading folders")
                .frame(maxWidth: .infinity)
                .padding(24)
        } else if let listError = sheet.listError {
            OpenCodeEmptyState(
                title: "Unable to list this folder",
                message: listError,
                fillsAvailableSpace: false,
                primaryAction: OpenCodeEmptyStateAction(title: "Retry", action: onRetry)
            )
            .padding(20)
        } else if let emptyMessage = sheet.emptyMessage {
            OpenCodeBottomSheetEntry(title: emptyMessage, enabled: false, action: {})
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(sheet.entries) { entry in
                    OpenCodeListItem(
                        title: entry.name,
                        subtitle: entry.isAdded ? "On home" : nil,
                        enabled: !sheet.isValidating,
                        startDecorator: .icon(.folder),
                        accessory: .disclosure,
                        action: { onOpenFolder(entry.path) },
                        trailing: {
                            if !entry.isAdded, !sheet.isValidating {
                                OpenCodeButton(
                                    variant: .iconCircle(.add, accessibilityLabel: "Add \(entry.name)"),
                                    contrast: .low,
                                    action: { onAddFolder(entry.path) }
                                )
                            }
                        }
                    )
                }
                if let truncationHint = sheet.truncationHint {
                    OpenCodeBottomSheetEntry(title: truncationHint, enabled: false, action: {})
                }
            }
        }
    }
}
