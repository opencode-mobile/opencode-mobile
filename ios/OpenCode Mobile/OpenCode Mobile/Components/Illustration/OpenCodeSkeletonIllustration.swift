import SwiftUI

enum OpenCodeSkeletonIllustrationTopic: String, CaseIterable, Hashable {
    case unofficial
    case connectForm
    case sessionsLive
}

struct OpenCodeSkeletonIllustration: View {
    let topic: OpenCodeSkeletonIllustrationTopic

    init(topic: OpenCodeSkeletonIllustrationTopic) {
        self.topic = topic
    }

    var body: some View {
        let palette = SkeletonIllustrationPalette.current
        Canvas { context, size in
            let scale = min(size.width / Self.designWidth, size.height / Self.designHeight)
            let offsetX = (size.width - Self.designWidth * scale) / 2
            let offsetY = (size.height - Self.designHeight * scale) / 2
            context.translateBy(x: offsetX, y: offsetY)
            context.scaleBy(x: scale, y: scale)
            context.drawSkeletonIllustration(topic: topic, palette: palette)
        }
        .accessibilityHidden(true)
    }

    private static let designWidth: CGFloat = 336
    private static let designHeight: CGFloat = 186
}

private struct SkeletonIllustrationPalette {
    let backgroundBase: Color
    let surfaceRaisedBase: Color
    let surfaceInteractiveWeak: Color
    let textBase: Color
    let textWeak: Color
    let textWeaker: Color
    let textInteractiveBase: Color
    let borderWeakBase: Color
    let borderWeakerBase: Color
    let iconInvertBase: Color
    let buttonPrimaryBase: Color
    let interactive: Color
    let success: Color

    static var current: SkeletonIllustrationPalette {
        SkeletonIllustrationPalette(
            backgroundBase: OpenCodeColors.backgroundBase,
            surfaceRaisedBase: OpenCodeColors.surfaceRaisedBase,
            surfaceInteractiveWeak: OpenCodeColors.surfaceInteractiveWeak,
            textBase: OpenCodeColors.textBase,
            textWeak: OpenCodeColors.textWeak,
            textWeaker: OpenCodeColors.textWeaker,
            textInteractiveBase: OpenCodeColors.textInteractiveBase,
            borderWeakBase: OpenCodeColors.borderWeakBase,
            borderWeakerBase: OpenCodeColors.borderWeakerBase,
            iconInvertBase: OpenCodeColors.iconInvertBase,
            buttonPrimaryBase: OpenCodeColors.buttonPrimaryBase,
            interactive: OpenCodeColors.interactive,
            success: OpenCodeColors.success
        )
    }
}

private extension GraphicsContext {
    mutating func drawSkeletonIllustration(
        topic: OpenCodeSkeletonIllustrationTopic,
        palette: SkeletonIllustrationPalette
    ) {
        switch topic {
        case .unofficial:
            drawUnofficialSkeleton(palette: palette)
        case .connectForm:
            drawConnectFormSkeleton(palette: palette)
        case .sessionsLive:
            drawSessionsLiveSkeleton(palette: palette)
        }
    }

    mutating func skeletonFrame(palette: SkeletonIllustrationPalette) {
        let path = Path(
            roundedRect: CGRect(x: 48, y: 14, width: 240, height: 210),
            cornerRadius: 18
        )
        fill(path, with: .color(palette.backgroundBase))
        stroke(path, with: .color(palette.borderWeakBase), lineWidth: 1.5)
    }

    mutating func skeletonCard(
        x: CGFloat,
        y: CGFloat,
        w: CGFloat,
        h: CGFloat,
        lit: Bool,
        cornerRadius: CGFloat = 10,
        fillColor: Color? = nil,
        palette: SkeletonIllustrationPalette
    ) {
        let path = Path(
            roundedRect: CGRect(x: x, y: y, width: w, height: h),
            cornerRadius: cornerRadius
        )
        fill(path, with: .color(fillColor ?? palette.surfaceRaisedBase))
        if lit {
            stroke(path, with: .color(palette.interactive), lineWidth: 2)
        } else {
            stroke(path, with: .color(palette.borderWeakerBase), lineWidth: 1)
        }
    }

    mutating func skeletonBar(
        x: CGFloat,
        y: CGFloat,
        w: CGFloat,
        h: CGFloat = 8,
        color: Color? = nil,
        palette: SkeletonIllustrationPalette
    ) {
        let path = Path(
            roundedRect: CGRect(x: x, y: y, width: w, height: h),
            cornerRadius: h / 2
        )
        fill(path, with: .color(color ?? palette.borderWeakBase))
    }

    mutating func skeletonGlow(
        x: CGFloat,
        y: CGFloat,
        w: CGFloat,
        h: CGFloat,
        cornerRadius: CGFloat = 8,
        palette: SkeletonIllustrationPalette
    ) {
        // Glow is inset by -6, so its radius is the inner shape's radius + 6 to stay concentric.
        let path = Path(
            roundedRect: CGRect(x: x - 6, y: y - 6, width: w + 12, height: h + 12),
            cornerRadius: cornerRadius + 6
        )
        fill(path, with: .color(palette.interactive.opacity(0.12)))
    }

    mutating func skeletonSpotlight(
        x: CGFloat,
        y: CGFloat,
        w: CGFloat,
        h: CGFloat,
        cornerRadius: CGFloat = 10,
        fillColor: Color? = nil,
        palette: SkeletonIllustrationPalette
    ) {
        skeletonGlow(x: x, y: y, w: w, h: h, cornerRadius: cornerRadius, palette: palette)
        skeletonCard(
            x: x, y: y, w: w, h: h,
            lit: true,
            cornerRadius: cornerRadius,
            fillColor: fillColor,
            palette: palette
        )
    }

    mutating func skeletonButton(
        x: CGFloat,
        y: CGFloat,
        w: CGFloat,
        h: CGFloat,
        title: String,
        palette: SkeletonIllustrationPalette
    ) {
        let rect = CGRect(x: x, y: y, width: w, height: h)
        fill(Path(roundedRect: rect, cornerRadius: 8), with: .color(palette.buttonPrimaryBase))
        drawSkeletonCenteredText(
            title,
            in: rect,
            size: 11,
            weight: .bold,
            color: palette.iconInvertBase
        )
    }

    mutating func skeletonAvatar(
        cx: CGFloat,
        cy: CGFloat,
        live: Bool,
        palette: SkeletonIllustrationPalette
    ) {
        let avatarPath = Path(
            ellipseIn: CGRect(x: cx - 12, y: cy - 12, width: 24, height: 24)
        )
        if live {
            fill(avatarPath, with: .color(palette.surfaceInteractiveWeak))
            let dotPath = Path(
                ellipseIn: CGRect(x: cx + 9 - 4, y: cy + 9 - 4, width: 8, height: 8)
            )
            fill(dotPath, with: .color(palette.success))
            stroke(dotPath, with: .color(palette.surfaceRaisedBase), lineWidth: 2)
        } else {
            fill(avatarPath, with: .color(palette.borderWeakBase))
        }
    }

    mutating func skeletonLockGlyph(
        cx: CGFloat,
        cy: CGFloat,
        palette: SkeletonIllustrationPalette
    ) {
        let body = Path(
            roundedRect: CGRect(x: cx - 5, y: cy - 1.5, width: 10, height: 7),
            cornerRadius: 1.5
        )
        fill(body, with: .color(palette.textWeak))

        var shackle = Path()
        shackle.addArc(
            center: CGPoint(x: cx, y: cy - 1.5),
            radius: 3,
            startAngle: .degrees(180),
            endAngle: .degrees(360),
            clockwise: false
        )
        stroke(shackle, with: .color(palette.textWeak), lineWidth: 1.5)
    }

    mutating func drawUnofficialSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonBar(x: 64, y: 32, w: 90, h: 10, palette: palette)
        skeletonBar(x: 64, y: 48, w: 60, h: 6, palette: palette)
        let pill = CGRect(x: 64, y: 64, width: 118, height: 26)
        skeletonSpotlight(
            x: pill.minX, y: pill.minY, w: pill.width, h: pill.height,
            cornerRadius: pill.height / 2,
            fillColor: palette.surfaceInteractiveWeak,
            palette: palette
        )
        drawSkeletonCenteredText(
            "UNOFFICIAL",
            in: pill,
            size: 11,
            weight: .bold,
            color: palette.textInteractiveBase
        )
        skeletonCard(x: 64, y: 104, w: 208, h: 60, lit: false, palette: palette)
        skeletonBar(x: 76, y: 116, w: 120, h: 7, palette: palette)
        skeletonBar(x: 76, y: 130, w: 160, h: 7, palette: palette)
        skeletonBar(x: 76, y: 144, w: 90, h: 7, palette: palette)
    }

    mutating func drawConnectFormSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonBar(x: 64, y: 32, w: 110, h: 10, palette: palette)
        skeletonCard(x: 64, y: 54, w: 208, h: 32, lit: false, palette: palette)
        draw(
            Text("https://")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(palette.textWeak),
            at: CGPoint(x: 76, y: 70),
            anchor: .leading
        )
        skeletonBar(x: 118, y: 68, w: 90, h: 8, palette: palette)
        skeletonCard(x: 64, y: 94, w: 208, h: 32, lit: false, palette: palette)
        skeletonLockGlyph(cx: 78, cy: 110, palette: palette)
        for dotX in [CGFloat(100), 112, 124, 136] {
            let dot = Path(
                ellipseIn: CGRect(x: dotX - 3.5, y: 110 - 3.5, width: 7, height: 7)
            )
            fill(dot, with: .color(palette.textWeaker))
        }
        skeletonGlow(x: 64, y: 138, w: 208, h: 34, palette: palette)
        skeletonButton(x: 64, y: 138, w: 208, h: 34, title: "Connect", palette: palette)
    }

    mutating func drawSessionsLiveSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonBar(x: 64, y: 30, w: 70, h: 10, palette: palette)
        fill(
            Path(ellipseIn: CGRect(x: 70 - 3, y: 50 - 3, width: 6, height: 6)),
            with: .color(palette.success)
        )
        skeletonBar(x: 78, y: 47, w: 22, h: 5, palette: palette)
        skeletonAvatar(cx: 80, cy: 80, live: false, palette: palette)
        skeletonBar(x: 100, y: 72, w: 100, h: 7, palette: palette)
        skeletonBar(x: 100, y: 85, w: 70, h: 5, palette: palette)
        skeletonSpotlight(x: 60, y: 104, w: 216, h: 40, palette: palette)
        skeletonAvatar(cx: 80, cy: 124, live: true, palette: palette)
        skeletonBar(x: 100, y: 116, w: 110, h: 7, color: palette.textBase, palette: palette)
        skeletonBar(x: 100, y: 129, w: 80, h: 5, palette: palette)
        let running = CGRect(x: 222, y: 115, width: 44, height: 18)
        fill(
            Path(roundedRect: running, cornerRadius: 9),
            with: .color(palette.surfaceInteractiveWeak)
        )
        drawSkeletonCenteredText(
            "Running",
            in: running,
            size: 10,
            weight: .bold,
            color: palette.textInteractiveBase
        )
        skeletonAvatar(cx: 80, cy: 168, live: false, palette: palette)
        skeletonBar(x: 100, y: 160, w: 90, h: 7, palette: palette)
        skeletonBar(x: 100, y: 173, w: 60, h: 5, palette: palette)
    }

    mutating func drawSkeletonCenteredText(
        _ string: String,
        in rect: CGRect,
        size: CGFloat,
        weight: Font.Weight,
        color: Color
    ) {
        draw(
            Text(string)
                .font(.system(size: size, weight: weight))
                .foregroundColor(color),
            at: CGPoint(x: rect.midX, y: rect.midY),
            anchor: .center
        )
    }
}

#Preview("Skeleton Illustration") {
    VStack(spacing: 16) {
        ForEach(OpenCodeSkeletonIllustrationTopic.allCases, id: \.self) { topic in
            OpenCodeSkeletonIllustration(topic: topic)
                .frame(height: 176)
                .clipShape(RoundedRectangle(cornerRadius: 20))
        }
    }
    .padding()
    .openCodeTheme()
}
