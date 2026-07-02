import SwiftUI

struct ComponentGalleryDetailView: View {
    let entry: ComponentGalleryEntry
    @Binding var themeMode: ComponentGalleryThemeMode
    @Environment(\.dismiss) private var dismiss
    private let horizontalPadding: CGFloat = 16

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: entry.title,
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            if entry.id == .messageList {
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(entry.description)
                            .font(OpenCodeTypography.bodySecondary)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .padding(.horizontal, horizontalPadding)

                        ComponentGalleryControls(themeMode: $themeMode)
                            .padding(.horizontal, horizontalPadding)

                        if entry.id == .topBar || entry.id == .sessionStatusBar {
                            content
                        } else {
                            content
                                .padding(.horizontal, horizontalPadding)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 16)
                }
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .navigationBarHidden(true)
    }

    @ViewBuilder
    private var content: some View {
        switch entry.id {
        case .colors:
            ColorGalleryView()
        case .typography:
            TypographyGalleryView()
        case .icons:
            IconsGalleryView()
        case .outgoingMessage:
            OutgoingMessageGalleryView()
        case .topBar:
            TopBarGalleryView()
        case .bottomSheet:
            BottomSheetGalleryView()
        case .listItem:
            ListItemGalleryView()
        case .choiceBadge:
            ChoiceBadgeGalleryView()
        case .actionPill:
            ActionPillGalleryView()
        case .button:
            ButtonGalleryView()
        case .projectSessionSelector:
            ProjectSessionSelectorGalleryView()
        case .sessionStatusBar:
            SessionStatusBarGalleryView()
        case .renderedMarkdown:
            RenderedMarkdownGalleryView()
        case .messageList:
            MessageListGalleryView()
        }
    }
}

#Preview {
    NavigationStack {
        ComponentGalleryDetailView(entry: componentGalleryEntries[0], themeMode: .constant(.system))
    }
    .openCodeTheme()
}
