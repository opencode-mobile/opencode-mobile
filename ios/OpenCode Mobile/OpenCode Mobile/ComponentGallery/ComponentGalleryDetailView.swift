import SwiftUI

struct ComponentGalleryDetailView: View {
    let entry: ComponentGalleryEntry
    @Binding var themeMode: ComponentGalleryThemeMode
    private let horizontalPadding: CGFloat = 16

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(entry.title)
                        .font(OpenCodeTypography.display)
                        .foregroundStyle(OpenCodeColors.textPrimary)
                    Text(entry.description)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                }
                .padding(.horizontal, horizontalPadding)

                ComponentGalleryControls(themeMode: $themeMode)
                    .padding(.horizontal, horizontalPadding)

                if entry.id == .topBar {
                    content
                } else {
                    content
                        .padding(.horizontal, horizontalPadding)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 16)
        }
        .navigationTitle(entry.title)
        .background(OpenCodeColors.backgroundPrimary)
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
        }
    }
}

#Preview {
    NavigationStack {
        ComponentGalleryDetailView(entry: componentGalleryEntries[0], themeMode: .constant(.system))
    }
    .openCodeTheme()
}
