import SwiftUI

struct ComponentGalleryDetailView: View {
    let entry: ComponentGalleryEntry
    @Binding var themeMode: ComponentGalleryThemeMode

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

                ComponentGalleryControls(themeMode: $themeMode)

                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
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
        case .listItem:
            ListItemGalleryView()
        }
    }
}

#Preview {
    NavigationStack {
        ComponentGalleryDetailView(entry: componentGalleryEntries[0], themeMode: .constant(.system))
    }
    .openCodeTheme()
}
