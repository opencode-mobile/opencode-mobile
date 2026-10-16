import SwiftUI

/// Pinned top of the Add project sheet: path field with Up, then the root chips.
struct AddProjectSheetHeader: View {
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
        .padding(.bottom, 8)
    }
}

/// Pinned bottom of the Add project sheet: the target path, then the Add button.
/// The sheet keeps it above the keyboard and supplies its padding.
struct AddProjectSheetFooter: View {
    let sheet: AddProjectSheetState
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let target = sheet.addTarget {
                Text(target)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OpenCodeButton(
                variant: .fullWidthText(title: sheet.addTitle),
                enabled: sheet.addEnabled,
                action: onAdd
            )
        }
    }
}

/// Scrolling part of the Add project sheet: On this server suggestions, then the folder rows.
struct AddProjectSheetRows: View {
    let sheet: AddProjectSheetState
    let onOpenFolder: (String) -> Void
    let onSelectSuggestion: (String) -> Void
    let onRetry: () -> Void

    var body: some View {
        if sheet.isValidating {
            OpenCodeStatusStack(spinner: "Checking folder")
                .frame(maxWidth: .infinity)
                .padding(24)
        } else {
            rows
        }
    }

    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            if sheet.showSuggestions {
                OpenCodeSectionHeader(title: "On this server")
                ForEach(sheet.suggestions) { suggestion in
                    OpenCodeListItem(
                        title: suggestion.name,
                        subtitle: suggestion.path,
                        caption: suggestion.activityLabel,
                        enabled: !sheet.isValidating,
                        startDecorator: .icon(.callSplit),
                        action: { onSelectSuggestion(suggestion.path) }
                    )
                }
                if let folderSectionTitle = sheet.folderSectionTitle {
                    OpenCodeSectionHeader(title: folderSectionTitle)
                }
            }
            folders
        }
    }

    @ViewBuilder
    private var folders: some View {
        if sheet.isLoading {
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
            ForEach(sheet.entries) { entry in
                OpenCodeListItem(
                    title: entry.name,
                    subtitle: entry.isAdded ? "On home" : nil,
                    enabled: !sheet.isValidating,
                    startDecorator: .icon(.folder),
                    action: { onOpenFolder(entry.path) }
                )
            }
            if let truncationHint = sheet.truncationHint {
                OpenCodeBottomSheetEntry(title: truncationHint, enabled: false, action: {})
            }
        }
    }
}
