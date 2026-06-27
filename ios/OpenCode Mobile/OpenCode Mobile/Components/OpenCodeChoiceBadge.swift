import SwiftUI

struct OpenCodeChoiceBadge: View {
    let label: String
    let selected: Bool
    let enabled: Bool
    let action: () -> Void

    init(
        label: String,
        selected: Bool,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.selected = selected
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(OpenCodeTypography.button)
                .foregroundStyle(labelColor)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 32)
                .background(backgroundColor, in: RoundedRectangle(cornerRadius: 16))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .strokeBorder(borderColor, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var labelColor: Color {
        enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase
    }

    private var backgroundColor: Color {
        guard enabled else { return OpenCodeColors.surfaceRaisedBase }
        return selected ? OpenCodeColors.surfaceRaisedBase : OpenCodeColors.surfaceBase
    }

    private var borderColor: Color {
        guard enabled else { return OpenCodeColors.borderWeakerBase }
        return selected ? OpenCodeColors.borderWeakBase : OpenCodeColors.borderWeakerBase
    }
}

#Preview("Default") {
    OpenCodeChoiceBadge(label: "Label", selected: false, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Selected") {
    OpenCodeChoiceBadge(label: "Label", selected: true, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeChoiceBadge(label: "Label", selected: false, enabled: false, action: {})
        .padding()
        .openCodeTheme()
}
