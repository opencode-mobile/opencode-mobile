import SwiftUI

struct MenuChipGalleryView: View {
    @State private var choice = "All projects"
    @State private var menuPresented = false
    @State private var enabled = true

    private let choices = ["All projects", "opencode-mobile", "acme-invoices"]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Menu chip")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Shows the current choice and opens a menu. A choice other than the default is tinted.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) { enabled.toggle() }
            }

            OpenCodeAnchoredOverflowHost(isPresented: $menuPresented, entries: entries, placement: .below) {
                OpenCodeMenuChip(
                    title: .verbatim(choice),
                    leading: leading(choice),
                    selected: choice != choices[0],
                    enabled: enabled,
                    action: { menuPresented = true }
                )
            }

            Text("Variants")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            VStack(alignment: .leading, spacing: 8) {
                OpenCodeMenuChip(title: "All projects", leading: .icon(.widgets), action: {})
                OpenCodeMenuChip(title: "opencode-mobile", leading: .letter("o", .red), selected: true, action: {})
                OpenCodeMenuChip(title: "No leading", action: {})
                OpenCodeMenuChip(title: "Disabled", leading: .icon(.widgets), enabled: false, action: {})
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var entries: [OpenCodeOverflowMenuEntry] {
        choices.map { option in
            OpenCodeOverflowMenuEntry(
                id: option,
                title: .verbatim(option),
                icon: option == choice ? .check : nil,
                accessibilityLabel: option == choice ? .verbatim("\(option), selected") : nil,
                action: { choice = option }
            )
        }
    }

    private func leading(_ option: String) -> OpenCodeMenuChipLeading {
        guard option != choices[0], let first = option.first else { return .icon(.widgets) }
        return .letter(first, letterAccent(for: option))
    }
}

#Preview {
    MenuChipGalleryView()
        .padding()
        .openCodeTheme()
}
