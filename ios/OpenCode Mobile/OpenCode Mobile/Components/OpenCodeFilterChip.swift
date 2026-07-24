import SwiftUI

struct OpenCodeFilterChip: View {
    let title: String
    let count: Int?
    let selected: Bool
    let action: () -> Void

    init(title: String, count: Int? = nil, selected: Bool, action: @escaping () -> Void) {
        self.title = title
        self.count = count
        self.selected = selected
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(title)
                    .font(OpenCodeTypography.button.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                if let count {
                    Text("\(count)")
                        .font(OpenCodeTypography.caption.weight(.semibold))
                        .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                        .monospacedDigit()
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, count >= 10 ? 8 : 6)
                        .frame(minWidth: countBadgeMinWidth(count), minHeight: 22)
                        .background(
                            OpenCodeColors.textStrong.opacity(selected ? 0.16 : 0.06),
                            in: Capsule()
                        )
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 36)
            .fixedSize(horizontal: true, vertical: false)
            .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeFilterChipPressStyle())
        .modifier(OpenCodeFilterChipChrome(selected: selected))
        .fixedSize(horizontal: true, vertical: false)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        if let count {
            return "\(title), \(count)"
        }
        return title
    }

    private func countBadgeMinWidth(_ count: Int) -> CGFloat {
        switch count {
        case 0 ..< 10: 22
        case 10 ..< 100: 28
        case 100 ..< 1000: 36
        default: 44
        }
    }
}

private struct OpenCodeFilterChipPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.smooth(duration: 0.18), value: configuration.isPressed)
    }
}

private struct OpenCodeFilterChipChrome: ViewModifier {
    let selected: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            if selected {
                content
                    .background(OpenCodeColors.textStrong.opacity(0.12), in: Capsule())
                    .glassEffect(.regular, in: .capsule)
            } else {
                content
                    .glassEffect(.clear, in: .capsule)
            }
        } else if selected {
            content
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1.5)
                )
        } else {
            content
        }
    }
}

#if DEBUG
#Preview("Filter Chip") {
    HStack {
        OpenCodeFilterChip(title: "All", count: 4, selected: true, action: {})
        OpenCodeFilterChip(title: "opencode-mobile", count: 2, selected: false, action: {})
    }
    .padding()
    .openCodeTheme()
}
#endif
