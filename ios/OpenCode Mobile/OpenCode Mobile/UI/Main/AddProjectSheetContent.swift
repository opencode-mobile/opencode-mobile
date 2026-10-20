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
            startDecorator: .icon(.folder),
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
                    startDecorator: .icon(.folder),
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

/// List items carry 16pt of their own padding; this brings their icon to the screen gutter.
private let addProjectRowGutterInset = OpenCodeLayout.screenGutter - 16
/// The top bar pads its back button 12pt from the edge; this brings it to the screen gutter.
private let addProjectTopBarGutterInset = OpenCodeLayout.screenGutter - 12

/// Full-screen folder browser. Back returns to the suggestion sheet. Rows only navigate; the
/// pinned action bar adds the folder it names. Every edge sits on one screen gutter.
struct AddProjectBrowser: View {
    let sheet: AddProjectSheetState
    let onBack: () -> Void
    let onFieldChange: (String) -> Void
    let onSubmitField: () -> Void
    let onUp: () -> Void
    let onSelectRoot: (String) -> Void
    let onOpenFolder: (String) -> Void
    let onRowAppeared: (String) -> Void
    let onRowDisappeared: (String) -> Void
    let onAdd: () -> Void
    let onRetry: () -> Void

    /// The path field has focus, so the software keyboard is up.
    @State private var isEditingPath = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(title: "Add project", leading: .back(action: onBack))
                .padding(.horizontal, addProjectTopBarGutterInset)
                .background(OpenCodeColors.backgroundBase)
            AddProjectPathChrome(
                sheet: sheet,
                isEditing: $isEditingPath,
                onFieldChange: onFieldChange,
                onSubmit: onSubmitField,
                onUp: onUp,
                onSelectRoot: onSelectRoot
            )
            ScrollView {
                AddProjectFolderList(
                    sheet: sheet,
                    onOpenFolder: onOpenFolder,
                    onRowAppeared: onRowAppeared,
                    onRowDisappeared: onRowDisappeared,
                    onRetry: onRetry
                )
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(OpenCodeColors.backgroundBase)
        // The inset rides the keyboard; while typing only the button stays.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            OpenCodeActionBar(
                target: actionBarTarget,
                actionTitle: sheet.addTitle,
                enabled: sheet.addEnabled,
                compact: isEditingPath,
                action: onAdd
            )
        }
    }

    private var actionBarTarget: OpenCodeActionBarTarget? {
        guard let target = sheet.barTarget else { return nil }
        return OpenCodeActionBarTarget(
            icon: .folder,
            title: target.name,
            tag: target.isGitRepository ? AddProjectSheetState.gitTag : nil,
            subtitle: target.path
        )
    }
}

/// Path field, Up, and root chips for the full-screen browser.
struct AddProjectPathChrome: View {
    let sheet: AddProjectSheetState
    @Binding var isEditing: Bool
    let onFieldChange: (String) -> Void
    let onSubmit: () -> Void
    let onUp: () -> Void
    let onSelectRoot: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 8) {
                OpenCodeTextInput(
                    text: Binding(get: { sheet.fieldText }, set: onFieldChange),
                    hint: "Folder path",
                    kind: .filter,
                    enabled: !sheet.isValidating,
                    errorMessage: sheet.fieldError,
                    submitAction: .go,
                    isFocused: $isEditing,
                    onSubmit: onSubmit
                )
                OpenCodeButton(
                    variant: .iconSquare(.arrowUpward, accessibilityLabel: "Up one folder"),
                    size: .large,
                    enabled: sheet.upTarget != nil && !sheet.isValidating,
                    contrast: .low,
                    action: onUp
                )
            }
            .padding(.horizontal, OpenCodeLayout.screenGutter)

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
                    .padding(.horizontal, OpenCodeLayout.screenGutter)
                }
            }
        }
        .padding(.top, 4)
        .padding(.bottom, 8)
    }
}

/// Folder rows in the browser. A row enters its folder; each row reports when it is on screen
/// so its folder count and git tag load lazily.
struct AddProjectFolderList: View {
    let sheet: AddProjectSheetState
    let onOpenFolder: (String) -> Void
    let onRowAppeared: (String) -> Void
    let onRowDisappeared: (String) -> Void
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
            .padding(OpenCodeLayout.screenGutter)
        } else if let emptyMessage = sheet.emptyMessage {
            Text(emptyMessage)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, OpenCodeLayout.screenGutter)
                .padding(.vertical, 16)
        } else {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(sheet.entries) { entry in
                    OpenCodeListItem(
                        title: entry.name,
                        subtitle: entry.subtitle,
                        titleTag: entry.isGitRepository ? AddProjectSheetState.gitTag : nil,
                        enabled: !sheet.isValidating,
                        startDecorator: .icon(.folder),
                        action: { onOpenFolder(entry.path) }
                    )
                    .padding(.horizontal, addProjectRowGutterInset)
                    .onAppear { onRowAppeared(entry.path) }
                    .onDisappear { onRowDisappeared(entry.path) }
                }
                if let truncationHint = sheet.truncationHint {
                    Text(truncationHint)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, OpenCodeLayout.screenGutter)
                        .padding(.vertical, 12)
                }
            }
        }
    }
}
