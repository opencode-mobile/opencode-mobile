import SwiftUI

/// The Session Composer shell for each `SessionComposerStyle`. A permission or error
/// `statusOutline` takes priority over the style's own edge.
struct OpenCodeSessionComposerChrome: ViewModifier {
    let style: SessionComposerStyle
    let floatingCornerRadius: CGFloat
    let statusOutline: Color?
    /// Docked only: how far the fill runs below the shell, through the bottom safe area and
    /// behind the keyboard.
    let dockedBottomInset: CGFloat

    func body(content: Content) -> some View {
        if style == .docked {
            let shape = UnevenRoundedRectangle(
                topLeadingRadius: Self.dockedCornerRadius,
                bottomLeadingRadius: 0,
                bottomTrailingRadius: 0,
                topTrailingRadius: Self.dockedCornerRadius,
                style: .circular
            )
            content
                .clipShape(shape)
                .background {
                    shape
                        .fill(OpenCodeColors.surfaceRaisedBase)
                        .padding(.bottom, -dockedBottomInset)
                }
                .background(alignment: .top) { dockedTopShadow }
                .overlay {
                    // Only a permission request keeps a colored edge.
                    if let statusOutline {
                        OpenCodeDockedTopEdge(cornerRadius: Self.dockedCornerRadius)
                            .stroke(statusOutline, lineWidth: 1)
                            .allowsHitTesting(false)
                    }
                }
        } else {
            content
                .clipShape(RoundedRectangle(cornerRadius: floatingCornerRadius, style: .continuous))
                .openCodeFloatingChrome(cornerRadius: floatingCornerRadius)
                .overlay {
                    RoundedRectangle(cornerRadius: floatingCornerRadius, style: .continuous)
                        .stroke(statusOutline ?? OpenCodeColors.sessionComposerEdge, lineWidth: 1)
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 4)
        }
    }

    /// Soft shadow above the shell. Runs under the rounded corners so they show no lighter notch.
    private var dockedTopShadow: some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [.black.opacity(0), .black.opacity(Self.dockedShadowOpacity)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: Self.dockedShadowHeight)
            Color.black.opacity(Self.dockedShadowOpacity)
                .frame(height: Self.dockedCornerRadius)
        }
        .offset(y: -Self.dockedShadowHeight)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private static let dockedCornerRadius: CGFloat = 22
    private static let dockedShadowHeight: CGFloat = 14
    private static let dockedShadowOpacity = 0.1
}

/// The top edge of the docked shell, following its rounded corners.
private struct OpenCodeDockedTopEdge: Shape {
    let cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        let inset: CGFloat = 0.5
        let left = rect.minX + inset
        let right = rect.maxX - inset
        let top = rect.minY + inset
        let radius = max(cornerRadius - inset, 0)
        var path = Path()
        path.move(to: CGPoint(x: left, y: rect.minY + cornerRadius))
        path.addArc(
            tangent1End: CGPoint(x: left, y: top),
            tangent2End: CGPoint(x: rect.minX + cornerRadius, y: top),
            radius: radius
        )
        path.addLine(to: CGPoint(x: rect.maxX - cornerRadius, y: top))
        path.addArc(
            tangent1End: CGPoint(x: right, y: top),
            tangent2End: CGPoint(x: right, y: rect.minY + cornerRadius),
            radius: radius
        )
        return path
    }
}

/// Dims the timeline around and just above the composer: `background-base` fades in over the
/// fade height, then stays translucent to the bottom edge so the timeline remains visible as it
/// scrolls past. The fade grows while the reader is away from the end. Never takes touches.
struct OpenCodeSessionComposerScrim: View {
    /// Height of the dimmed area, measured from the bottom edge to the composer's top.
    let solidHeight: CGFloat
    var awayFromEnd = false

    var body: some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [OpenCodeColors.backgroundBase.opacity(0), OpenCodeColors.backgroundBase.opacity(Self.opacity)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: Self.fadeHeight(awayFromEnd: awayFromEnd))
            OpenCodeColors.backgroundBase.opacity(Self.opacity)
                .frame(height: max(solidHeight, 0))
        }
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .animation(.easeOut(duration: 0.2), value: awayFromEnd)
    }

    private static let opacity = 0.7

    /// At the end the fade fits the resting gap, so it adds no space.
    nonisolated static func fadeHeight(awayFromEnd: Bool) -> CGFloat {
        awayFromEnd ? 46 : 16
    }
}
