import SwiftUI

enum OpenCodeSkeletonIllustrationTopic: String, CaseIterable, Hashable {
    case unofficial
    case connectForm
    case sessionsLive
    case chipTimelineGlance
    case chipTimelineLongRuns
    case chipTimelineDetails
    case timelineStandard
    case timelineChips
    case timelineStepRail
    case timelineActivityCard
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
    let diffAdd: Color
    let diffDelete: Color

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
            success: OpenCodeColors.success,
            diffAdd: OpenCodeColors.diffAdd,
            diffDelete: OpenCodeColors.diffDelete
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
        case .chipTimelineGlance:
            drawChipTimelineGlanceSkeleton(palette: palette)
        case .chipTimelineLongRuns:
            drawChipTimelineLongRunsSkeleton(palette: palette)
        case .chipTimelineDetails:
            drawChipTimelineDetailsSkeleton(palette: palette)
        case .timelineStandard:
            drawTimelineStandardSkeleton(palette: palette)
        case .timelineChips:
            drawChipTimelineGlanceSkeleton(palette: palette)
        case .timelineStepRail:
            drawTimelineStepRailSkeleton(palette: palette)
        case .timelineActivityCard:
            drawTimelineActivityCardSkeleton(palette: palette)
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

    /// A tool chip: icon square plus label bar; `diff` adds green/red count bars.
    mutating func skeletonChip(
        x: CGFloat,
        y: CGFloat,
        w: CGFloat,
        lit: Bool = false,
        diff: Bool = false,
        palette: SkeletonIllustrationPalette
    ) {
        let h: CGFloat = 18
        if lit {
            skeletonSpotlight(x: x, y: y, w: w, h: h, cornerRadius: 6, palette: palette)
        } else {
            skeletonCard(x: x, y: y, w: w, h: h, lit: false, cornerRadius: 6, palette: palette)
        }
        fill(
            Path(roundedRect: CGRect(x: x + 6, y: y + 6, width: 6, height: 6), cornerRadius: 1.5),
            with: .color(palette.borderWeakBase)
        )
        let labelWidth = w - 22 - (diff ? 20 : 0)
        skeletonBar(x: x + 16, y: y + 6, w: labelWidth, h: 6, palette: palette)
        if diff {
            skeletonBar(x: x + w - 24, y: y + 6, w: 8, h: 6, color: palette.diffAdd, palette: palette)
            skeletonBar(x: x + w - 14, y: y + 6, w: 8, h: 6, color: palette.diffDelete, palette: palette)
        }
    }

    /// Narration with its tools as chips underneath; the first chip row is lit.
    mutating func drawChipTimelineGlanceSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        fill(
            Path(roundedRect: CGRect(x: 160, y: 28, width: 112, height: 22), cornerRadius: 11),
            with: .color(palette.surfaceInteractiveWeak)
        )
        skeletonBar(x: 172, y: 36, w: 84, h: 6, palette: palette)
        skeletonBar(x: 64, y: 62, w: 196, h: 7, palette: palette)
        skeletonBar(x: 64, y: 75, w: 138, h: 7, palette: palette)
        skeletonGlow(x: 64, y: 90, w: 206, h: 18, cornerRadius: 6, palette: palette)
        skeletonChip(x: 64, y: 90, w: 62, lit: true, palette: palette)
        skeletonChip(x: 132, y: 90, w: 78, lit: true, diff: true, palette: palette)
        skeletonChip(x: 216, y: 90, w: 54, lit: true, palette: palette)
        skeletonBar(x: 64, y: 124, w: 176, h: 7, palette: palette)
        skeletonBar(x: 64, y: 137, w: 110, h: 7, palette: palette)
        skeletonChip(x: 64, y: 152, w: 70, palette: palette)
        skeletonChip(x: 140, y: 152, w: 58, palette: palette)
    }

    /// A long list of tool rows on the left collapses into a few lit chips on the right.
    mutating func drawChipTimelineLongRunsSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        for index in 0..<8 {
            let y = 30 + CGFloat(index) * 18
            fill(
                Path(roundedRect: CGRect(x: 64, y: y, width: 8, height: 8), cornerRadius: 2),
                with: .color(palette.borderWeakerBase)
            )
            skeletonBar(x: 78, y: y + 1, w: [62, 48, 70, 54, 66, 44, 58, 50][index], h: 6, color: palette.borderWeakerBase, palette: palette)
        }
        var arrow = Path()
        arrow.move(to: CGPoint(x: 156, y: 92))
        arrow.addLine(to: CGPoint(x: 172, y: 92))
        arrow.move(to: CGPoint(x: 166, y: 86))
        arrow.addLine(to: CGPoint(x: 172, y: 92))
        arrow.addLine(to: CGPoint(x: 166, y: 98))
        stroke(arrow, with: .color(palette.textWeaker), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        skeletonBar(x: 184, y: 62, w: 86, h: 7, palette: palette)
        skeletonBar(x: 184, y: 75, w: 60, h: 7, palette: palette)
        skeletonGlow(x: 184, y: 90, w: 86, h: 40, cornerRadius: 6, palette: palette)
        skeletonChip(x: 184, y: 90, w: 46, lit: true, palette: palette)
        skeletonChip(x: 234, y: 90, w: 36, lit: true, palette: palette)
        skeletonChip(x: 184, y: 112, w: 64, lit: true, diff: true, palette: palette)
    }

    /// One lit chip opens its detail: a file header with diff lines.
    mutating func drawChipTimelineDetailsSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonBar(x: 64, y: 30, w: 186, h: 7, palette: palette)
        skeletonBar(x: 64, y: 43, w: 124, h: 7, palette: palette)
        skeletonChip(x: 64, y: 58, w: 62, palette: palette)
        skeletonChip(x: 132, y: 58, w: 84, lit: true, diff: true, palette: palette)
        skeletonCard(x: 64, y: 92, w: 208, h: 86, lit: false, palette: palette)
        skeletonBar(x: 76, y: 102, w: 96, h: 7, palette: palette)
        let lines: [(CGFloat, Color?)] = [(150, nil), (128, palette.diffDelete), (160, palette.diffAdd), (112, palette.diffAdd), (140, nil)]
        for (index, line) in lines.enumerated() {
            let y = 118 + CGFloat(index) * 11
            if let marker = line.1 {
                fill(
                    Path(roundedRect: CGRect(x: 76, y: y, width: 3, height: 6), cornerRadius: 1),
                    with: .color(marker)
                )
            }
            skeletonBar(x: 84, y: y, w: line.0, h: 6, color: line.1?.opacity(0.45), palette: palette)
        }
    }

    /// The user's prompt bubble at the top of a Session Chat skeleton.
    mutating func skeletonUserBubble(palette: SkeletonIllustrationPalette) {
        fill(
            Path(roundedRect: CGRect(x: 160, y: 28, width: 112, height: 22), cornerRadius: 11),
            with: .color(palette.surfaceInteractiveWeak)
        )
        skeletonBar(x: 172, y: 36, w: 84, h: 6, palette: palette)
    }

    mutating func skeletonDot(cx: CGFloat, cy: CGFloat, radius: CGFloat, color: Color) {
        fill(
            Path(ellipseIn: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2)),
            with: .color(color)
        )
    }

    mutating func skeletonChevron(_ points: [CGPoint], palette: SkeletonIllustrationPalette) {
        var path = Path()
        path.addLines(points)
        stroke(
            path,
            with: .color(palette.textWeaker),
            style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round)
        )
    }

    mutating func skeletonDiffBars(x: CGFloat, y: CGFloat, palette: SkeletonIllustrationPalette) {
        skeletonBar(x: x, y: y, w: 8, h: 6, color: palette.diffAdd, palette: palette)
        skeletonBar(x: x + 11, y: y, w: 8, h: 6, color: palette.diffDelete, palette: palette)
    }

    /// Standard timeline: tool calls as one lit group card between the agent's messages.
    mutating func drawTimelineStandardSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonUserBubble(palette: palette)
        skeletonBar(x: 64, y: 62, w: 196, h: 7, palette: palette)
        skeletonBar(x: 64, y: 75, w: 120, h: 7, palette: palette)
        skeletonSpotlight(x: 64, y: 90, w: 208, h: 76, palette: palette)
        skeletonBar(x: 76, y: 99, w: 54, h: 6, color: palette.textWeak, palette: palette)
        skeletonChevron(
            [CGPoint(x: 256, y: 99), CGPoint(x: 260, y: 103), CGPoint(x: 264, y: 99)],
            palette: palette
        )
        fill(Path(CGRect(x: 64, y: 113, width: 208, height: 1)), with: .color(palette.borderWeakerBase))
        let rows: [(y: CGFloat, width: CGFloat, diff: Bool)] = [(121, 92, false), (137, 70, true), (153, 104, false)]
        for row in rows {
            fill(
                Path(roundedRect: CGRect(x: 76, y: row.y, width: 8, height: 8), cornerRadius: 2),
                with: .color(palette.borderWeakBase)
            )
            skeletonBar(x: 90, y: row.y + 1, w: 30, h: 6, color: palette.textWeak, palette: palette)
            skeletonBar(x: 126, y: row.y + 1, w: row.width, h: 6, palette: palette)
            if row.diff {
                skeletonDiffBars(x: 244, y: row.y + 1, palette: palette)
            }
        }
        skeletonBar(x: 64, y: 180, w: 150, h: 7, palette: palette)
    }

    /// Step Rail timeline: one line per step, the running step lit with live time.
    mutating func drawTimelineStepRailSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonUserBubble(palette: palette)
        skeletonBar(x: 64, y: 62, w: 170, h: 7, palette: palette)
        fill(Path(CGRect(x: 70, y: 82, width: 1.5, height: 104)), with: .color(palette.borderWeakBase))
        let railX: CGFloat = 70.75
        let steps: [(y: CGFloat, name: CGFloat, detail: CGFloat, diff: Bool)] = [
            (88, 40, 70, false), (106, 52, 56, false), (124, 34, 84, true),
        ]
        for step in steps {
            skeletonDot(cx: railX, cy: step.y + 3, radius: 4, color: palette.success)
            skeletonBar(x: 82, y: step.y, w: step.name, h: 6, color: palette.textWeak, palette: palette)
            skeletonBar(x: 88 + step.name, y: step.y, w: step.detail, h: 6, palette: palette)
            if step.diff {
                skeletonDiffBars(x: 244, y: step.y, palette: palette)
            }
        }
        skeletonSpotlight(x: 80, y: 140, w: 192, h: 18, cornerRadius: 6, palette: palette)
        skeletonDot(cx: railX, cy: 149, radius: 4, color: palette.interactive)
        stroke(
            Path(ellipseIn: CGRect(x: railX - 7, y: 142, width: 14, height: 14)),
            with: .color(palette.interactive.opacity(0.35)),
            lineWidth: 2
        )
        skeletonBar(x: 88, y: 146, w: 34, h: 6, color: palette.textBase, palette: palette)
        skeletonBar(x: 128, y: 146, w: 80, h: 6, palette: palette)
        skeletonBar(x: 240, y: 146, w: 24, h: 6, color: palette.textWeaker, palette: palette)
        skeletonDot(cx: railX, cy: 175, radius: 4, color: palette.textWeaker)
        skeletonBar(x: 82, y: 172, w: 60, h: 6, color: palette.borderWeakerBase, palette: palette)
    }

    /// Activity Card timeline: one lit live card with a header, summary, and running-tool footer.
    mutating func drawTimelineActivityCardSkeleton(palette: SkeletonIllustrationPalette) {
        skeletonFrame(palette: palette)
        skeletonUserBubble(palette: palette)
        skeletonBar(x: 64, y: 62, w: 196, h: 7, palette: palette)
        skeletonBar(x: 64, y: 75, w: 120, h: 7, palette: palette)
        let card = CGRect(x: 64, y: 90, width: 208, height: 80)
        skeletonGlow(x: card.minX, y: card.minY, w: card.width, h: card.height, cornerRadius: 12, palette: palette)
        let cardPath = Path(roundedRect: card, cornerRadius: 12)
        fill(cardPath, with: .color(palette.surfaceRaisedBase))
        drawLayer { layer in
            layer.clip(to: cardPath)
            layer.fill(Path(CGRect(x: 64, y: 146, width: 208, height: 24)), with: .color(palette.borderWeakerBase))
        }
        fill(Path(CGRect(x: 64, y: 146, width: 208, height: 1)), with: .color(palette.borderWeakBase))
        stroke(cardPath, with: .color(palette.interactive), lineWidth: 2)
        skeletonBar(x: 76, y: 102, w: 52, h: 8, color: palette.textBase, palette: palette)
        skeletonBar(x: 236, y: 103, w: 26, h: 6, color: palette.textWeaker, palette: palette)
        skeletonBar(x: 76, y: 120, w: 70, h: 6, color: palette.textWeak, palette: palette)
        skeletonBar(x: 152, y: 120, w: 46, h: 6, palette: palette)
        skeletonDot(cx: 80, cy: 158, radius: 4, color: palette.interactive)
        skeletonBar(x: 90, y: 155, w: 30, h: 6, color: palette.textBase, palette: palette)
        skeletonBar(x: 126, y: 155, w: 84, h: 6, palette: palette)
        skeletonChevron(
            [CGPoint(x: 256, y: 154), CGPoint(x: 260, y: 158), CGPoint(x: 256, y: 162)],
            palette: palette
        )
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
