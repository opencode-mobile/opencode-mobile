import SwiftUI

struct SplitButtonGalleryView: View {
    @State private var contrast: OpenCodeButtonContrast = .low
    @State private var enabled = true
    @State private var focusRing = false
    @State private var menuPresented = false
    @State private var lastAction = "None"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Split button")
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("The label runs the action. The ▾ half, a long-press, or a right-click opens a menu of related actions.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled, action: { enabled.toggle() })
                OpenCodeChoiceBadge(label: "Keyboard focus", selected: focusRing, action: { focusRing.toggle() })
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Low contrast", selected: contrast == .low, action: { contrast = .low })
                OpenCodeChoiceBadge(label: "High contrast", selected: contrast == .high, action: { contrast = .high })
            }

            OpenCodeAnchoredOverflowHost(isPresented: $menuPresented, entries: entries, placement: .below) {
                OpenCodeSplitButton(
                    title: "Deny",
                    menuAccessibilityLabel: "Deny options",
                    enabled: enabled,
                    contrast: contrast,
                    action: { lastAction = "Deny" },
                    menuAction: { menuPresented = true }
                )
                .openCodeFocusRingPreview(focusRing)
            }

            Text("Last action: \(lastAction)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)

            Text("States")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    OpenCodeSplitButton(
                        title: "Deny", menuAccessibilityLabel: "Deny options", contrast: .low,
                        action: {}, menuAction: {}
                    )
                    OpenCodeSplitButton(
                        title: "Deny", menuAccessibilityLabel: "Deny options", enabled: false, contrast: .low,
                        action: {}, menuAction: {}
                    )
                }
                HStack(spacing: 12) {
                    OpenCodeSplitButton(
                        title: "Run", menuAccessibilityLabel: "Run options", contrast: .high,
                        action: {}, menuAction: {}
                    )
                    OpenCodeSplitButton(
                        title: "Run", menuAccessibilityLabel: "Run options", enabled: false, contrast: .high,
                        action: {}, menuAction: {}
                    )
                }
                // Beside a compact button, as in the permission prompt.
                HStack(spacing: 8) {
                    OpenCodeSplitButton(
                        title: "Deny", menuAccessibilityLabel: "Deny options", contrast: .low,
                        action: {}, menuAction: {}
                    )
                    Spacer(minLength: 0)
                    OpenCodeButton(variant: .compactText(title: "Always"), contrast: .low, action: {})
                    OpenCodeButton(variant: .compactText(title: "Allow once"), action: {})
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var entries: [OpenCodeOverflowMenuEntry] {
        [
            OpenCodeOverflowMenuEntry(id: "deny", title: "Deny", icon: .close, action: { lastAction = "Deny" }),
            OpenCodeOverflowMenuEntry(
                id: "denyWithNote",
                title: "Deny with a note…",
                subtitle: "Tell the agent what to do instead",
                icon: .edit,
                action: { lastAction = "Deny with a note" }
            ),
        ]
    }
}

#Preview {
    SplitButtonGalleryView()
        .padding()
        .openCodeTheme()
}
