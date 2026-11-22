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
                .overlay {
                    OpenCodeDockedTopEdge(cornerRadius: Self.dockedCornerRadius)
                        .stroke(statusOutline ?? OpenCodeColors.sessionComposerEdge, lineWidth: 1)
                        .allowsHitTesting(false)
                }
        } else {
            content
                .clipShape(RoundedRectangle(cornerRadius: floatingCornerRadius, style: .continuous))
                .openCodeFloatingChrome(cornerRadius: floatingCornerRadius)
                .overlay {
                    if let outline = statusOutline ?? (style == .outlineFade ? OpenCodeColors.sessionComposerEdge : nil) {
                        RoundedRectangle(cornerRadius: floatingCornerRadius, style: .continuous)
                            .stroke(outline, lineWidth: 1)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 4)
        }
    }

    private static let dockedCornerRadius: CGFloat = 22
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

/// Hides the timeline behind and just above the composer: `background-base` fades in
/// over `fadeHeight`, then stays solid to the bottom edge. Never takes touches.
struct OpenCodeSessionComposerScrim: View {
    /// Height of the solid area, measured from the bottom edge to the composer's top.
    let solidHeight: CGFloat

    var body: some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [OpenCodeColors.backgroundBase.opacity(0), OpenCodeColors.backgroundBase],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: Self.fadeHeight)
            OpenCodeColors.backgroundBase
                .frame(height: max(solidHeight, 0))
        }
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    static let fadeHeight: CGFloat = 46
    /// Extra list clearance so the last message rests above the fade, not inside it.
    static let restingClearance: CGFloat = fadeHeight - OpenCodeBottomFade.overhang
}
