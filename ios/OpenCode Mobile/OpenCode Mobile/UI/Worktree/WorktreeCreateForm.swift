import SwiftUI

/// The New worktree form, or its v2 base list, inside the sheet that opened it (Run in, Move).
/// The sheet's content switches in place; no nested sheet.
struct WorktreeCreateForm: View {
    let model: WorktreeCreateModel
    let onSubmit: () -> Void

    var body: some View {
        if let state = model.state {
            if state.basePickerVisible {
                baseList(state)
            } else {
                form(state)
            }
        }
    }

    private func form(_ state: WorktreeCreateFormState) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeSectionHeader(title: WorktreeCreateCopy.nameLabel)
            OpenCodeTextInput(
                text: Binding(
                    get: { model.state?.name ?? "" },
                    set: { model.updateName($0) }
                ),
                hint: WorktreeCreateCopy.nameLabel,
                enabled: !state.submitting,
                errorMessage: state.nameError,
                submitAction: .go,
                onSubmit: {
                    if state.canSubmit { onSubmit() }
                }
            )
            .accessibilityIdentifier("worktreeCreateName")
            .padding(.horizontal, 16)
            OpenCodeSectionHeader(title: WorktreeCreateCopy.branchLabel)
            OpenCodeListItem(
                title: state.branchValue,
                subtitle: state.branchNote,
                enabled: false,
                startDecorator: .icon(.callSplit)
            )
            .accessibilityIdentifier("worktreeCreateBranch")
            OpenCodeSectionHeader(title: WorktreeCreateCopy.baseLabel)
            OpenCodeListItem(
                title: state.baseValue,
                subtitle: state.baseNote,
                enabled: state.baseSelectable && !state.submitting,
                startDecorator: .icon(.callSplit),
                accessory: state.baseSelectable ? .disclosure : nil,
                action: state.baseSelectable ? { model.showBasePicker() } : nil
            )
            .accessibilityIdentifier("worktreeCreateBase")
            VStack(alignment: .leading, spacing: 12) {
                if let error = state.errorMessage {
                    OpenCodeInlineNotice(message: error, tone: .error, variant: .banner)
                        .accessibilityIdentifier("worktreeCreateError")
                }
                OpenCodeButton(
                    variant: .fullWidthText(title: state.submitTitle),
                    enabled: state.canSubmit,
                    action: onSubmit
                )
                .accessibilityIdentifier("worktreeCreateSubmit")
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func baseList(_ state: WorktreeCreateFormState) -> some View {
        let rows = state.baseRows
        // Default first; while loading or after a failure it is the only branch row.
        baseRow(rows[0])
        switch state.baseOptionsState {
        case .loading:
            OpenCodeListItem(title: WorktreeCreateCopy.baseLoading, enabled: false)
                .accessibilityIdentifier("worktreeCreateBaseLoading")
        case .failed:
            OpenCodeListItem(title: WorktreeCreateCopy.baseFailed, enabled: false)
                .accessibilityIdentifier("worktreeCreateBaseFailed")
        case .loaded:
            ForEach(rows.dropFirst()) { row in
                baseRow(row)
            }
        }
    }

    private func baseRow(_ row: WorktreeCreateBaseOption) -> some View {
        OpenCodeListItem(
            title: row.title,
            selected: row.isSelected,
            startDecorator: .icon(.callSplit),
            action: { model.selectBase(row.ref) }
        ) {
            if row.isSelected {
                OpenCodeIconImage(icon: .check, size: 18)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityAddTraits(row.isSelected ? .isSelected : [])
        .accessibilityIdentifier(row.accessibilityIdentifier)
    }
}

/// Sheet chrome while the New worktree form shows.
enum WorktreeCreateSheetChrome {
    /// Header Back: base list → form → the sheet's list.
    static func backAction(_ model: WorktreeCreateModel) -> OpenCodeBottomSheetHeaderAction? {
        guard let state = model.state else { return nil }
        return OpenCodeBottomSheetHeaderAction(
            icon: .arrowBack,
            accessibilityLabel: WorktreeCreateCopy.back,
            enabled: !state.submitting,
            accessibilityIdentifier: "worktreeCreateBack",
            action: { model.back() }
        )
    }

    /// The form keeps the keyboard for the name; the lists dismiss it.
    static func keyboard(_ model: WorktreeCreateModel) -> OpenCodeBottomSheetKeyboard {
        guard let state = model.state, !state.basePickerVisible else { return .dismiss }
        return .keep
    }
}

/// The New worktree row at the end of a Run in or Move list.
struct NewWorktreeRow: View {
    let availability: NewWorktreeAvailability
    let accessibilityIdentifier: String
    let action: () -> Void

    var body: some View {
        OpenCodeListItem(
            title: WorktreeCreateCopy.newWorktreeRow,
            subtitle: availability.subtitle,
            enabled: availability.enabled,
            startDecorator: .icon(.add),
            action: availability.enabled ? action : nil
        )
        .accessibilityIdentifier(accessibilityIdentifier)
    }
}
