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

    /// Picker name of this style.
    var pickerTitle: LocalizedStringResource {
        switch self {
        case .standard: LocalizedStringResource("session_chat_sheet.timeline_style_picker.standard.title", defaultValue: "Standard")
        case .chips: LocalizedStringResource("session_chat_sheet.timeline_style_picker.chips.title", defaultValue: "Chips")
        case .stepRail: LocalizedStringResource("session_chat_sheet.timeline_style_picker.step_rail.title", defaultValue: "Step Rail")
        case .activityCard:
            LocalizedStringResource("session_chat_sheet.timeline_style_picker.activity_card.title", defaultValue: "Activity Card")
        }
    }

    /// Picker description of this style.
    var pickerSubtitle: OpenCodeText {
        switch self {
        case .standard: "session_chat_sheet.timeline_style_picker.standard.subtitle"
        case .chips: "session_chat_sheet.timeline_style_picker.chips.subtitle"
        case .stepRail: "session_chat_sheet.timeline_style_picker.step_rail.subtitle"
        case .activityCard: "session_chat_sheet.timeline_style_picker.activity_card.subtitle"
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
                title: "session_chat_sheet.timeline_style_picker.title",
                // Every option stays in view; the 50% detent hid the last one.
                height: .fitAllContent,
                pinnedTop: { EmptyView() },
                content: { sheetContent(selected: selected) },
                pinnedBottom: {
                    VStack(spacing: 10) {
                        OpenCodeButton(variant: .fullWidthText(title: useTitle(selected))) {
                            onUse(selected)
                        }
                        Text("session_chat_sheet.timeline_style_picker.footnote")
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
            Text("session_chat_sheet.timeline_style_picker.explainer")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .openCodeBottomSheetContentInset()
                .padding(.bottom, 8)

            VStack(spacing: 8) {
                ForEach(orderedStyles, id: \.self) { style in
                    OpenCodeOptionCard(
                        title: OpenCodeText(style.pickerTitle),
                        subtitle: style.pickerSubtitle,
                        tag: tag(for: style),
                        tagTone: style == .activityCard ? .accent : .neutral,
                        preview: .illustration(style.pickerIllustration),
                        selected: style == selected,
                        action: { selection = style }
                    )
                }
            }
            .openCodeBottomSheetContentInset()
        }
    }

    /// Activity Card first: it is preselected and recommended, so it must be in view.
    private var orderedStyles: [SessionChatTimelineStyle] {
        styles.filter { $0 == .activityCard } + styles.filter { $0 != .activityCard }
    }

    private func tag(for style: SessionChatTimelineStyle) -> OpenCodeText? {
        if style == .activityCard { return "session_chat_sheet.timeline_style_picker.recommended" }
        if style == currentStyle { return "session_chat_sheet.timeline_style_picker.current" }
        return nil
    }

    /// "Use Activity Card".
    private func useTitle(_ style: SessionChatTimelineStyle) -> OpenCodeText {
        let name = String(localized: style.pickerTitle)
        return OpenCodeText(LocalizedStringResource(
            "session_chat_sheet.timeline_style_picker.use",
            defaultValue: "Use \(name)"
        ))
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
