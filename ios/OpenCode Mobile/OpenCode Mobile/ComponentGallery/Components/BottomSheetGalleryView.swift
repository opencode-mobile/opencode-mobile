import SwiftUI

private enum GalleryBottomSheetDecoratorKind: String, CaseIterable, Identifiable {
    case none
    case icon

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "None"
        case .icon: "Icon"
        }
    }
}

struct BottomSheetGalleryView: View {
    @State private var isPresented = false
    @State private var entryEnabled = true
    @State private var entrySelected = false
    @State private var showsHeaderBack = false
    @State private var showsHeaderTextAction = false
    @State private var headerTextActionEditing = false
    @State private var itemCount = 2
    @State private var startDecorator: GalleryBottomSheetDecoratorKind = .icon
    @State private var endDecorator: GalleryBottomSheetDecoratorKind = .none
    @State private var height: OpenCodeBottomSheetHeight = .fitContent
    @State private var keyboard: OpenCodeBottomSheetKeyboard = .dismiss
    @State private var showsPinnedTop = false
    @State private var showsPinnedBottom = false
    @State private var formValue = "http://127.0.0.1:4096/"
    @State private var isExpandPresented = false
    @State private var expandFilter = ""

    private var entryStartDecorator: BottomSheetEntryDecorator {
        switch startDecorator {
        case .none:
            .none
        case .icon:
            .icon(.folder)
        }
    }

    private var entryEndDecorator: BottomSheetEntryDecorator {
        switch endDecorator {
        case .none:
            .none
        case .icon:
            .icon(.chevronRight)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Bottom sheet")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)

                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Entry enabled",
                        selected: entryEnabled,
                        action: { entryEnabled.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Entry selected",
                        selected: entrySelected,
                        action: { entrySelected.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Header back",
                        selected: showsHeaderBack,
                        action: { showsHeaderBack.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Header Edit",
                        selected: showsHeaderTextAction,
                        action: { showsHeaderTextAction.toggle() }
                    )
                }
            }

            GalleryChoiceRow(
                title: "Height",
                options: [
                    ("Fit content", OpenCodeBottomSheetHeight.fitContent),
                    ("Half", .half),
                ],
                selected: height,
                onSelect: { height = $0 }
            )
            GalleryChoiceRow(
                title: "Keyboard",
                options: [
                    ("Dismiss", OpenCodeBottomSheetKeyboard.dismiss),
                    ("Keep", .keep),
                    ("Expand", .expand),
                ],
                selected: keyboard,
                onSelect: { keyboard = $0 }
            )

            VStack(alignment: .leading, spacing: 8) {
                Text("Pinned")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Pinned top",
                        selected: showsPinnedTop,
                        action: { showsPinnedTop.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Pinned bottom",
                        selected: showsPinnedBottom,
                        action: { showsPinnedBottom.toggle() }
                    )
                }
            }

            DecoratorControls(
                title: "Start decorator",
                selected: startDecorator,
                onSelect: { startDecorator = $0 }
            )
            DecoratorControls(
                title: "End decorator",
                selected: endDecorator,
                onSelect: { endDecorator = $0 }
            )

            ItemCountControls(selected: itemCount, onSelect: { itemCount = $0 })

            OpenCodeButton(
                variant: .fullWidthText(title: "Show bottom sheet"),
                action: { isPresented = true }
            )

            VStack(alignment: .leading, spacing: 8) {
                Text("Keyboard: expand")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("Half-height sheet that fills the space above the keyboard while the field is focused.")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                OpenCodeButton(
                    variant: .fullWidthText(title: "Show expanding sheet"),
                    action: { isExpandPresented = true }
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeBottomSheet(
            isPresented: $isExpandPresented,
            title: "Choose folder",
            height: .half,
            keyboard: .expand,
            pinnedTop: {
                OpenCodeTextInput(
                    text: $expandFilter,
                    hint: "Folder path",
                    kind: .filter
                )
                .openCodeBottomSheetContentInset()
                .padding(.bottom, 8)
            }
        ) {
            ForEach(1 ... 20, id: \.self) { index in
                OpenCodeBottomSheetEntry(
                    title: "Folder \(index)",
                    startDecorator: .icon(.folder),
                    endDecorator: .icon(.chevronRight),
                    action: {}
                )
            }
        }
        .openCodeBottomSheet(
            isPresented: $isPresented,
            title: showsHeaderBack ? "Anthropic" : "Session actions",
            leadingAction: showsHeaderBack
                ? OpenCodeBottomSheetHeaderAction(
                    icon: .arrowBack,
                    accessibilityLabel: "Back to providers",
                    action: { showsHeaderBack = false }
                )
                : nil,
            trailingAction: showsHeaderTextAction
                ? OpenCodeBottomSheetHeaderAction(
                    text: headerTextActionEditing ? "Done" : "Edit",
                    action: { headerTextActionEditing.toggle() }
                )
                : nil,
            height: height,
            keyboard: keyboard,
            pinnedTop: {
                if showsPinnedTop {
                    OpenCodeBottomSheetEntry(
                        title: "Go up",
                        startDecorator: .icon(.arrowUpward),
                        action: {}
                    )
                    OpenCodeBottomSheetEntry(
                        title: "/Users/demo/Projects",
                        startDecorator: .icon(.folderCheck),
                        action: {}
                    )
                }
            },
            content: {
                if keyboard == .dismiss {
                    actionEntries
                } else {
                    OpenCodeTextInput(
                        text: $formValue,
                        hint: "Server URL",
                        kind: .url
                    )
                    .openCodeBottomSheetContentInset()
                    OpenCodeTextInput(
                        text: .constant("gallery password"),
                        hint: "Password",
                        kind: .password
                    )
                    .openCodeBottomSheetContentInset()
                }
            },
            pinnedBottom: {
                if showsPinnedBottom {
                    OpenCodeButton(
                        variant: .fullWidthText(title: "Done"),
                        action: { isPresented = false }
                    )
                }
            }
        )
    }

    @ViewBuilder
    private var actionEntries: some View {
            OpenCodeBottomSheetEntry(
                title: "Open current project",
                enabled: entryEnabled,
                startDecorator: entryStartDecorator,
                endDecorator: entryEndDecorator,
                selected: entrySelected,
                action: { isPresented = false }
            )
            OpenCodeBottomSheetEntry(
                title: "Create new session",
                startDecorator: .icon(.add),
                action: { isPresented = false }
            )
            if itemCount >= 4 {
                OpenCodeBottomSheetEntry(
                    title: "Rename session",
                    startDecorator: .icon(.edit),
                    action: { isPresented = false }
                )
                OpenCodeBottomSheetEntry(
                    title: "Fork session",
                    endDecorator: .icon(.chevronRight),
                    action: { isPresented = false }
                )
            }
            if itemCount >= 6 {
                OpenCodeBottomSheetEntry(
                    title: "Review diff",
                    startDecorator: .icon(.description),
                    action: { isPresented = false }
                )
                OpenCodeBottomSheetEntry(
                    title: "Abort active work",
                    startDecorator: .icon(.stopCircle),
                    action: { isPresented = false }
                )
            }
    }
}

private struct DecoratorControls: View {
    let title: String
    let selected: GalleryBottomSheetDecoratorKind
    let onSelect: (GalleryBottomSheetDecoratorKind) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            HStack(spacing: 8) {
                ForEach(GalleryBottomSheetDecoratorKind.allCases) { kind in
                    OpenCodeChoiceBadge(
                        label: kind.label,
                        selected: selected == kind,
                        action: { onSelect(kind) }
                    )
                }
            }
        }
    }
}

private struct GalleryChoiceRow<Value: Equatable>: View {
    let title: String
    let options: [(label: String, value: Value)]
    let selected: Value
    let onSelect: (Value) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            HStack(spacing: 8) {
                ForEach(options.indices, id: \.self) { index in
                    OpenCodeChoiceBadge(
                        label: options[index].label,
                        selected: options[index].value == selected,
                        action: { onSelect(options[index].value) }
                    )
                }
            }
        }
    }
}

private struct ItemCountControls: View {
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Items")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            HStack(spacing: 8) {
                ForEach([2, 4, 6], id: \.self) { count in
                    OpenCodeChoiceBadge(
                        label: "\(count)",
                        selected: selected == count,
                        action: { onSelect(count) }
                    )
                }
            }
        }
    }
}

#Preview {
    BottomSheetGalleryView()
        .padding(16)
        .openCodeTheme()
}
