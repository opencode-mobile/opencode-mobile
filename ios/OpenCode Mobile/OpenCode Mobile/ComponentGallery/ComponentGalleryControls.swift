import SwiftUI

enum ComponentGalleryThemeMode: String, CaseIterable, Identifiable {
    case system = "System"
    case light = "Light"
    case dark = "Dark"

    var id: String { rawValue }

    var colorScheme: ColorScheme? {
        switch self {
        case .system:
            nil
        case .light:
            .light
        case .dark:
            .dark
        }
    }
}

struct ComponentGalleryControls: View {
    @Binding var themeMode: ComponentGalleryThemeMode

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Theme")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            HStack(spacing: 8) {
                ForEach(ComponentGalleryThemeMode.allCases) { mode in
                    OpenCodeChoiceBadge(
                        label: .verbatim(mode.rawValue),
                        selected: themeMode == mode,
                        action: { themeMode = mode }
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ComponentGalleryControls(themeMode: .constant(.system))
        .padding()
        .openCodeTheme()
}
