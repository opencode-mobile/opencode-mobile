import SwiftUI

struct SplitPaneGalleryView: View {
    /// Gallery-only store so demo drags never change the real Home or Settings panes.
    @State private var preferences = SplitPanePreferences(
        defaults: UserDefaults(suiteName: "OpenCodeSplitPaneGallery") ?? .standard
    )

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Drag the handle to resize; release narrow to snap closed; tap to toggle.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)

            OpenCodeSplitPane(screen: .home, preferences: preferences) {
                VStack(spacing: 0) {
                    OpenCodeTopBar(title: "Leading")
                    OpenCodeListItem(title: "Selected row", subtitle: "Highlighted in the list", selected: true)
                    OpenCodeListItem(title: "Another row", subtitle: "Tap selects on tablets")
                    Spacer(minLength: 0)
                }
            } trailing: { control in
                VStack(spacing: 0) {
                    OpenCodeTopBar(
                        title: "Detail",
                        leading: OpenCodeSplitPane.detailLeading(control, back: {})
                    )
                    OpenCodeEmptyState(
                        title: control.isCollapsed ? "Sidebar hidden" : "Sidebar shown",
                        message: "The top-bar button toggles the leading pane.",
                        icon: .sidebar
                    )
                    .openCodeReadableColumn()
                }
            }
            .frame(height: 360)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

#Preview {
    SplitPaneGalleryView()
        .padding()
        .openCodeTheme()
}
