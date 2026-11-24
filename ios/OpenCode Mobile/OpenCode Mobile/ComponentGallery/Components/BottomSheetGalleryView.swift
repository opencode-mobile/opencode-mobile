import SwiftUI

enum GalleryBottomSheetDecoratorKind: String, CaseIterable, Identifiable {
    case none
    case icon
    case illustration

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "None"
        case .icon: "Icon"
        case .illustration: "Illustration"
        }
    }
}

struct BottomSheetGalleryView: View {
    @State private var isPresented = false
    @State private var entryEnabled = true
    @State private var entrySelected = false
    @State private var entryTagged = false
    @State private var entryNew = false
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

    static func startDecorator(_ kind: GalleryBottomSheetDecoratorKind) -> BottomSheetEntryDecorator {
        switch kind {
        case .none:
            .none
        case .icon:
            .icon(.folder)
        case .illustration:
            .illustration(.timelineActivityCard)
        }
    }

    static func endDecorator(_ kind: GalleryBottomSheetDecoratorKind) -> BottomSheetEntryDecorator {
        switch kind {
        case .none:
            .none
        case .icon:
            .icon(.chevronRight)
        case .illustration:
            .illustration(.timelineStepRail)
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
                        label: "Entry tag",
                        selected: entryTagged,
                        action: { entryTagged.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Entry new",
                        selected: entryNew,
                        action: { entryNew.toggle() }
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
                    ("Fit all", .fitAllContent),
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

    private var actionEntries: some View {
        Self.actionEntries(
            itemCount: itemCount,
            entryEnabled: entryEnabled,
            entrySelected: entrySelected,
            entryTagged: entryTagged,
            entryNew: entryNew,
            start: startDecorator,
            end: endDecorator,
            onSelect: { isPresented = false }
        )
    }

    /// The Session actions sheet entries. 12 items add six plain extra actions;
    /// `destructive` marks Abort active work, `entrySubtitle` gives the first entry a subtitle.
    @ViewBuilder
    static func actionEntries(
        itemCount: Int,
        entryEnabled: Bool = true,
        entrySelected: Bool = false,
        entryTagged: Bool = false,
        entryNew: Bool = false,
        entrySubtitle: Bool = false,
        destructive: Bool = false,
        start: GalleryBottomSheetDecoratorKind = .icon,
        end: GalleryBottomSheetDecoratorKind = .none,
        onSelect: @escaping () -> Void = {}
    ) -> some View {
        OpenCodeBottomSheetEntry(
            title: "Open current project",
            subtitle: entrySubtitle ? "Tool cards and groups between the agent's messages" : nil,
            enabled: entryEnabled,
            startDecorator: startDecorator(start),
            endDecorator: endDecorator(end),
            selected: entrySelected,
            tag: entryTagged ? "Recommended" : nil,
            isNew: entryNew,
            action: onSelect
        )
        OpenCodeBottomSheetEntry(
            title: "Create new session",
            startDecorator: .icon(.add),
            action: onSelect
        )
        if itemCount >= 4 {
            OpenCodeBottomSheetEntry(title: "Rename session", startDecorator: .icon(.edit), action: onSelect)
            OpenCodeBottomSheetEntry(title: "Fork session", endDecorator: .icon(.chevronRight), action: onSelect)
        }
        if itemCount >= 6 {
            OpenCodeBottomSheetEntry(title: "Review diff", startDecorator: .icon(.description), action: onSelect)
            OpenCodeBottomSheetEntry(
                title: "Abort active work",
                destructive: destructive,
                startDecorator: .icon(.stopCircle),
                action: onSelect
            )
        }
        if itemCount >= 12 {
            ForEach(1 ... 6, id: \.self) { index in
                OpenCodeBottomSheetEntry(title: .verbatim("Extra action \(index)"), action: onSelect)
            }
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
                        label: .verbatim(kind.label),
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
                        label: .verbatim(options[index].label),
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
