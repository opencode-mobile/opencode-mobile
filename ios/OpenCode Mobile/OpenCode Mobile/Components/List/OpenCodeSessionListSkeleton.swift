import SwiftUI

private let skeletonRowVerticalPadding: CGFloat = 12
private let skeletonRowHorizontalPadding: CGFloat = 16
private let skeletonAvatarSize: CGFloat = 32
private let skeletonAvatarOpacity: Double = 0.35
private let skeletonTitleHeight: CGFloat = 13
private let skeletonSubtitleHeight: CGFloat = 10
private let skeletonLineSpacing: CGFloat = 7
private let skeletonTextBlockHeight: CGFloat = 38
private let skeletonPillSize = CGSize(width: 44, height: 18)
private let skeletonRowFadeStep: Double = 0.12
private let skeletonShimmerCycle: Double = 1.4
private let skeletonShimmerHalfWidth: Double = 0.35
/// Title and subtitle widths per row, as fractions of the text column, so the rows do not
/// read as one repeated block.
private let skeletonLineWidths: [(title: CGFloat, subtitle: CGFloat)] = [
    (0.72, 0.46), (0.58, 0.52), (0.80, 0.40), (0.64, 0.50), (0.50, 0.44), (0.70, 0.48),
]

/// Placeholder shaped like a session row (letter avatar, title, subtitle, trailing status pill)
/// while a project's sessions are loading.
struct OpenCodeSessionRowSkeleton: View {
    /// Tints the avatar with the project's letter colour; nil uses a neutral fill.
    let accent: LetterAccent?
    /// Picks one of the line-width patterns so neighbouring rows differ.
    var variant: Int = 0

    var body: some View {
        OpenCodeSkeletonShimmer { phase in
            OpenCodeSessionRowSkeletonContent(accent: accent, variant: variant, phase: phase)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading session")
    }
}

/// Session-list loading placeholder: `rowCount` row skeletons fading down the list, with an
/// optional quiet caption under them. Static when Reduce Motion is on.
struct OpenCodeSessionListSkeleton: View {
    static let defaultRowCount = 6
    static let defaultAccessibilityLabel = "Loading sessions"

    let rowCount: Int
    let accent: LetterAccent?
    let caption: String?

    init(rowCount: Int = Self.defaultRowCount, accent: LetterAccent? = nil, caption: String? = nil) {
        self.rowCount = max(rowCount, 0)
        self.accent = accent
        self.caption = caption
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeSkeletonShimmer { phase in
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(0 ..< rowCount, id: \.self) { index in
                        OpenCodeSessionRowSkeletonContent(accent: accent, variant: index, phase: phase)
                            .opacity(max(1 - Double(index) * skeletonRowFadeStep, 0.2))
                    }
                }
            }
            if let caption, !caption.isEmpty {
                Text(caption)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .multilineTextAlignment(.leading)
                    .padding(.horizontal, skeletonRowHorizontalPadding)
                    .padding(.top, 8)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(caption.map { "\(Self.defaultAccessibilityLabel). \($0)" } ?? Self.defaultAccessibilityLabel)
        .accessibilityIdentifier("sessionListSkeleton")
    }
}

/// Drives one shared shimmer phase (0…1) for every bar inside; nil under Reduce Motion.
private struct OpenCodeSkeletonShimmer<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ViewBuilder let content: (Double?) -> Content

    var body: some View {
        if reduceMotion {
            content(nil)
        } else {
            TimelineView(.animation) { context in
                content(
                    context.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: skeletonShimmerCycle) / skeletonShimmerCycle
                )
            }
        }
    }
}

private struct OpenCodeSessionRowSkeletonContent: View {
    let accent: LetterAccent?
    let variant: Int
    let phase: Double?

    private var widths: (title: CGFloat, subtitle: CGFloat) {
        skeletonLineWidths[abs(variant) % skeletonLineWidths.count]
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Circle()
                .fill(accent?.badgeFill ?? OpenCodeColors.textStrong.opacity(0.10))
                .opacity(accent == nil ? 1 : skeletonAvatarOpacity)
                .frame(width: skeletonAvatarSize, height: skeletonAvatarSize)

            GeometryReader { geometry in
                VStack(alignment: .leading, spacing: skeletonLineSpacing) {
                    bar(width: geometry.size.width * widths.title, height: skeletonTitleHeight)
                    bar(width: geometry.size.width * widths.subtitle, height: skeletonSubtitleHeight)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(height: skeletonTextBlockHeight)

            Capsule()
                .fill(fill)
                .frame(width: skeletonPillSize.width, height: skeletonPillSize.height)
        }
        .padding(.horizontal, skeletonRowHorizontalPadding)
        .padding(.vertical, skeletonRowVerticalPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func bar(width: CGFloat, height: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
            .fill(fill)
            .frame(width: max(width, 0), height: height)
    }

    private var fill: AnyShapeStyle {
        let base = OpenCodeColors.textStrong.opacity(0.07)
        guard let phase else { return AnyShapeStyle(base) }
        let highlight = OpenCodeColors.textStrong.opacity(0.14)
        let center = -skeletonShimmerHalfWidth + phase * (1 + 2 * skeletonShimmerHalfWidth)
        return AnyShapeStyle(
            LinearGradient(
                colors: [base, highlight, base],
                startPoint: UnitPoint(x: center - skeletonShimmerHalfWidth, y: 0.5),
                endPoint: UnitPoint(x: center + skeletonShimmerHalfWidth, y: 0.5)
            )
        )
    }
}

#if DEBUG
#Preview("Session List Skeleton") {
    VStack(spacing: 24) {
        OpenCodeSessionRowSkeleton(accent: .violet)
        OpenCodeSessionListSkeleton(
            accent: .teal,
            caption: "Still loading \u{2014} large projects can take a moment"
        )
    }
    .background(OpenCodeColors.backgroundBase)
    .openCodeTheme()
}
#endif
