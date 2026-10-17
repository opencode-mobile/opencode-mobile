import SwiftUI

/// Short Add project sheet. Browse folders is first. The suggestion block is always
/// five rows tall, so the card opens at its final height and does not resize when the
/// server list arrives or comes back short.
struct AddProjectSuggestions: View {
    let sheet: AddProjectSheetState
    let onSelectSuggestion: (String) -> Void
    let onBrowse: () -> Void
    let onRetry: () -> Void

    private var isListError: Bool {
        sheet.listError != nil && sheet.directory == nil && !sheet.isValidating
    }

    private var isLoadingSuggestions: Bool {
        !sheet.isValidating && !isListError && !sheet.suggestionsResolved && !sheet.showSuggestions
    }

    private var showsEmptySuggestions: Bool {
        !sheet.isValidating && !isListError && sheet.suggestionsResolved && !sheet.showSuggestions
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeListItem(
                title: AddProjectSheetState.browseFoldersTitle,
                subtitle: "Pick any folder on this server",
                enabled: !sheet.isValidating,
                startDecorator: .icon(.folder),
                accessory: .disclosure,
                action: onBrowse
            )
            if let fieldError = sheet.fieldError {
                Text(fieldError)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 12)
            }
            OpenCodeSectionHeader(title: "On this server")
                .opacity(isListError ? 0 : 1)
                .accessibilityHidden(isListError)
            suggestionFrame
        }
    }

    private var suggestionFrame: some View {
        let frame = ZStack(alignment: .top) {
            VStack(spacing: 0) {
                ForEach(0..<ServerProjectSuggestions.defaultLimit, id: \.self) { index in
                    suggestionSlot(index)
                }
            }
            if sheet.isValidating {
                OpenCodeStatusStack(spinner: "Checking folder")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if isListError {
                OpenCodeEmptyState(
                    title: "Unable to list this folder",
                    message: sheet.listError ?? AddProjectSheetState.listErrorMessage,
                    fillsAvailableSpace: false,
                    primaryAction: OpenCodeEmptyStateAction(title: "Retry", action: onRetry)
                )
                .padding(20)
            } else if showsEmptySuggestions {
                Text(AddProjectSheetState.noOtherProjectsMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
            }
        }
        return Group {
            if isLoadingSuggestions {
                frame
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Loading projects")
            } else {
                frame
            }
        }
    }

    /// A hidden suggestion row keeps every slot the height of a real row, loaded or not.
    private var suggestionRuler: some View {
        OpenCodeListItem(
            title: "Project",
            subtitle: "/Users/placeholder",
            caption: "1d ago",
            enabled: false,
            startDecorator: .icon(.callSplit),
            action: {}
        )
        .hidden()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func suggestionSlot(_ index: Int) -> some View {
        ZStack(alignment: .leading) {
            suggestionRuler
            if isLoadingSuggestions {
                AddProjectSuggestionSkeleton()
            } else if !sheet.isValidating, !isListError, sheet.showSuggestions, index < sheet.suggestions.count {
                let suggestion = sheet.suggestions[index]
                OpenCodeListItem(
                    title: suggestion.name,
                    subtitle: suggestion.path,
                    caption: suggestion.activityLabel,
                    startDecorator: .icon(.callSplit),
                    action: { onSelectSuggestion(suggestion.path) }
                )
            }
        }
    }
}

/// Feature-local stand-in for a suggestion row. The hidden list item behind it sets the height.
private struct AddProjectSuggestionSkeleton: View {
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(OpenCodeColors.textWeaker.opacity(0.35))
                .frame(width: 20, height: 20)
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(OpenCodeColors.textWeaker.opacity(0.35))
                    .frame(width: 132, height: 12)
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(OpenCodeColors.textWeaker.opacity(0.35))
                    .frame(width: 196, height: 10)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityHidden(true)
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
