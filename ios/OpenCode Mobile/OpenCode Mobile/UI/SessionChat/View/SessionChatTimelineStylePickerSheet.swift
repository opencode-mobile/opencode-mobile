import SwiftUI

extension SessionChatTimelineStyle {
    /// Skeleton illustration the Timeline Style picker shows for this style.
    var pickerIllustration: OpenCodeSkeletonIllustrationTopic {
        switch self {
        case .standard: .timelineStandard
        case .chips: .timelineChips
        case .stepRail: .timelineStepRail
        case .activityCard: .timelineActivityCard
        }
    }
}

/// One-time Timeline Style picker for updated installs (#805): a bottom sheet
/// that starts on Activity Card. Swipe, scrim, and back count as dismiss.
struct SessionChatTimelineStylePickerModifier: ViewModifier {
    let isPresented: Bool
    let styles: [SessionChatTimelineStyle]
    let currentStyle: SessionChatTimelineStyle
    let onUse: (SessionChatTimelineStyle) -> Void
    let onDismiss: () -> Void

    @State private var selection: SessionChatTimelineStyle = .activityCard

    func body(content: Content) -> some View {
        // Read here, not only inside the sheet's closures: the sheet hosts its
        // content separately and refreshes only when this body runs again.
        let selected = selection
        return content
            .openCodeBottomSheet(
                isPresented: presentedBinding,
                title: "New: choose your timeline",
                pinnedTop: { EmptyView() },
                content: { sheetContent(selected: selected) },
                pinnedBottom: {
                    VStack(spacing: 10) {
                        OpenCodeButton(variant: .fullWidthText(title: "Use \(selected.title)")) {
                            onUse(selected)
                        }
                        Text("You can change this any time in Settings → Timeline Style.")
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                    }
                }
            )
            .onChange(of: isPresented, initial: true) { _, presented in
                guard presented else { return }
                selection = .activityCard
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.sessionTimelineStylePicker,
                    presentation: ProductAnalytics.Presentation.sheet,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
    }

    private func sheetContent(selected: SessionChatTimelineStyle) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("This update adds new ways to follow what the agent is doing.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .openCodeBottomSheetContentInset()
                .padding(.bottom, 8)

            ForEach(orderedStyles, id: \.self) { style in
                OpenCodeBottomSheetEntry(
                    title: style.title,
                    subtitle: style.subtitle,
                    startDecorator: .illustration(style.pickerIllustration),
                    selected: style == selected,
                    tag: tag(for: style),
                    action: { selection = style }
                )
            }
        }
    }

    /// Activity Card first: it is preselected and recommended, so it must be in view.
    private var orderedStyles: [SessionChatTimelineStyle] {
        styles.filter { $0 == .activityCard } + styles.filter { $0 != .activityCard }
    }

    private func tag(for style: SessionChatTimelineStyle) -> String? {
        if style == .activityCard { return "Recommended" }
        if style == currentStyle { return "Current" }
        return nil
    }

    private var presentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented { onDismiss() }
            }
        )
    }
}

extension View {
    func sessionChatTimelineStylePicker(
        isPresented: Bool,
        styles: [SessionChatTimelineStyle],
        currentStyle: SessionChatTimelineStyle,
        onUse: @escaping (SessionChatTimelineStyle) -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        modifier(
            SessionChatTimelineStylePickerModifier(
                isPresented: isPresented,
                styles: styles,
                currentStyle: currentStyle,
                onUse: onUse,
                onDismiss: onDismiss
            )
        )
    }
}
