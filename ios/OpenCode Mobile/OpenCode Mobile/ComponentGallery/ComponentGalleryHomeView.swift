import SwiftUI

struct ComponentGalleryHomeView: View {
    @State private var themeMode: ComponentGalleryThemeMode = .system

    /// Matches Android: foundations first, then components (declaration order), not alphabetical by label.
    private var entriesByCategory: [(ComponentGalleryCategory, [ComponentGalleryEntry])] {
        let grouped = Dictionary(grouping: componentGalleryEntries, by: \.category)
        return ComponentGalleryCategory.allCases.compactMap { category in
            guard let entries = grouped[category], !entries.isEmpty else { return nil }
            return (category, entries)
        }
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Component Gallery")
                        .font(OpenCodeTypography.display)
                        .foregroundStyle(OpenCodeColors.textPrimary)
                    Text("Developer-only review surface for design-system foundations and reusable components.")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                }
                .listRowBackground(OpenCodeColors.surfacePrimary)
            }

            ForEach(entriesByCategory, id: \.0) { category, entries in
                Section(category.rawValue) {
                    ForEach(entries) { entry in
                        NavigationLink {
                            ComponentGalleryDetailView(entry: entry, themeMode: $themeMode)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(entry.title)
                                    .font(OpenCodeTypography.heading)
                                    .foregroundStyle(OpenCodeColors.textPrimary)
                                Text(entry.description)
                                    .font(OpenCodeTypography.bodySecondary)
                                    .foregroundStyle(OpenCodeColors.textSecondary)
                            }
                            .padding(.vertical, 4)
                        }
                        .listRowBackground(OpenCodeColors.surfacePrimary)
                    }
                }
            }
        }
        .navigationTitle("Gallery")
        .scrollContentBackground(.hidden)
        .background(OpenCodeColors.backgroundPrimary)
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
