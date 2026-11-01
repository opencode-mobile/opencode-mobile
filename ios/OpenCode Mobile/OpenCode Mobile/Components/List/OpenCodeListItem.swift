import SwiftUI

private let listItemCornerRadius: CGFloat = 8
/// Plain-style selection and press highlights are inset from the row edges so they
/// read as a compact rounded highlight; the row frame and tap target are unchanged.
private let listItemSelectionHorizontalInset: CGFloat = 8
private let listItemSelectionVerticalInset: CGFloat = 2
private let listItemSelectionCornerRadius: CGFloat = 12
private let listItemLetterBadgeSize: CGFloat = 32
private let listItemLetterGlyphSize: CGFloat = 14
private let listItemLetterStatusDotSize: CGFloat = 10
private let listItemIconDecoratorSize: CGFloat = 24
private let listItemIconCircleGlyphSize: CGFloat = 16
private let listItemSubtitleIconSize: CGFloat = 12
private let listItemVerticalPadding: CGFloat = 12
private let listItemTitleSubtitleSpacing: CGFloat = 2
private let listItemMetaSpacing: CGFloat = 4
private let listItemCompactBadgeSize: CGFloat = 32
private let listItemCompactGlyphSize: CGFloat = 13
private let listItemCompactStatusDotSize: CGFloat = 8
private let listItemCompactStatusRingWidth: CGFloat = 2.5
/// Moves the ringed dot from the frame corner onto the badge's edge at 45 degrees.
private let listItemCompactStatusDotOffset: CGFloat = 2
private let listItemCompactHeight: CGFloat = 66
private let listItemCompactLineSpacing: CGFloat = 5
private let listItemCompactTitleFont = Font.system(size: 16, weight: .medium)
private let listItemCompactEmphasizedTitleFont = Font.system(size: 16, weight: .semibold)
private let listItemCompactPathFont = Font.system(size: 13.5, weight: .regular)
private let listItemCompactPathSpacing: CGFloat = 5
/// Same size as the path beside it, one weight up.
private let listItemCompactStatusFont = Font.system(size: 13.5, weight: .semibold)
private let listItemPulseDuration: Double = 1.2
private let listItemPulseMinOpacity: Double = 0.3
private let listItemPressedScale: CGFloat = 0.965
private let listItemPopPeakScale: CGFloat = 1.035
private let listItemPressAnimation = Animation.easeOut(duration: 0.12)
private let listItemPopAnimation = Animation.spring(response: 0.28, dampingFraction: 0.52)
private let listItemPopSettleAnimation = Animation.spring(response: 0.34, dampingFraction: 0.72)

enum OpenCodeListItemStyle {
    case plain
    case card
}

enum OpenCodeListItemVariant {
    /// Title, then up to two lines of subtitle and detail, with time and status stacked on the right.
    case standard
    /// Fixed two-line row: title and caption, then a path and a coloured status label.
    case compact
}

/// Line 2 of a compact row: `primary / secondary`. When both do not fit, the primary keeps its
/// width if it fits in 40% of the line, else the secondary keeps its width if it fits in 60%,
/// else they split 40/60. The secondary is cut in the middle.
struct OpenCodeListItemPath: Equatable {
    let primary: String
    let secondary: String?

    init(primary: String, secondary: String? = nil) {
        self.primary = primary
        self.secondary = secondary?.isEmpty == true ? nil : secondary
    }
}

/// Width sharing of an `OpenCodeListItemPath` line.
nonisolated enum OpenCodeListItemPathSplit {
    static let primaryShare: CGFloat = 0.4
    static let secondaryShare: CGFloat = 0.6

    /// Width given to the primary part; the secondary takes what is left.
    static func primaryWidth(primary: CGFloat, secondary: CGFloat, available: CGFloat) -> CGFloat {
        guard available > 0 else { return 0 }
        if primary + secondary <= available { return primary }
        if primary <= available * primaryShare { return primary }
        if secondary <= available * secondaryShare { return available - secondary }
        return available * primaryShare
    }
}

enum OpenCodeListItemSubtitleTone {
    case secondary
    /// Quieter than `secondary`, for the detail line under the subtitle.
    case tertiary
    case addition
    case deletion
}

struct OpenCodeListItemSubtitleSegment: Equatable {
    let text: String
    let tone: OpenCodeListItemSubtitleTone
    /// Small glyph drawn inline before `text`, such as the branch icon.
    let icon: OpenCodeIcon?

    init(text: String, tone: OpenCodeListItemSubtitleTone, icon: OpenCodeIcon? = nil) {
        self.text = text
        self.tone = tone
        self.icon = icon
    }
}

enum OpenCodeListItemStatusTone: Equatable {
    case interactive
    case success
    case warning
    case error
    case info
    case stale
}

struct OpenCodeListItemStatus: Equatable {
    let text: OpenCodeText
    let tone: OpenCodeListItemStatusTone
}

/// Built-in trailing control. Custom trailing views stay available for anything else.
enum OpenCodeListItemAccessory {
    /// Navigation chevron. The row action owns the tap.
    case disclosure
    /// Switch. The row should not also take a tap action.
    case toggle(isOn: Binding<Bool>, accessibilityLabel: OpenCodeText)
}

struct OpenCodeListItem<Trailing: View>: View {
    let variant: OpenCodeListItemVariant
    let title: OpenCodeText
    /// Compact rows: semibold title, such as an unread finish. Standard titles are always semibold.
    let emphasized: Bool
    let subtitle: OpenCodeText?
    let subtitleSegments: [OpenCodeListItemSubtitleSegment]?
    /// Optional third line under the subtitle, such as a branch. Truncates on its own.
    let detailSegments: [OpenCodeListItemSubtitleSegment]?
    /// Compact rows: line 2 as `primary / secondary`, in place of the subtitle.
    let path: OpenCodeListItemPath?
    let status: OpenCodeListItemStatus?
    let caption: OpenCodeText?
    /// Small tag after the title, such as "git". The title truncates first; the tag stays.
    let titleTag: OpenCodeText?
    let selected: Bool
    let enabled: Bool
    let style: OpenCodeListItemStyle
    let startDecorator: StartDecorator
    let accessory: OpenCodeListItemAccessory?
    let action: (() -> Void)?
    let onLongPress: (() -> Void)?
    @ViewBuilder let trailing: () -> Trailing

    @State private var isPressing = false
    @State private var popScale: CGFloat = 1

    init(
        variant: OpenCodeListItemVariant = .standard,
        title: OpenCodeText,
        emphasized: Bool = false,
        subtitle: OpenCodeText? = nil,
        subtitleSegments: [OpenCodeListItemSubtitleSegment]? = nil,
        detailSegments: [OpenCodeListItemSubtitleSegment]? = nil,
        path: OpenCodeListItemPath? = nil,
        status: OpenCodeListItemStatus? = nil,
        caption: OpenCodeText? = nil,
        titleTag: OpenCodeText? = nil,
        selected: Bool = false,
        enabled: Bool = true,
        style: OpenCodeListItemStyle = .plain,
        startDecorator: StartDecorator = .none,
        accessory: OpenCodeListItemAccessory? = nil,
        action: (() -> Void)? = nil,
        onLongPress: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        precondition(subtitle == nil || subtitleSegments == nil, "List Item accepts either a plain subtitle or semantic segments")
        self.variant = variant
        self.title = title
        self.emphasized = emphasized
        self.subtitle = subtitle
        self.subtitleSegments = subtitleSegments
        self.detailSegments = detailSegments?.isEmpty == true ? nil : detailSegments
        self.path = path
        self.status = status
        self.caption = caption
        self.titleTag = titleTag?.isEmpty == true ? nil : titleTag
        self.selected = selected
        self.enabled = enabled
        self.style = style
        self.startDecorator = startDecorator
        self.accessory = accessory
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
                    .accessibilityAddTraits(.isButton)
            } else if let action {
                Button(action: action) {
                    content
                }
                .buttonStyle(OpenCodeListItemPressStyle(enabled: enabled, style: style))
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
            listItemHighlightShape(style: style)
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
        rowContent
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .padding(.horizontal, 16)
            .padding(.vertical, variant == .compact ? 0 : listItemVerticalPadding)
            .frame(height: variant == .compact ? listItemCompactHeight : nil)
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

    @ViewBuilder
    private var rowContent: some View {
        switch variant {
        case .standard: standardContent
        case .compact: compactContent
        }
    }

    private var compactContent: some View {
        HStack(alignment: .center, spacing: 12) {
            startDecoratorContent

            VStack(alignment: .leading, spacing: listItemCompactLineSpacing) {
                HStack(spacing: 8) {
                    Text(openCode: title)
                        .font(emphasized ? listItemCompactEmphasizedTitleFont : listItemCompactTitleFont)
                        .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let caption, !caption.isEmpty {
                        Text(openCode: caption)
                            .font(OpenCodeTypography.caption.monospacedDigit())
                            .foregroundStyle(enabled ? OpenCodeColors.textWeak : OpenCodeColors.textWeaker)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }

                HStack(spacing: 8) {
                    compactSecondLine
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let status {
                        compactStatusLabel(status)
                            .fixedSize()
                    }
                }
            }

            trailing()
                .foregroundStyle(OpenCodeColors.iconStrongBase)

            accessoryView
        }
    }

    @ViewBuilder
    private var compactSecondLine: some View {
        if let path {
            OpenCodeListItemPathLine(path: path, enabled: enabled)
        } else if let subtitleSegments {
            semanticSubtitle(subtitleSegments)
                .font(listItemCompactPathFont)
                .lineLimit(1)
        } else if let subtitle {
            Text(openCode: subtitle)
                .font(listItemCompactPathFont)
                .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                .lineLimit(1)
        }
    }

    private var standardContent: some View {
        HStack(alignment: .center, spacing: 12) {
            startDecoratorContent

            VStack(alignment: .leading, spacing: listItemTitleSubtitleSpacing) {
                HStack(spacing: 6) {
                    Text(openCode: title)
                        .font(OpenCodeTypography.body.weight(.semibold))
                        .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                        .lineLimit(1)
                    if let titleTag {
                        OpenCodeTag(titleTag, size: .small)
                    }
                }

                if let subtitleSegments {
                    semanticSubtitle(subtitleSegments)
                        .font(OpenCodeTypography.caption)
                        .lineLimit(1)
                } else if let subtitle {
                    Text(openCode: subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                        .lineLimit(1)
                }

                if let detailSegments {
                    semanticSubtitle(detailSegments)
                        .font(OpenCodeTypography.caption)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if hasMeta {
                metaColumn
            }

            trailing()
                .foregroundStyle(OpenCodeColors.iconStrongBase)

            accessoryView
        }
    }

    private var metaColumn: some View {
        VStack(alignment: .trailing, spacing: listItemMetaSpacing) {
            if let caption, !caption.isEmpty {
                Text(openCode: caption)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, 2)
            }
            if let status {
                statusPill(status)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private func statusPill(_ status: OpenCodeListItemStatus) -> some View {
        Text(openCode: status.text)
            .font(OpenCodeTypography.caption.weight(.semibold))
            .foregroundStyle(statusForeground(status.tone))
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(statusBackground(status.tone), in: Capsule())
    }

    /// Compact rows show the status as coloured text, without the pill's capsule.
    private func compactStatusLabel(_ status: OpenCodeListItemStatus) -> some View {
        Text(openCode: status.text)
            .font(listItemCompactStatusFont)
            .foregroundStyle(statusForeground(status.tone))
            .lineLimit(1)
    }

    private func semanticSubtitle(_ segments: [OpenCodeListItemSubtitleSegment]) -> Text {
        segments.reduce(Text("")) { result, segment in
            let color = subtitleColor(segment.tone)
            var text = result
            if let icon = segment.icon, let image = OpenCodeInlineIcon.image(icon, pointSize: listItemSubtitleIconSize) {
                text = text + Text(image).foregroundColor(color).baselineOffset(-1) + Text("\u{2009}")
            }
            return text + Text(segment.text).foregroundColor(color)
        }
    }

    private func subtitleColor(_ tone: OpenCodeListItemSubtitleTone) -> Color {
        guard enabled else { return OpenCodeColors.textWeaker }
        switch tone {
        case .secondary:
            return OpenCodeColors.textBase
        case .tertiary:
            return OpenCodeColors.textWeak
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
        case .stale:
            return OpenCodeColors.textWeaker
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
        case .stale:
            return OpenCodeColors.textStrong.opacity(0.06)
        }
    }

    @ViewBuilder
    private var containerBackground: some View {
        switch (style, selected) {
        case (.plain, false):
            Color.clear
        case (.plain, true), (.card, true):
            listItemHighlightShape(style: style)
                .fill(OpenCodeColors.surfaceRaisedBase)
        case (.card, false):
            RoundedRectangle(cornerRadius: listItemCornerRadius)
                .fill(OpenCodeColors.surfaceBase)
        }
    }

    @ViewBuilder
    private var accessoryView: some View {
        switch accessory {
        case .none:
            EmptyView()
        case .disclosure:
            OpenCodeIconImage(icon: .chevronRight, size: 18)
                .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                .accessibilityHidden(true)
        case let .toggle(isOn, accessibilityLabel):
            Toggle(isOn: isOn) { Text(openCode: accessibilityLabel) }
                .labelsHidden()
                .tint(OpenCodeColors.interactive)
                .disabled(!enabled)
        }
    }

    @ViewBuilder
    private var startDecoratorContent: some View {
        switch startDecorator {
        case .none:
            EmptyView()
        case let .letter(character, accent, statusDot, worktree):
            ZStack {
                letterBadge(character, accent, size: listItemLetterBadgeSize, glyphSize: listItemLetterGlyphSize)

                if let statusDot, worktree {
                    OpenCodeIconImage(icon: .callSplit, size: 10)
                        .foregroundStyle(OpenCodeColors.backgroundBase)
                        .frame(width: listItemLetterStatusDotSize + 4, height: listItemLetterStatusDotSize + 4)
                        .background(statusForeground(statusDot), in: Circle())
                        .overlay(Circle().stroke(OpenCodeColors.backgroundBase, lineWidth: 2))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .offset(x: 1, y: 1)
                        .accessibilityLabel("Worktree")
                } else {
                    if let statusDot {
                        Circle()
                            .fill(statusForeground(statusDot))
                            .frame(width: listItemLetterStatusDotSize, height: listItemLetterStatusDotSize)
                            .overlay(
                                Circle()
                                    .stroke(OpenCodeColors.backgroundBase, lineWidth: 2)
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                            .offset(x: 1, y: 1)
                            .accessibilityHidden(true)
                    }
                    if worktree {
                        OpenCodeIconImage(icon: .callSplit, size: 10)
                            .foregroundStyle(OpenCodeColors.iconStrongBase)
                            .frame(width: listItemLetterStatusDotSize + 4, height: listItemLetterStatusDotSize + 4)
                            .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
                            .overlay(Circle().stroke(OpenCodeColors.backgroundBase, lineWidth: 2))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                            .offset(x: 1, y: 1)
                            .accessibilityLabel("Worktree")
                    }
                }
            }
            .frame(width: listItemLetterBadgeSize, height: listItemLetterBadgeSize)
        case let .compactLetter(character, accent, statusDot, pulsing):
            letterBadge(character, accent, size: listItemCompactBadgeSize, glyphSize: listItemCompactGlyphSize)
                .overlay(alignment: .topTrailing) {
                    if let statusDot {
                        OpenCodeListItemCompactStatusDot(color: statusForeground(statusDot), pulsing: pulsing)
                            .offset(x: listItemCompactStatusDotOffset, y: -listItemCompactStatusDotOffset)
                    }
                }
        case let .icon(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: listItemIconDecoratorSize)
                .foregroundStyle(OpenCodeColors.iconStrongBase)
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(openCode: accessibilityLabel ?? .empty)
        case let .iconCircle(icon, accessibilityLabel):
            OpenCodeIconImage(icon: icon, size: listItemIconCircleGlyphSize)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: listItemLetterBadgeSize, height: listItemLetterBadgeSize)
                .background(OpenCodeColors.textStrong.opacity(0.10), in: Circle())
                .accessibilityHidden(accessibilityLabel == nil)
                .accessibilityLabel(openCode: accessibilityLabel ?? .empty)
        }
    }
}

/// Status dot on a compact badge's top-right edge, ringed in the background colour. Pulsing fades it from full to
/// 30% and back every 2.4s, and holds it still under Reduce Motion.
private struct OpenCodeListItemCompactStatusDot: View {
    let color: Color
    let pulsing: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    private var animates: Bool { pulsing && !reduceMotion }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: listItemCompactStatusDotSize, height: listItemCompactStatusDotSize)
            .opacity(animates && dimmed ? listItemPulseMinOpacity : 1)
            .padding(listItemCompactStatusRingWidth)
            .background(OpenCodeColors.backgroundBase, in: Circle())
            .accessibilityHidden(true)
            .onAppear { updatePulse() }
            .onChange(of: animates) { _, _ in updatePulse() }
    }

    private func updatePulse() {
        guard animates else {
            var still = Transaction()
            still.disablesAnimations = true
            withTransaction(still) { dimmed = false }
            return
        }
        withAnimation(.easeInOut(duration: listItemPulseDuration).repeatForever(autoreverses: true)) {
            dimmed = true
        }
    }
}

/// `primary / secondary` on one line, shared by `OpenCodeListItemPathSplit`.
private struct OpenCodeListItemPathLine: View {
    let path: OpenCodeListItemPath
    let enabled: Bool

    var body: some View {
        if let secondary = path.secondary {
            OpenCodeListItemPathLayout(spacing: listItemCompactPathSpacing) {
                primaryText
                Text("/")
                    .foregroundStyle(enabled ? OpenCodeColors.textWeak : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .fixedSize()
                Text(secondary)
                    .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .font(listItemCompactPathFont)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(path.primary), \(secondary)")
        } else {
            primaryText
                .font(listItemCompactPathFont)
        }
    }

    private var primaryText: some View {
        Text(path.primary)
            .fontWeight(.medium)
            .foregroundStyle(enabled ? OpenCodeColors.textStrong.opacity(0.8) : OpenCodeColors.textWeaker)
            .lineLimit(1)
    }
}

/// Lays out primary, separator, secondary. The primary gets the width `OpenCodeListItemPathSplit`
/// gives it; the secondary takes the rest of the line.
private struct OpenCodeListItemPathLayout: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 3 else { return .zero }
        let ideal = subviews.map { $0.sizeThatFits(.unspecified) }
        let idealWidth = ideal.reduce(0) { $0 + $1.width } + spacing * 2
        let height = ideal.map(\.height).max() ?? 0
        return CGSize(width: min(proposal.width ?? idealWidth, idealWidth), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 3 else { return }
        let primary = subviews[0]
        let separator = subviews[1]
        let secondary = subviews[2]
        let separatorWidth = separator.sizeThatFits(.unspecified).width
        let available = max(0, bounds.width - separatorWidth - spacing * 2)
        let primaryWidth = OpenCodeListItemPathSplit.primaryWidth(
            primary: primary.sizeThatFits(.unspecified).width,
            secondary: secondary.sizeThatFits(.unspecified).width,
            available: available
        )
        var x = bounds.minX
        let placedPrimary = primary.sizeThatFits(ProposedViewSize(width: primaryWidth, height: bounds.height))
        primary.place(
            at: CGPoint(x: x, y: bounds.midY),
            anchor: .leading,
            proposal: ProposedViewSize(width: min(primaryWidth, placedPrimary.width), height: bounds.height)
        )
        x += min(primaryWidth, placedPrimary.width) + spacing
        separator.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: .unspecified)
        x += separatorWidth + spacing
        secondary.place(
            at: CGPoint(x: x, y: bounds.midY),
            anchor: .leading,
            proposal: ProposedViewSize(width: max(0, bounds.maxX - x), height: bounds.height)
        )
    }
}

/// Round letter badge in the letter accent colours.
private func letterBadge(_ character: Character, _ accent: LetterAccent, size: CGFloat, glyphSize: CGFloat) -> some View {
    Text(normalizedBadgeLetter(character))
        .font(.system(size: glyphSize, weight: .bold, design: .rounded))
        .foregroundStyle(accent.badgeTint)
        .frame(width: size, height: size)
        .background(accent.badgeFill, in: Circle())
        .accessibilityHidden(true)
}

private func normalizedBadgeLetter(_ character: Character) -> String {
    let upper = String(character).uppercased(with: .current)
    guard let first = upper.first else { return "?" }
    return first.isLetter || first.isNumber ? String(first) : "?"
}

/// Selection/press highlight shape. Plain rows inset the highlight inside the row
/// frame; card rows keep the full-bleed rounded container.
private func listItemHighlightShape(style: OpenCodeListItemStyle) -> some InsettableShape {
    switch style {
    case .plain:
        return InsetRoundedHighlight(
            cornerRadius: listItemSelectionCornerRadius,
            horizontalInset: listItemSelectionHorizontalInset,
            verticalInset: listItemSelectionVerticalInset
        )
    case .card:
        return InsetRoundedHighlight(cornerRadius: listItemCornerRadius, horizontalInset: 0, verticalInset: 0)
    }
}

private struct InsetRoundedHighlight: InsettableShape {
    let cornerRadius: CGFloat
    let horizontalInset: CGFloat
    let verticalInset: CGFloat
    var extraInset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let insetRect = rect.insetBy(dx: horizontalInset + extraInset, dy: verticalInset + extraInset)
        return RoundedRectangle(cornerRadius: cornerRadius).path(in: insetRect)
    }

    func inset(by amount: CGFloat) -> InsetRoundedHighlight {
        var copy = self
        copy.extraInset += amount
        return copy
    }
}

private struct OpenCodeListItemPressStyle: ButtonStyle {
    let enabled: Bool
    let style: OpenCodeListItemStyle

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if configuration.isPressed && enabled {
                    listItemHighlightShape(style: style)
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
            title: "Cached running session",
            subtitle: "~/Projects/opencode-mobile",
            status: OpenCodeListItemStatus(text: "Running", tone: .stale),
            caption: "14m ago",
            startDecorator: .letter(Character("C"), .violet)
        )
        OpenCodeListItem(
            title: "Keep split pane in step with the sidebar",
            subtitleSegments: [.init(text: "opencode-mobile-enterprise-edition", tone: .secondary, icon: .folder)],
            detailSegments: [
                .init(text: "feature/ope-1042-show-worktree-and-project-on-home-rows", tone: .tertiary, icon: .callSplit),
                .init(text: " \u{00B7} opencode-mobile-1042", tone: .tertiary)
            ],
            status: OpenCodeListItemStatus(text: "Running", tone: .interactive),
            caption: "13m ago",
            startDecorator: .letter(Character("O"), .red)
        )
        OpenCodeListItem(
            title: "All projects",
            subtitle: "8 projects",
            startDecorator: .iconCircle(.widgets)
        )
        OpenCodeListItem(
            title: "opencode-mobile-357-split-view-chat-lifecycle-cache-android",
            subtitle: "On Home \u{00B7} 12 folders",
            titleTag: "git",
            startDecorator: .icon(.folder)
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

/// Template icons sized for inline use inside `Text`, which draws images at their asset size.
@MainActor
enum OpenCodeInlineIcon {
    private static var cache: [String: Image] = [:]

    static func image(_ icon: OpenCodeIcon, pointSize: CGFloat) -> Image? {
        let key = "\(icon.rawValue)@\(pointSize)"
        if let cached = cache[key] { return cached }
        guard let source = UIImage(named: icon.rawValue) else { return nil }
        let size = CGSize(width: pointSize, height: pointSize)
        let scaled = UIGraphicsImageRenderer(size: size).image { _ in
            source.draw(in: CGRect(origin: .zero, size: size))
        }
        let image = Image(uiImage: scaled.withRenderingMode(.alwaysTemplate))
        cache[key] = image
        return image
    }
}
