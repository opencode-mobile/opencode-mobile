import SwiftUI

struct ComponentGalleryHomeView: View {
    @State private var themeMode: ComponentGalleryThemeMode = .system
    @Environment(\.dismiss) private var dismiss

    /// Matches Android: foundations first, then components (declaration order), not alphabetical by label.
    private var entriesByCategory: [(ComponentGalleryCategory, [ComponentGalleryEntry])] {
        let grouped = Dictionary(grouping: componentGalleryEntries, by: \.category)
        return ComponentGalleryCategory.allCases.compactMap { category in
            guard let entries = grouped[category], !entries.isEmpty else { return nil }
            return (category, entries)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Component Gallery",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            List {
                ForEach(entriesByCategory, id: \.0) { category, entries in
                    Section(category.rawValue) {
                        ForEach(entries) { entry in
                            NavigationLink {
                                ComponentGalleryDetailView(entry: entry, themeMode: $themeMode)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(entry.title)
                                        .font(OpenCodeTypography.heading)
                                        .foregroundStyle(OpenCodeColors.textStrong)
                                    Text(entry.description)
                                        .font(OpenCodeTypography.bodySecondary)
                                        .foregroundStyle(OpenCodeColors.textBase)
                                }
                                .padding(.vertical, 4)
                            }
                            .listRowBackground(OpenCodeColors.surfaceBase)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
        }
        .background(OpenCodeColors.backgroundBase)
        .navigationBarHidden(true)
        .preferredColorScheme(themeMode.colorScheme)
    }
}

#Preview {
    NavigationStack {
        ComponentGalleryHomeView()
    }
    .openCodeTheme()
}

#Preview("Dark") {
    NavigationStack {
        ComponentGalleryHomeView()
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
