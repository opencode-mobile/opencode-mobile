import SwiftUI

/// The talk button: a 50 pt pill. Idle it is `solid` with the mic and its label; with a draft it
/// turns quiet (`panel`, `line2` border). Held it is just the level meter; dragging sideways
/// traces round its edge from the side it is dragged to, builds a glow from that side and fades
/// in a lock (right) or ✕ (left). The button never moves. Locked shows a small lock.
///
/// The press answers in the same frame as the touch (no fade in), with a light haptic. The drag
/// look follows a spring-smoothed copy of the finger (about 120 ms, no overshoot), so a shaky
/// finger gives a steady trace; the red and white tints fade with that smoothed distance and
/// never switch on and off. Arming is decided by the caller on the real finger, and ticks once.
struct HexTalkButton: View {
    struct Phase: Equatable {
        /// Shown while not recording.
        var label: String
        /// A draft exists: the quiet style.
        var quiet = false
        var recording = false
        var pressing = false
        var locked = false
        /// The finger's sideways distance from where it touched down, in points (right positive).
        var offset: CGFloat = 0
        /// What release does now; set when the drag has gone far enough.
        var arm: TalkDrag.Arm?
    }

    /// The meter's full height inside the 50 pt pill.
    static let meterHeight: CGFloat = 30

    /// The spring the look follows the finger with.
    static let follow = Animation.spring(duration: 0.12, bounce: 0)

    let phase: Phase
    /// The live input level, 0…1, read for the meter while recording.
    let level: () -> Double
    let accessibilityText: String
    let onPressBegan: () -> Void
    /// The horizontal drag since the press began, in points.
    let onPressMoved: (CGFloat) -> Void
    let onPressEnded: () -> Void

    private let height: CGFloat = 50
    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: height / 2, style: .continuous) }

    var body: some View {
        ZStack {
            background
            if phase.recording {
                recordingContent
                    .background(FirstFrameProbe())
            } else {
                idleContent
                    .transition(.opacity.animation(HexMotion.ease(HexMotion.fade)))
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .clipShape(shape)
        .contentShape(shape)
        .gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .local)
                .onChanged { value in
                    if !phase.pressing { onPressBegan() }
                    onPressMoved(value.translation.width)
                }
                .onEnded { _ in
                    // A very quick tap can end the gesture before it reported a change.
                    if !phase.pressing { onPressBegan() }
                    onPressEnded()
                }
        )
        .sensoryFeedback(.impact(weight: .light), trigger: phase.recording) { _, recording in recording }
        .sensoryFeedback(.selection, trigger: phase.arm) { _, arm in arm != nil }
        // Going into the recording look is instant; only the way back fades.
        .animation(phase.recording ? nil : HexMotion.ease(HexMotion.fade), value: phase.recording)
        .animation(HexMotion.ease(HexMotion.fade), value: phase.quiet)
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: accessibilityText))
        .accessibilityAddTraits(.isButton)
    }

    private var isQuiet: Bool { phase.recording || phase.quiet }

    @ViewBuilder
    private var background: some View {
        if isQuiet {
            shape.fill(HexColor.panel)
                .overlay(shape.strokeBorder(HexColor.line2, lineWidth: 1))
        } else {
            shape.fill(HexColor.solid)
        }
    }

    private var idleContent: some View {
        HStack(spacing: 8) {
            HexIconView(.mic, size: HexIconSize.regular)
            Text(verbatim: phase.label)
                .hexFont(.bodyStrong)
                .lineLimit(1)
        }
        .foregroundStyle(isQuiet ? HexColor.text1 : HexColor.onSolid)
    }

    @ViewBuilder
    private var recordingContent: some View {
        if phase.locked {
            ZStack {
                HexMeter(level: level)
                    .frame(height: Self.meterHeight)
                    .padding(.leading, 40)
                    .padding(.trailing, 16)
                HStack {
                    HexIconView(.lock, size: HexIconSize.regular)
                        .foregroundStyle(HexColor.text2)
                    Spacer()
                }
                .padding(.horizontal, 15)
            }
        } else {
            TalkDragLook(
                offset: phase.pressing ? phase.offset : 0,
                armed: phase.arm != nil,
                shape: shape,
                height: height,
                level: level
            )
            .animation(Self.follow, value: phase.pressing ? phase.offset : 0)
        }
    }
}

/// The held look, drawn from one smoothed offset: SwiftUI's spring animates `offset` itself, and
/// every tint, the ring and the icons are worked out from it each frame, so they move together
/// and fade instead of switching.
private struct TalkDragLook: View, Animatable {
    var offset: CGFloat
    let armed: Bool
    let shape: RoundedRectangle
    let height: CGFloat
    let level: () -> Double

    var animatableData: CGFloat {
        get { offset }
        set { offset = newValue }
    }

    var body: some View {
        let progress = TalkDrag.progress(offset)
        let lock = max(0, progress)
        let drop = max(0, -progress)
        ZStack {
            // The glow builds inside the button from the side it is dragged to.
            glow(HexColor.red, strength: (0.24, 0.06), amount: drop, from: .leading)
            glow(HexColor.text1, strength: (0.16, 0.04), amount: lock, from: .trailing)
            HexMeter(level: level)
                .frame(height: HexTalkButton.meterHeight)
                .padding(.horizontal, 14)
                .opacity(1 - 0.75 * abs(progress))
                .offset(x: progress * 6)
            EdgeTrace(fromTrailing: false, progress: drop)
                .stroke(HexColor.red, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .opacity(drop > 0 ? 1 : 0)
            EdgeTrace(fromTrailing: true, progress: lock)
                .stroke(HexColor.text1, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .opacity(lock > 0 ? 1 : 0)
            HStack {
                armIcon(.x, color: HexColor.red, amount: drop)
                Spacer()
                armIcon(.lock, color: HexColor.text1, amount: lock)
            }
            .padding(.horizontal, 15)
        }
    }

    private func glow(_ color: Color, strength: (CGFloat, CGFloat), amount: CGFloat, from side: UnitPoint) -> some View {
        LinearGradient(
            stops: [
                .init(color: color.opacity(strength.0), location: 0),
                .init(color: color.opacity(strength.1), location: 0.45),
                .init(color: color.opacity(0), location: 0.75),
            ],
            startPoint: side,
            endPoint: side == .leading ? .trailing : .leading
        )
        .opacity(amount)
    }

    private func armIcon(_ icon: HexIcon, color: Color, amount: CGFloat) -> some View {
        HexIconView(icon, size: HexIconSize.regular)
            .foregroundStyle(color)
            .opacity(amount)
            .scaleEffect(0.7 + 0.3 * amount)
    }
}

/// Progress round the pill's edge, growing from the middle of one end along the top and bottom at
/// once, until the two meet at the middle of the other end (complete: release will act).
struct EdgeTrace: Shape {
    /// Starts at the right end (lock); otherwise the left (drop).
    var fromTrailing: Bool
    var progress: CGFloat

    func path(in rect: CGRect) -> Path {
        guard progress > 0 else { return Path() }
        let frame = rect.insetBy(dx: 1, dy: 1)
        let radius = frame.height / 2
        let near = fromTrailing ? frame.maxX : frame.minX
        let far = fromTrailing ? frame.minX : frame.maxX
        let inward: CGFloat = fromTrailing ? -1 : 1
        // One half: from the near end's middle round its corner, along the top (or bottom) edge,
        // round the far corner to the far end's middle.
        func half(edgeY: CGFloat) -> Path {
            var path = Path()
            path.move(to: CGPoint(x: near, y: frame.midY))
            path.addArc(
                tangent1End: CGPoint(x: near, y: edgeY),
                tangent2End: CGPoint(x: near + inward * radius, y: edgeY),
                radius: radius
            )
            path.addLine(to: CGPoint(x: far - inward * radius, y: edgeY))
            path.addArc(
                tangent1End: CGPoint(x: far, y: edgeY),
                tangent2End: CGPoint(x: far, y: frame.midY),
                radius: radius
            )
            return path
        }
        var path = Path()
        path.addPath(half(edgeY: frame.minY).trimmedPath(from: 0, to: progress))
        path.addPath(half(edgeY: frame.maxY).trimmedPath(from: 0, to: progress))
        return path
    }
}

/// Reports the first frame the recording look is drawn in.
private struct FirstFrameProbe: View {
    var body: some View {
        Color.clear.onAppear {
            DispatchQueue.main.async { PressTiming.recordingLookDrawn() }
        }
    }
}
