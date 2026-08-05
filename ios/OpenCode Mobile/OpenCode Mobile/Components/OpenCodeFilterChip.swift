import SwiftUI

struct OpenCodeFilterChip: View {
    /// Shared height for the count pill and remove circle so they align.
    private static let metaSize: CGFloat = 20

    let title: String
    let count: Int?
    let selected: Bool
    let action: () -> Void
    let removeAction: (() -> Void)?

    init(
        title: String,
        count: Int? = nil,
        selected: Bool,
        action: @escaping () -> Void,
        removeAction: (() -> Void)? = nil
    ) {
        self.title = title
        self.count = count
        self.selected = selected
        self.action = action
        self.removeAction = removeAction
    }

    var body: some View {
        HStack(spacing: 4) {
            Button(action: action) {
                HStack(spacing: 6) {
                    Text(title)
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                    if let count {
                        Text("\(count)")
                            .font(OpenCodeTypography.caption.weight(.semibold))
                            .foregroundStyle(OpenCodeColors.textStrong)
                            .monospacedDigit()
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, count >= 10 ? 8 : 6)
                            .frame(minWidth: countBadgeMinWidth(count), minHeight: Self.metaSize, maxHeight: Self.metaSize)
                            .background(
                                OpenCodeColors.textStrong.opacity(0.10),
                                in: Capsule()
                            )
                    }
                }
                .contentShape(Capsule())
            }
            .buttonStyle(OpenCodeFilterChipPressStyle())

            if let removeAction {
                Button(action: removeAction) {
                    OpenCodeIconImage(icon: .close)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(OpenCodeColors.textStrong.opacity(0.85))
                        .frame(width: Self.metaSize, height: Self.metaSize)
                        .background(OpenCodeColors.textStrong.opacity(0.10), in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove \(title)")
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, removeAction == nil ? 12 : 6)
        .padding(.vertical, 8)
        .frame(minHeight: 36)
        .fixedSize(horizontal: true, vertical: false)
        .contentShape(Capsule())
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
        case 0 ..< 10: Self.metaSize
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
                    .glassEffect(.regular.interactive(), in: .capsule)
            } else {
                content
                    .glassEffect(.clear.interactive(), in: .capsule)
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
                .background(OpenCodeColors.surfaceRaisedBase.opacity(0.001), in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                )
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
