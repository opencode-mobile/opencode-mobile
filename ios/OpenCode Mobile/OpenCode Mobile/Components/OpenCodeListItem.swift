import SwiftUI

struct OpenCodeListItem<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let selected: Bool
    let enabled: Bool
    let action: (() -> Void)?
    @ViewBuilder let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        selected: Bool = false,
        enabled: Bool = true,
        action: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        self.title = title
        self.subtitle = subtitle
        self.selected = selected
        self.enabled = enabled
        self.action = action
        self.trailing = trailing
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    content
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
            } else {
                content
            }
        }
    }

    private var content: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(enabled ? OpenCodeColors.textPrimary : OpenCodeColors.textDisabled)
                    .lineLimit(1)

                if let subtitle {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(enabled ? OpenCodeColors.textSecondary : OpenCodeColors.textDisabled)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)

            trailing()
                .foregroundStyle(OpenCodeColors.iconDefault)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(selected ? OpenCodeColors.surfaceSecondary : OpenCodeColors.surfacePrimary)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .opacity(enabled ? 1 : 0.7)
    }
}

#Preview {
    VStack(spacing: 12) {
        OpenCodeListItem(
            title: "Material Design Icons integration plan",
            subtitle: "http://127.0.0.1:4096/"
        ) {
            Text("...")
                .font(OpenCodeTypography.heading)
        }
        OpenCodeListItem(
            title: "iOS color palette and typography screens",
            subtitle: "http://127.0.0.1:4096/",
            selected: true
        ) {
            Text("...")
                .font(OpenCodeTypography.heading)
        }
        OpenCodeListItem(
            title: "Update agents.md with notes on screenshot",
            subtitle: "Unavailable",
            enabled: false
        )
    }
    .padding(16)
    .openCodeTheme()
}
