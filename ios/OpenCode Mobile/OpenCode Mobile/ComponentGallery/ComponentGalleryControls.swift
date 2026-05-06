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
                .foregroundStyle(OpenCodeColors.textPrimary)

            Picker("Theme", selection: $themeMode) {
                ForEach(ComponentGalleryThemeMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.segmented)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ComponentGalleryControls(themeMode: .constant(.system))
        .padding()
        .openCodeTheme()
}
