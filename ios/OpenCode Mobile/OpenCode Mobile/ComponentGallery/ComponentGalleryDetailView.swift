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

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(entry.description)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textSecondary)
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
        .background(OpenCodeColors.backgroundPrimary)
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
        case .button:
            ButtonGalleryView()
        case .projectSessionSelector:
            ProjectSessionSelectorGalleryView()
        case .sessionStatusBar:
            SessionStatusBarGalleryView()
        case .renderedMarkdown:
            RenderedMarkdownGalleryView()
        }
    }
}

#Preview {
    NavigationStack {
        ComponentGalleryDetailView(entry: componentGalleryEntries[0], themeMode: .constant(.system))
    }
    .openCodeTheme()
}
