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
    @State private var showsHeaderBack = false
    @State private var itemCount = 2
    @State private var startDecorator: GalleryBottomSheetDecoratorKind = .icon
    @State private var endDecorator: GalleryBottomSheetDecoratorKind = .none

    private var entryStartDecorator: BottomSheetEntryDecorator {
        switch startDecorator {
        case .none:
            .none
        case .icon:
            .icon(.folder, accessibilityLabel: "Project")
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
                    .foregroundStyle(OpenCodeColors.textPrimary)

                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Entry enabled",
                        selected: entryEnabled,
                        action: { entryEnabled.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Header back",
                        selected: showsHeaderBack,
                        action: { showsHeaderBack.toggle() }
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
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeBottomSheet(
            isPresented: $isPresented,
            title: showsHeaderBack ? "Anthropic" : "Session actions",
            leadingAction: showsHeaderBack
                ? OpenCodeBottomSheetHeaderAction(
                    icon: .arrowBack,
                    accessibilityLabel: "Back to providers",
                    action: { showsHeaderBack = false }
                )
                : nil
        ) {
            OpenCodeBottomSheetEntry(
                title: "Open current project",
                enabled: entryEnabled,
                startDecorator: entryStartDecorator,
                endDecorator: entryEndDecorator,
                action: { isPresented = false }
            )
            OpenCodeBottomSheetEntry(
                title: "Create new session",
                startDecorator: .icon(.add, accessibilityLabel: "Create new session"),
                action: { isPresented = false }
            )
            if itemCount >= 4 {
                OpenCodeBottomSheetEntry(
                    title: "Rename session",
                    startDecorator: .icon(.edit, accessibilityLabel: "Rename session"),
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
                    startDecorator: .icon(.description, accessibilityLabel: "Review diff"),
                    action: { isPresented = false }
                )
                OpenCodeBottomSheetEntry(
                    title: "Abort active work",
                    startDecorator: .icon(.stopCircle, accessibilityLabel: "Abort active work"),
                    action: { isPresented = false }
                )
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
                .foregroundStyle(OpenCodeColors.textPrimary)

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

private struct ItemCountControls: View {
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Items")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textPrimary)

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
