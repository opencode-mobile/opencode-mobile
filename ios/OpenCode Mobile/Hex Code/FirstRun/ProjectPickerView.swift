import SwiftUI

/// First run, step 3: Browse folders, then up to five recent repositories on the computer.
/// After OpenCode Mobile's Add project sheet.
struct ProjectPickerView: View {
    let picker: ProjectPickerModel
    var title = LocalizedStringResource("project.title", defaultValue: "Pick a project")
    var note = LocalizedStringResource("project.note", defaultValue: "A new session starts there each day.")
    let serverLabel: String
    let onBack: () -> Void
    let onPick: (ChosenProject) -> Void
    /// Pushes Browse folders as its own page. Without it the browser replaces the list in place.
    var onBrowse: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if picker.isBrowsing, onBrowse == nil {
            FolderBrowserView(picker: picker, onPick: onPick)
                .transition(HexMotion.transition(.move(edge: .trailing), reduced: reduceMotion))
        } else {
            list
        }
    }

    private var sheet: FolderBrowserState { picker.sheet }

    private var list: some View {
        VStack(spacing: 0) {
            HexTopBar(title, subtitle: serverLabel, onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HexPickerRow(
                        icon: .folder,
                        title: String(localized: "project.browse", defaultValue: "Browse folders"),
                        subtitle: String(localized: "project.browse.subtitle", defaultValue: "Pick any folder on this computer"),
                        trailing: .chevron,
                        action: {
                            if let onBrowse { onBrowse() } else { Task { await picker.browse() } }
                        }
                    )
                    .disabled(sheet.homePath.isEmpty)
                    HexSectionLabel(LocalizedStringResource("project.on_this_computer", defaultValue: "On this computer"))
                    suggestions
                }
                .padding(.top, 4)
            }
            Text(note)
                .hexFont(.caption)
                .foregroundStyle(HexColor.text3)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, HexSpacing.screen)
                .padding(.bottom, 8)
        }
        .hexScreenBackground()
    }

    @ViewBuilder
    private var suggestions: some View {
        if let error = sheet.listError, sheet.homePath.isEmpty {
            PickerNotice(text: error, onRetry: { Task { await picker.retry() } })
        } else if !sheet.suggestionsResolved {
            HexStatusLine(.waiting, LocalizedStringResource("project.loading", defaultValue: "Loading projects"))
                .padding(.horizontal, HexSpacing.screen)
                .padding(.vertical, 14)
        } else if sheet.suggestions.isEmpty {
            PickerNotice(text: String(localized: "project.none", defaultValue: "No repositories yet. Browse folders to pick one."))
        } else {
            ForEach(sheet.suggestions) { suggestion in
                HexPickerRow(
                    icon: .folder,
                    title: suggestion.name,
                    subtitle: suggestion.path,
                    trailing: suggestion.activityLabel.map(HexRowTrailing.caption) ?? .none,
                    action: {
                        if let chosen = picker.suggestion(at: suggestion.path) { onPick(chosen) }
                    }
                )
            }
        }
    }
}

/// Browse folders: path field with Up, root chips, folder rows with a `git` tag, and a
/// pinned bar naming the folder with "Use this folder". Rows only navigate.
struct FolderBrowserView: View {
    let picker: ProjectPickerModel
    let onPick: (ChosenProject) -> Void
    /// Back from the browser; by default it returns to the project list in place.
    var onBack: (() -> Void)?

    @FocusState private var editingPath: Bool

    private var sheet: FolderBrowserState { picker.sheet }

    var body: some View {
        VStack(spacing: 0) {
            HexTopBar(
                LocalizedStringResource("browse.title", defaultValue: "Pick a folder"),
                onBack: { if let onBack { onBack() } else { picker.closeBrowser() } }
            )
            pathField
            roots
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) { rows }
                    .padding(.top, 4)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .hexScreenBackground()
        .safeAreaInset(edge: .bottom, spacing: 0) { bar }
    }

    private var pathField: some View {
        HStack(spacing: 8) {
            HexTextField(
                LocalizedStringResource("add_project.folder_path", defaultValue: "Folder path"),
                text: Binding(get: { sheet.fieldText }, set: { picker.updateField($0) }),
                monospaced: true,
                submitLabel: .go,
                focus: $editingPath,
                onSubmit: { Task { await picker.submitField() } }
            )
            HexButton(icon: .send, size: .field, enabled: sheet.upTarget != nil) {
                Task { await picker.up() }
            }
            .accessibilityLabel(Text(LocalizedStringResource("add_project.up_one_folder", defaultValue: "Up one folder")))
        }
        .padding(.horizontal, HexSpacing.screen)
        .padding(.top, 4)
    }

    @ViewBuilder
    private var roots: some View {
        if !sheet.roots.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(sheet.roots) { root in
                        RootChip(label: root.label, selected: root.selected) {
                            Task { await picker.selectRoot(root.path) }
                        }
                    }
                }
                .padding(.horizontal, HexSpacing.screen)
            }
            .padding(.top, 12)
            .padding(.bottom, 6)
        }
    }

    @ViewBuilder
    private var rows: some View {
        if sheet.isLoading {
            HexStatusLine(.waiting, LocalizedStringResource("browse.loading", defaultValue: "Loading folders"))
                .padding(.horizontal, HexSpacing.screen)
                .padding(.vertical, 14)
        } else if let error = sheet.listError {
            PickerNotice(text: error, onRetry: { Task { await picker.retry() } })
        } else if let empty = sheet.emptyMessage {
            PickerNotice(text: empty)
        } else {
            ForEach(sheet.entries) { entry in
                HexPickerRow(
                    icon: .folder,
                    title: entry.name,
                    badge: entry.isGitRepository ? Text(verbatim: FolderBrowserState.gitTag) : nil,
                    monoBadge: true,
                    subtitle: entry.subtitle,
                    reservesSubtitle: true,
                    trailing: .chevron,
                    action: { Task { await picker.openFolder(entry.path) } }
                )
                .onAppear { picker.rowAppeared(entry.path) }
                .onDisappear { picker.rowDisappeared(entry.path) }
            }
            if let hint = sheet.truncationHint {
                Text(verbatim: hint)
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text2)
                    .padding(.horizontal, HexSpacing.screen)
                    .padding(.vertical, 12)
            }
        }
    }

    private var bar: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let target = sheet.barTarget, !editingPath {
                HStack(spacing: 12) {
                    HexIconView(.folder, size: HexIconSize.large)
                        .foregroundStyle(HexColor.text1)
                        .frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 7) {
                            Text(verbatim: target.name)
                                .hexFont(.bodyStrong)
                                .foregroundStyle(HexColor.text1)
                                .lineLimit(1)
                            if target.isGitRepository {
                                HexBadge(text: Text(verbatim: FolderBrowserState.gitTag), mono: true)
                            }
                        }
                        Text(verbatim: target.path)
                            .hexFont(HexTextStyle.footnote.monospaced())
                            .foregroundStyle(HexColor.text2)
                            .lineLimit(1)
                            .truncationMode(.head)
                    }
                }
                .padding(.top, 4)
                .padding(.bottom, 12)
            }
            HexPrimaryButton(
                verbatim: sheet.useTitle,
                enabled: picker.folderToUse != nil,
                action: { if let folder = picker.folderToUse { onPick(folder) } }
            )
        }
        .padding(.horizontal, HexSpacing.screen)
        .padding(.top, 10)
        .padding(.bottom, 4)
        .background(HexColor.background)
        .overlay(alignment: .top) { HexColor.line.frame(height: 1) }
    }
}

/// A root the path field can jump to: Home, `/`, a volume or a drive.
private struct RootChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(verbatim: label)
                .hexFont(.callout)
                .foregroundStyle(selected ? HexColor.onSolid : HexColor.text2)
                .lineLimit(1)
                .padding(.horizontal, 13)
                .frame(height: 32)
                .background(selected ? HexColor.solid : .clear, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? .clear : HexColor.line2, lineWidth: 1))
        }
        .buttonStyle(HexPressStyle(scale: 0.96))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A line of text in place of the list (empty, or a failure with Retry).
struct PickerNotice: View {
    let text: String
    var onRetry: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: text)
                .hexFont(.row)
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
            if let onRetry {
                HexButton(LocalizedStringResource("common.retry", defaultValue: "Retry"), action: onRetry)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, HexSpacing.screen)
        .padding(.vertical, 14)
    }
}
