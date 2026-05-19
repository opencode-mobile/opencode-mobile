import SwiftUI

private let bottomSheetHeaderHorizontalPadding: CGFloat = 20
private let bottomSheetHeaderTopPadding: CGFloat = 12
private let bottomSheetHeaderBottomPadding: CGFloat = 8
private let bottomSheetContentBottomPadding: CGFloat = 20
private let bottomSheetEntryMinHeight: CGFloat = 48
private let bottomSheetEntryHorizontalPadding: CGFloat = 20
private let bottomSheetEntryVerticalPadding: CGFloat = 12
private let bottomSheetEntryDecoratorSize: CGFloat = 24
private let bottomSheetEntryIconSize: CGFloat = 20
private let bottomSheetInitialHeight: CGFloat = 176

enum BottomSheetEntryDecorator {
    case none
    case icon(OpenCodeIcon, accessibilityLabel: String? = nil)
}

private struct BottomSheetContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct OpenCodeBottomSheetSheetBody<SheetContent: View>: View {
    let title: String
    let onDismiss: () -> Void
    @ViewBuilder let sheetContent: () -> SheetContent
    @State private var fittedHeight: CGFloat = bottomSheetInitialHeight
    @State private var selectedDetent: PresentationDetent = .height(bottomSheetInitialHeight)

    var body: some View {
        OpenCodeBottomSheetContainer(title: title, onDismiss: onDismiss, content: sheetContent)
            .background(
                GeometryReader { geo in
                    Color.clear.preference(key: BottomSheetContentHeightKey.self, value: geo.size.height)
                },
            )
            .onPreferenceChange(BottomSheetContentHeightKey.self) { height in
                guard height > 1 else { return }
                fittedHeight = height
                selectedDetent = .height(height)
            }
            .presentationDetents([.height(fittedHeight)], selection: $selectedDetent)
            .presentationCornerRadius(24)
            .presentationBackground(OpenCodeColors.surfacePrimary)
            .presentationDragIndicator(.hidden)
    }
}

struct OpenCodeBottomSheet<SheetContent: View>: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    @ViewBuilder let sheetContent: () -> SheetContent

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $isPresented) {
                OpenCodeBottomSheetSheetBody(
                    title: title,
                    onDismiss: { isPresented = false },
                    sheetContent: sheetContent,
                )
            }
    }
}

struct OpenCodeBottomSheetEntry: View {
    let title: String
    let enabled: Bool
    let startDecorator: BottomSheetEntryDecorator
    let endDecorator: BottomSheetEntryDecorator
    let action: () -> Void

    init(
        title: String,
        enabled: Bool = true,
        startDecorator: BottomSheetEntryDecorator = .none,
        endDecorator: BottomSheetEntryDecorator = .none,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.enabled = enabled
        self.startDecorator = startDecorator
        self.endDecorator = endDecorator
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                BottomSheetEntryDecoration(decorator: startDecorator, enabled: enabled)

                Text(title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(enabled ? OpenCodeColors.textPrimary : OpenCodeColors.textDisabled)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                BottomSheetEntryDecoration(decorator: endDecorator, enabled: enabled)
            }
            .padding(.horizontal, bottomSheetEntryHorizontalPadding)
            .padding(.vertical, bottomSheetEntryVerticalPadding)
            .frame(minHeight: bottomSheetEntryMinHeight)
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.7)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

private struct OpenCodeBottomSheetContainer<SheetContent: View>: View {
    let title: String
    let onDismiss: () -> Void
    @ViewBuilder let content: () -> SheetContent

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text(title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textPrimary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                OpenCodeButton(
                    variant: .iconCircle(.close, accessibilityLabel: "Close"),
                    contrast: .low,
                    action: onDismiss
                )
            }
            .padding(.horizontal, bottomSheetHeaderHorizontalPadding)
            .padding(.top, bottomSheetHeaderTopPadding)
            .padding(.bottom, bottomSheetHeaderBottomPadding)

            content()
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .padding(.bottom, bottomSheetContentBottomPadding)
        .background(OpenCodeColors.surfacePrimary)
        // Sheet presentation proposes infinite height; opt out so height tracks content (wrap content).
        .fixedSize(horizontal: false, vertical: true)
    }
}

private struct BottomSheetEntryDecoration: View {
    let decorator: BottomSheetEntryDecorator
    let enabled: Bool

    var body: some View {
        switch decorator {
        case .none:
            EmptyView()
        case let .icon(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon)
                .font(.system(size: bottomSheetEntryIconSize, weight: .medium))
                .foregroundStyle(enabled ? OpenCodeColors.iconDefault : OpenCodeColors.textDisabled)
                .frame(width: bottomSheetEntryDecoratorSize, height: bottomSheetEntryDecoratorSize)
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(accessibilityLabel ?? "")
        }
    }
}

extension View {
    func openCodeBottomSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        title: String,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        modifier(
            OpenCodeBottomSheet(
                isPresented: isPresented,
                title: title,
                sheetContent: content
            )
        )
    }
}

#Preview("Bottom Sheet Light") {
    Color.clear
        .frame(height: 1)
        .openCodeBottomSheet(isPresented: .constant(true), title: "Session actions") {
            OpenCodeBottomSheetEntry(
                title: "New session",
                startDecorator: .icon(.add, accessibilityLabel: "New session"),
                action: {}
            )
            OpenCodeBottomSheetEntry(
                title: "Open project",
                startDecorator: .icon(.folder, accessibilityLabel: "Open project"),
                endDecorator: .icon(.chevronRight),
                action: {}
            )
        }
        .openCodeTheme()
}

#Preview("Bottom Sheet Dark") {
    Color.clear
        .frame(height: 1)
        .openCodeBottomSheet(isPresented: .constant(true), title: "Session actions") {
            OpenCodeBottomSheetEntry(title: "Rename session", action: {})
            OpenCodeBottomSheetEntry(
                title: "Fork session",
                endDecorator: .icon(.chevronRight),
                action: {}
            )
            OpenCodeBottomSheetEntry(
                title: "Delete session",
                enabled: false,
                startDecorator: .icon(.close, accessibilityLabel: "Delete session"),
                action: {}
            )
        }
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
