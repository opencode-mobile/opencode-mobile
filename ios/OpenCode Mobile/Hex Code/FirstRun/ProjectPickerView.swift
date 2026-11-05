import OpenCodeKit
import SwiftUI

/// First run, step 3: Browse folders, then up to five recent repositories on the computer.
/// Taken from OpenCode Mobile's Add project sheet, on its shared Add project state.
struct ProjectPickerView: View {
    let picker: ProjectPickerModel
    var title = LocalizedStringResource("project.title", defaultValue: "Pick a project")
    var note = LocalizedStringResource("project.note", defaultValue: "A new session starts there each day.")
    let serverLabel: String
    let onBack: () -> Void
    let onPick: (ChosenProject) -> Void
    /// Pushes Browse folders as its own page. Without it the browser replaces the list in place.
    var onBrowse: (() -> Void)?

    var body: some View {
        if picker.isBrowsing, onBrowse == nil {
            FolderBrowserView(picker: picker, onPick: onPick)
                .transition(.move(edge: .trailing))
        } else {
            list
        }
    }

    private var sheet: AddProjectSheetState { picker.sheet }

    private var list: some View {
        VStack(spacing: 0) {
            HexTopBar(
                title: title,
                subtitle: serverLabel,
                onBack: onBack
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HexRow(
                        icon: .folder,
                        title: String(localized: "project.browse", defaultValue: "Browse folders"),
                        subtitle: String(localized: "project.browse.subtitle", defaultValue: "Pick any folder on this computer"),
                        trailing: .chevron,
                        action: {
                            if let onBrowse { onBrowse() } else { Task { await picker.browse() } }
                        }
                    )
                    .disabled(sheet.homePath.isEmpty)
                    HexSectionLabel(title: OpenCodeText(LocalizedStringResource("project.on_this_computer", defaultValue: "On this computer")))
                    suggestions
                }
            }
            Text(note)
                .font(.system(size: 12.5))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)
        }
        .background(OpenCodeColors.backgroundBase)
    }

    @ViewBuilder
    private var suggestions: some View {
        if let error = sheet.listError, sheet.homePath.isEmpty {
            retryNotice(error)
        } else if !sheet.suggestionsResolved {
            OpenCodeStatusStack(spinner: OpenCodeText(LocalizedStringResource("project.loading", defaultValue: "Loading projects")))
                .frame(maxWidth: .infinity)
                .padding(24)
        } else if sheet.suggestions.isEmpty {
            Text(LocalizedStringResource("project.none", defaultValue: "No repositories yet. Browse folders to pick one."))
                .font(.system(size: 14.5))
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.horizontal, HexLayout.edge)
                .padding(.vertical, 12)
        } else {
            ForEach(sheet.suggestions) { suggestion in
                HexRow(
                    icon: .folder,
                    title: suggestion.name,
                    subtitle: suggestion.path,
                    trailing: suggestion.activityLabel.map(HexRow.Trailing.caption) ?? .none,
                    action: {
                        if let chosen = picker.suggestion(at: suggestion.path) { onPick(chosen) }
                    }
                )
            }
        }
    }

    private func retryNotice(_ message: OpenCodeText) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(openCode: message)
                .font(.system(size: 14.5))
                .foregroundStyle(OpenCodeColors.textWeak)
            Button {
                Task { await picker.retry() }
            } label: {
                Text(LocalizedStringResource("common.retry", defaultValue: "Retry"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .padding(.horizontal, HexLayout.edge)
        .padding(.vertical, 12)
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

    private var sheet: AddProjectSheetState { picker.sheet }

    var body: some View {
        VStack(spacing: 0) {
            HexTopBar(
                title: LocalizedStringResource("browse.title", defaultValue: "Pick a folder"),
                onBack: { if let onBack { onBack() } else { picker.closeBrowser() } }
            )
            pathField
            roots
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) { rows }
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .background(OpenCodeColors.backgroundBase)
        .safeAreaInset(edge: .bottom, spacing: 0) { bar }
    }

    private var pathField: some View {
        HStack(spacing: 8) {
            TextField(
                String(localized: "add_project.folder_path", defaultValue: "Folder path"),
                text: Binding(get: { sheet.fieldText }, set: { picker.updateField($0) })
            )
            .font(.system(size: 14, design: .monospaced))
            .foregroundStyle(OpenCodeColors.textStrong)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.go)
            .focused($editingPath)
            .onSubmit { Task { await picker.submitField() } }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(OpenCodeColors.borderWeakBase, lineWidth: 1))
            Button {
                Task { await picker.up() }
            } label: {
                OpenCodeIconImage(icon: .arrowUpward, size: 17)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .frame(width: 44, height: 44)
                    .background(OpenCodeColors.surfaceBaseHover, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(HexPressStyle())
            .disabled(sheet.upTarget == nil)
            .opacity(sheet.upTarget == nil ? 0.3 : 1)
            .accessibilityLabel(Text("add_project.up_one_folder", comment: "Up one folder"))
        }
        .padding(.horizontal, HexLayout.edge)
        .padding(.top, 4)
    }

    @ViewBuilder
    private var roots: some View {
        if !sheet.roots.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(sheet.roots) { root in
                        Button {
                            Task { await picker.selectRoot(root.path) }
                        } label: {
                            Text(openCode: root.label)
                                .font(.system(size: 13.5, weight: .medium))
                                .foregroundStyle(root.selected ? OpenCodeColors.iconInvertBase : OpenCodeColors.textWeak)
                                .padding(.horizontal, 13)
                                .frame(height: 32)
                                .background(root.selected ? OpenCodeColors.buttonPrimaryBase : .clear, in: Capsule())
                                .overlay(Capsule().stroke(root.selected ? .clear : OpenCodeColors.borderWeakBase, lineWidth: 1))
                        }
                        .buttonStyle(HexPressStyle())
                    }
                }
                .padding(.horizontal, HexLayout.edge)
            }
            .padding(.top, 12)
            .padding(.bottom, 6)
        }
    }

    @ViewBuilder
    private var rows: some View {
        if sheet.isLoading {
            OpenCodeStatusStack(spinner: OpenCodeText(LocalizedStringResource("browse.loading", defaultValue: "Loading folders")))
                .frame(maxWidth: .infinity)
                .padding(24)
        } else if let error = sheet.listError {
            VStack(alignment: .leading, spacing: 10) {
                Text(openCode: error)
                    .font(.system(size: 14.5))
                    .foregroundStyle(OpenCodeColors.textWeak)
                Button {
                    Task { await picker.retry() }
                } label: {
                    Text(LocalizedStringResource("common.retry", defaultValue: "Retry"))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(OpenCodeColors.textStrong)
                }
            }
            .padding(.horizontal, HexLayout.edge)
            .padding(.vertical, 14)
        } else if let empty = sheet.emptyMessage {
            Text(openCode: empty)
                .font(.system(size: 14.5))
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.horizontal, HexLayout.edge)
                .padding(.vertical, 14)
        } else {
            ForEach(sheet.entries) { entry in
                HexRow(
                    icon: .folder,
                    title: entry.name,
                    tag: entry.isGitRepository ? AddProjectSheetState.gitTag : nil,
                    subtitle: entry.subtitle?.resolved,
                    reservesSubtitle: true,
                    trailing: .chevron,
                    action: { Task { await picker.openFolder(entry.path) } }
                )
                .onAppear { picker.rowAppeared(entry.path) }
                .onDisappear { picker.rowDisappeared(entry.path) }
            }
            if let hint = sheet.truncationHint {
                Text(openCode: hint)
                    .font(.system(size: 13))
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.horizontal, HexLayout.edge)
                    .padding(.vertical, 12)
            }
        }
    }

    private var bar: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let target = sheet.barTarget, !editingPath {
                HStack(spacing: 12) {
                    OpenCodeIconImage(icon: .folder, size: 20)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 7) {
                            Text(verbatim: target.name)
                                .font(.system(size: 15.5, weight: .semibold))
                                .foregroundStyle(OpenCodeColors.textStrong)
                            if target.isGitRepository {
                                OpenCodeTag(AddProjectSheetState.gitTag, size: .small)
                            }
                        }
                        Text(verbatim: target.path)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(1)
                            .truncationMode(.head)
                    }
                }
                .padding(.bottom, 12)
            }
            HexPrimaryButton(
                text: sheet.addTitle,
                enabled: picker.folderToUse != nil,
                action: { if let folder = picker.folderToUse { onPick(folder) } }
            )
        }
        .padding(.horizontal, HexLayout.edge)
        .padding(.top, 10)
        .padding(.bottom, 4)
        .background(OpenCodeColors.backgroundBase)
        .overlay(alignment: .top) { Divider().overlay(OpenCodeColors.borderWeakBase) }
    }
}
