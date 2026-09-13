import SwiftUI

private let listItemCornerRadius: CGFloat = 8
private let listItemLetterBadgeSize: CGFloat = 32
private let listItemLetterGlyphSize: CGFloat = 14
private let listItemLetterStatusDotSize: CGFloat = 10
private let listItemIconDecoratorSize: CGFloat = 24
private let listItemIconCircleGlyphSize: CGFloat = 16
private let listItemVerticalPadding: CGFloat = 12
private let listItemTitleSubtitleSpacing: CGFloat = 2
private let listItemMetaSpacing: CGFloat = 4
private let listItemPressedScale: CGFloat = 0.965
private let listItemPopPeakScale: CGFloat = 1.035
private let listItemPressAnimation = Animation.easeOut(duration: 0.12)
private let listItemPopAnimation = Animation.spring(response: 0.28, dampingFraction: 0.52)
private let listItemPopSettleAnimation = Animation.spring(response: 0.34, dampingFraction: 0.72)

enum OpenCodeListItemStyle {
    case plain
    case card
}

enum OpenCodeListItemSubtitleTone {
    case secondary
    case addition
    case deletion
}

struct OpenCodeListItemSubtitleSegment {
    let text: String
    let tone: OpenCodeListItemSubtitleTone
}

enum OpenCodeListItemStatusTone {
    case interactive
    case success
    case warning
    case error
    case info
}

struct OpenCodeListItemStatus {
    let text: String
    let tone: OpenCodeListItemStatusTone
}

struct OpenCodeListItem<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let subtitleSegments: [OpenCodeListItemSubtitleSegment]?
    let status: OpenCodeListItemStatus?
    let caption: String?
    let selected: Bool
    let enabled: Bool
    let style: OpenCodeListItemStyle
    let startDecorator: StartDecorator
    let action: (() -> Void)?
    let onLongPress: (() -> Void)?
    @ViewBuilder let trailing: () -> Trailing

    @State private var isPressing = false
    @State private var popScale: CGFloat = 1

    init(
        title: String,
        subtitle: String? = nil,
        subtitleSegments: [OpenCodeListItemSubtitleSegment]? = nil,
        status: OpenCodeListItemStatus? = nil,
        caption: String? = nil,
        selected: Bool = false,
        enabled: Bool = true,
        style: OpenCodeListItemStyle = .plain,
        startDecorator: StartDecorator = .none,
        action: (() -> Void)? = nil,
        onLongPress: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        precondition(subtitle == nil || subtitleSegments == nil, "List Item accepts either a plain subtitle or semantic segments")
        self.title = title
        self.subtitle = subtitle
        self.subtitleSegments = subtitleSegments
        self.status = status
        self.caption = caption
        self.selected = selected
        self.enabled = enabled
        self.style = style
        self.startDecorator = startDecorator
        self.action = action
        self.onLongPress = onLongPress
        self.trailing = trailing
    }

    var body: some View {
        Group {
            if let onLongPress {
                // Gestures must sit above Text in an overlay. Putting onTap/onLongPress
                // directly on `content` lets Text claim the touch, so long-press only
                // works on padding/Spacer (background) and fails on the title/subtitle.
                content
                    .background { pressHighlight }
                    .scaleEffect(rowScale)
                    .animation(listItemPressAnimation, value: isPressing)
                    .animation(listItemPopAnimation, value: popScale)
                    .overlay {
                        Color.clear
                            .contentShape(Rectangle())
                            .onTapGesture {
                                guard enabled else { return }
                                action?()
                            }
                            .onLongPressGesture(
                                minimumDuration: 0.42,
                                maximumDistance: 18,
                                pressing: { pressing in
                                    guard enabled else { return }
                                    isPressing = pressing
                                    if pressing {
                                        OpenCodeHaptics.prepareContextAction()
                                    }
                                },
                                perform: {
                                    guard enabled else { return }
                                    isPressing = false
                                    triggerContextActionPop()
                                    OpenCodeHaptics.contextAction()
                                    onLongPress()
                                }
                            )
                    }
                    .allowsHitTesting(enabled)
            } else if let action {
                Button(action: action) {
                    content
                }
                .buttonStyle(OpenCodeListItemPressStyle(enabled: enabled))
                .disabled(!enabled)
            } else {
                content
            }
        }
    }

    private var rowScale: CGFloat {
        if isPressing { return listItemPressedScale }
        return popScale
    }

    @ViewBuilder
    private var pressHighlight: some View {
        if isPressing || popScale != 1 {
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceRaisedBase.opacity(isPressing ? 0.92 : 0.55))
        }
    }

    private func triggerContextActionPop() {
        popScale = listItemPressedScale
        withAnimation(listItemPopAnimation) {
            popScale = listItemPopPeakScale
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 90_000_000)
            withAnimation(listItemPopSettleAnimation) {
                popScale = 1
            }
        }
    }

    private var hasMeta: Bool {
        status != nil || (caption?.isEmpty == false)
    }

    private var content: some View {
        HStack(alignment: .center, spacing: 12) {
            startDecoratorContent

            VStack(alignment: .leading, spacing: listItemTitleSubtitleSpacing) {
                Text(title)
                    .font(OpenCodeTypography.body.weight(.semibold))
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                    .lineLimit(1)

                if let subtitleSegments {
                    semanticSubtitle(subtitleSegments)
                        .font(OpenCodeTypography.caption)
                        .lineLimit(1)
                } else if let subtitle {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if hasMeta {
                metaColumn
            }

            trailing()
                .foregroundStyle(OpenCodeColors.iconStrongBase)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .padding(.horizontal, 16)
        .padding(.vertical, listItemVerticalPadding)
        .background {
            containerBackground
        }
        .overlay {
            if style == .card {
                RoundedRectangle(cornerRadius: listItemCornerRadius)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            }
        }
        .opacity(enabled ? 1 : 0.7)
    }

    private var metaColumn: some View {
        VStack(alignment: .trailing, spacing: listItemMetaSpacing) {
            if let caption, !caption.isEmpty {
                Text(caption)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, 2)
            }
            if let status {
                Text(status.text)
                    .font(OpenCodeTypography.caption.weight(.semibold))
                    .foregroundStyle(statusForeground(status.tone))
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(statusBackground(status.tone), in: Capsule())
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private func semanticSubtitle(_ segments: [OpenCodeListItemSubtitleSegment]) -> Text {
        segments.reduce(Text("")) { result, segment in
            result + Text(segment.text)
                .foregroundColor(subtitleColor(segment.tone))
        }
    }

    private func subtitleColor(_ tone: OpenCodeListItemSubtitleTone) -> Color {
        guard enabled else { return OpenCodeColors.textWeaker }
        switch tone {
        case .secondary:
            return OpenCodeColors.textBase
        case .addition:
            return OpenCodeColors.diffAdd
        case .deletion:
            return OpenCodeColors.diffDelete
        }
    }

    private func statusForeground(_ tone: OpenCodeListItemStatusTone) -> Color {
        guard enabled else { return OpenCodeColors.textWeaker }
        switch tone {
        case .interactive:
            return OpenCodeColors.interactive
        case .success:
            return OpenCodeColors.success
        case .warning:
            return OpenCodeColors.warning
        case .error:
            return OpenCodeColors.error
        case .info:
            return OpenCodeColors.textBase
        }
    }

    private func statusBackground(_ tone: OpenCodeListItemStatusTone) -> Color {
        guard enabled else { return OpenCodeColors.surfaceRaisedBase }
        switch tone {
        case .interactive:
            return OpenCodeColors.interactive.opacity(0.12)
        case .success:
            return OpenCodeColors.success.opacity(0.12)
        case .warning:
            return OpenCodeColors.warning.opacity(0.12)
        case .error:
            return OpenCodeColors.error.opacity(0.12)
        case .info:
            return OpenCodeColors.textStrong.opacity(0.10)
        }
    }

    @ViewBuilder
    private var containerBackground: some View {
        switch (style, selected) {
        case (.plain, false):
            Color.clear
        case (.plain, true), (.card, true):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceRaisedBase)
        case (.card, false):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceBase)
        }
    }

    @ViewBuilder
    private var startDecoratorContent: some View {
        switch startDecorator {
        case .none:
            EmptyView()
        case let .letter(character, accent, statusDot):
            ZStack(alignment: .bottomTrailing) {
                Text(normalizedBadgeLetter(character))
                    .font(.system(size: listItemLetterGlyphSize, weight: .bold, design: .rounded))
                    .foregroundStyle(accent.badgeTint)
                    .frame(width: listItemLetterBadgeSize, height: listItemLetterBadgeSize)
                    .background(accent.badgeFill, in: Circle())

                if let statusDot {
                    Circle()
                        .fill(statusForeground(statusDot))
                        .frame(width: listItemLetterStatusDotSize, height: listItemLetterStatusDotSize)
                        .overlay(
                            Circle()
                                .stroke(OpenCodeColors.backgroundBase, lineWidth: 2)
                        )
                        .offset(x: 1, y: 1)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityHidden(true)
        case let .icon(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: listItemIconDecoratorSize)
                .foregroundStyle(OpenCodeColors.iconStrongBase)
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(accessibilityLabel ?? "")
        case let .iconCircle(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: listItemIconCircleGlyphSize)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: listItemLetterBadgeSize, height: listItemLetterBadgeSize)
                .background(OpenCodeColors.textStrong.opacity(0.10), in: Circle())
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(accessibilityLabel ?? "")
        }
    }
}

private func normalizedBadgeLetter(_ character: Character) -> String {
    let upper = String(character).uppercased(with: .current)
    guard let first = upper.first else { return "?" }
    return first.isLetter || first.isNumber ? String(first) : "?"
}

private struct OpenCodeListItemPressStyle: ButtonStyle {
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if configuration.isPressed && enabled {
                    RoundedRectangle(cornerRadius: listItemCornerRadius)
                        .fill(OpenCodeColors.surfaceRaisedBase.opacity(0.92))
                }
            }
            .scaleEffect(configuration.isPressed && enabled ? listItemPressedScale : 1)
            .animation(listItemPressAnimation, value: configuration.isPressed)
    }
}

#if DEBUG
#Preview {
    VStack(spacing: 0) {
        OpenCodeListItem(
            title: "Material Design Icons integration plan",
            subtitle: "http://127.0.0.1:4096/",
            status: OpenCodeListItemStatus(text: "Working · 14m", tone: .interactive),
            caption: "sidebar-v2-concepts",
            startDecorator: .letter(Character("A"), .violet)
        )
        OpenCodeListItem(
            title: "contact.html",
            subtitleSegments: [
                .init(text: "Modified · ", tone: .secondary),
                .init(text: "+51", tone: .addition),
                .init(text: " ", tone: .secondary),
                .init(text: "−7", tone: .deletion)
            ]
        )
        OpenCodeListItem(
            title: "Sidebar v2 UX Redesign",
            subtitle: "~/Projects/opencode-mobile",
            status: OpenCodeListItemStatus(text: "Running", tone: .interactive),
            caption: "now",
            startDecorator: .letter(Character("t"), .blue)
        )
        OpenCodeListItem(
            title: "Retrying session",
            subtitle: "~/Projects/opencode-mobile",
            status: OpenCodeListItemStatus(text: "Retrying · 2", tone: .warning),
            caption: "now",
            startDecorator: .letter(Character("R"), .amber)
        )
        OpenCodeListItem(
            title: "Idle session",
            subtitle: "~/Projects/playground",
            status: OpenCodeListItemStatus(text: "Idle", tone: .info),
            caption: "14m ago",
            startDecorator: .letter(Character("P"), .teal)
        )
        OpenCodeListItem(
            title: "All projects",
            subtitle: "8 projects",
            startDecorator: .iconCircle(.widgets)
        )
        OpenCodeListItem(
            title: "Card style with border",
            subtitle: "http://127.0.0.1:4096/",
            style: .card
        )
    }
    .openCodeTheme()
}
#endif
