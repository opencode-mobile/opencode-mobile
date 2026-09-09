import SwiftUI

struct ComponentGalleryHomeView: View {
    @State private var themeMode: ComponentGalleryThemeMode = .system
    @State private var searchQuery = ""
    @State private var searchFocused = false
    @Environment(\.dismiss) private var dismiss

    /// Matches Android: fixed category order; entries sorted alphabetically by title within each group.
    private var entriesByCategory: [(ComponentGalleryCategory, [ComponentGalleryEntry])] {
        let filtered = componentGalleryEntries.filter { $0.matchesGallerySearch(searchQuery) }
        let grouped = Dictionary(grouping: filtered, by: \.category)
        return ComponentGalleryCategory.allCases.compactMap { category in
            guard let entries = grouped[category], !entries.isEmpty else { return nil }
            return (category, entries.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending })
        }
    }

    private var hasQuery: Bool {
        !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Component Gallery",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            Group {
            if entriesByCategory.isEmpty {
                searchField
                OpenCodeEmptyState(
                    title: hasQuery ? "No components found" : "No components",
                    message: hasQuery
                        ? "Try a different search."
                        : "Component gallery entries will appear here.",
                    icon: .search
                )
                .contentShape(Rectangle())
                .onTapGesture { dismissSearchKeyboard() }
            } else {
                // Keep the field inside the ScrollView so scrollDismissesKeyboard can
                // resign it. A pinned header keeps search visible while scrolling.
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16, pinnedViews: [.sectionHeaders]) {
                        Section {
                            ForEach(entriesByCategory, id: \.0) { category, entries in
                                VStack(alignment: .leading, spacing: 12) {
                                    Text(category.rawValue)
                                        .font(OpenCodeTypography.heading)
                                        .foregroundStyle(OpenCodeColors.textStrong)
                                        .padding(.horizontal, 16)

                                    ForEach(entries) { entry in
                                        NavigationLink {
                                            ComponentGalleryDetailView(entry: entry, themeMode: $themeMode)
                                        } label: {
                                            OpenCodeListItem(
                                                title: entry.title,
                                                subtitle: entry.description,
                                                style: .card
                                            )
                                        }
                                        .buttonStyle(.plain)
                                        .padding(.horizontal, 16)
                                        .simultaneousGesture(TapGesture().onEnded { dismissSearchKeyboard() })
                                    }
                                }
                            }
                        } header: {
                            searchField
                                .background(OpenCodeColors.backgroundBase)
                        }
                    }
                    .padding(.bottom, 16)
                }
                .scrollDismissesKeyboard(.immediately)
            }
            }
            .openCodeReadableColumn()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OpenCodeColors.backgroundBase)
        .navigationBarHidden(true)
        .preferredColorScheme(themeMode.colorScheme)
    }

    private var searchField: some View {
        OpenCodeTextInput(
            text: $searchQuery,
            hint: "Search components",
            isFocused: $searchFocused,
            onSubmit: { dismissSearchKeyboard() }
        )
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func dismissSearchKeyboard() {
        guard searchFocused else { return }
        searchFocused = false
    }
}

extension ComponentGalleryEntry {
    func matchesGallerySearch(_ query: String) -> Bool {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return true }
        return title.localizedCaseInsensitiveContains(normalized)
            || description.localizedCaseInsensitiveContains(normalized)
            || category.rawValue.localizedCaseInsensitiveContains(normalized)
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
