import SwiftUI

/// The talk button: a 50 pt pill. Idle it is `solid` with the mic and its label; with a draft it
/// turns quiet (`panel`, `line2` border). Held it is just the level meter; dragging sideways
/// traces round its edge, builds a glow from that side and fades in a lock (right) or ✕ (left).
/// The button never moves. Locked shows a small lock.
struct HexTalkButton: View {
    struct Phase: Equatable {
        /// Shown while not recording.
        var label: String
        /// Speech is ready; otherwise the label is dimmed.
        var ready = true
        /// A draft exists: the quiet style.
        var quiet = false
        var recording = false
        var pressing = false
        var locked = false
        /// −1 (drop) … 1 (lock): how far the drag has gone.
        var drag: CGFloat = 0
        /// The drag has gone far enough that release locks or drops.
        var armed = false
    }

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
            } else {
                idleContent
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
        .sensoryFeedback(.impact(weight: .light), trigger: phase.armed) { _, armed in armed }
        .sensoryFeedback(.impact(weight: .medium), trigger: phase.recording) { _, recording in recording }
        .animation(HexMotion.ease(HexMotion.fade), value: phase.recording)
        .animation(HexMotion.ease(HexMotion.fade), value: phase.quiet)
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: accessibilityText))
        .accessibilityAddTraits(.isButton)
    }

    private var isQuiet: Bool { phase.recording || phase.quiet }
    private var dropping: Bool { phase.drag < 0 }

    @ViewBuilder
    private var background: some View {
        if isQuiet {
            shape.fill(HexColor.panel)
                .overlay(shape.strokeBorder(HexColor.line2, lineWidth: 1))
        } else {
            shape.fill(HexColor.solid)
        }
        if phase.pressing, phase.recording, !phase.locked {
            // The glow builds inside the button from the side it is dragged to.
            let k = abs(phase.drag)
            LinearGradient(
                stops: [
                    .init(color: dropping ? HexColor.red.opacity(0.24 * k) : HexColor.tint(16 * k), location: 0),
                    .init(color: dropping ? HexColor.red.opacity(0.06 * k) : HexColor.tint(4 * k), location: 0.45),
                    .init(color: .clear, location: 0.75),
                ],
                startPoint: dropping ? .leading : .trailing,
                endPoint: dropping ? .trailing : .leading
            )
        }
    }

    private var tint: Color { dropping ? HexColor.red : HexColor.text1 }

    private var idleContent: some View {
        HStack(spacing: 8) {
            HexIconView(.mic, size: HexIconSize.regular)
            Text(verbatim: phase.label)
                .hexFont(.bodyStrong)
                .lineLimit(1)
        }
        .foregroundStyle(isQuiet ? HexColor.text1 : HexColor.onSolid)
        .opacity(phase.ready ? 1 : 0.6)
    }

    private var recordingContent: some View {
        ZStack {
            HexMeter(level: level)
                .padding(.leading, phase.locked ? 40 : 14)
                .padding(.trailing, phase.locked ? 16 : 14)
                .opacity(phase.pressing && abs(phase.drag) > 0.05 ? 0.25 + 0.75 * (1 - abs(phase.drag)) : 1)
                .offset(x: phase.drag * 6)
            if phase.locked {
                HStack {
                    HexIconView(.lock, size: HexIconSize.regular)
                        .foregroundStyle(HexColor.text2)
                    Spacer()
                }
                .padding(.horizontal, 15)
            } else if phase.pressing {
                trace
                HStack {
                    armIcon(.x, shown: dropping)
                    Spacer()
                    armIcon(.lock, shown: phase.drag > 0)
                }
                .padding(.horizontal, 15)
            }
        }
    }

    /// Progress round the button's edge; complete means release will lock or drop.
    private var trace: some View {
        shape
            .inset(by: 1)
            .trim(from: 0, to: abs(phase.drag))
            .stroke(tint, style: StrokeStyle(lineWidth: 2, lineCap: .round))
            .scaleEffect(x: dropping ? -1 : 1, y: 1)
            .animation(.linear(duration: 0.05), value: phase.drag)
    }

    private func armIcon(_ icon: HexIcon, shown: Bool) -> some View {
        let k = shown ? abs(phase.drag) : 0
        return HexIconView(icon, size: HexIconSize.regular)
            .foregroundStyle(icon == .x ? HexColor.red : HexColor.text1)
            .opacity(k)
            .scaleEffect(0.7 + 0.3 * k)
    }
}
