import OpenCodeKit
import SwiftUI
import VoiceEngineKit

/// The talk button. Idle it shows its label. Held it is just the voice meter; dragging sideways
/// traces round its edge, builds a glow from that side and fades in a lock (right) or ✕ (left).
/// The button never moves, and the action happens on release once the trace is complete.
/// Locked shows a small lock; a tap pauses.
struct TalkButton: View {
    let home: HomeModel
    let voice: VoiceEngine

    private let height: CGFloat = 50
    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: height / 2, style: .continuous) }

    var body: some View {
        ZStack {
            background
            if home.isRecording {
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
                    if !home.isPressing { home.pressBegan() }
                    home.pressMoved(dx: value.translation.width)
                }
                .onEnded { _ in
                    // A very quick tap can end the gesture before it reported a change.
                    if !home.isPressing { home.pressBegan() }
                    Task { await home.pressEnded() }
                }
        )
        .sensoryFeedback(.impact(weight: .light), trigger: home.isArmed) { _, armed in armed }
        .sensoryFeedback(.impact(weight: .medium), trigger: home.isRecording) { _, recording in recording }
        .accessibilityElement()
        .accessibilityLabel(Text(accessibilityText))
        .accessibilityAddTraits(.isButton)
    }

    private var isQuiet: Bool { home.isRecording || !home.draft.isEmpty }

    @ViewBuilder
    private var background: some View {
        if isQuiet {
            shape.fill(OpenCodeColors.surfaceRaisedBase)
                .overlay(shape.strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1))
        } else {
            shape.fill(OpenCodeColors.buttonPrimaryBase)
        }
        if home.isPressing, home.isRecording, !home.locked {
            // The glow builds inside the button from the side it is dragged to.
            LinearGradient(
                colors: [tint.opacity(0.22 * abs(home.drag)), .clear],
                startPoint: home.drag < 0 ? .leading : .trailing,
                endPoint: .center
            )
        }
    }

    private var tint: Color { home.drag < 0 ? OpenCodeColors.error : OpenCodeColors.textStrong }

    private var idleContent: some View {
        HStack(spacing: 8) {
            OpenCodeIconImage(icon: .mic, size: 16)
            Text(verbatim: label)
                .font(.system(size: 15.5, weight: .semibold))
                .lineLimit(1)
        }
        .foregroundStyle(isQuiet ? OpenCodeColors.textStrong : OpenCodeColors.iconInvertBase)
        .opacity(voice.modelState == .ready ? 1 : 0.6)
    }

    private var recordingContent: some View {
        ZStack {
            TalkMeter(voice: voice)
                .padding(.horizontal, home.locked ? 44 : 34)
                .offset(x: home.drag * 6)
            if home.locked {
                HStack {
                    OpenCodeIconImage(icon: .lock, size: 15)
                        .foregroundStyle(OpenCodeColors.textWeak)
                    Spacer()
                }
                .padding(.horizontal, 18)
            } else if home.isPressing {
                trace
                HStack {
                    armIcon(.close, shown: home.drag < 0)
                    Spacer()
                    armIcon(.lock, shown: home.drag > 0)
                }
                .padding(.horizontal, 14)
            }
        }
    }

    /// Progress round the button's edge; complete means release will lock or drop.
    private var trace: some View {
        shape
            .inset(by: 1)
            .trim(from: 0, to: abs(home.drag))
            .stroke(tint, style: StrokeStyle(lineWidth: 2, lineCap: .round))
            .animation(.linear(duration: 0.05), value: home.drag)
    }

    private func armIcon(_ icon: OpenCodeIcon, shown: Bool) -> some View {
        let k = shown ? abs(home.drag) : 0
        return OpenCodeIconImage(icon: icon, size: 15)
            .foregroundStyle(tint)
            .opacity(k)
            .scaleEffect(0.7 + 0.3 * k)
    }

    private var label: String {
        switch voice.modelState {
        case let .preparing(fraction, _):
            return String(localized: "talk.preparing", defaultValue: "Getting speech ready · \(Int((fraction * 100).rounded()))%")
        case .notLoaded:
            return String(localized: "talk.waiting", defaultValue: "Getting speech ready")
        case .failed:
            return String(localized: "talk.failed", defaultValue: "Speech isn’t available")
        case .ready:
            if home.draft.selectedIndex != nil {
                return String(localized: "talk.rerecord", defaultValue: "Hold to re-record")
            }
            return home.draft.isEmpty
                ? String(localized: "talk.hold", defaultValue: "Hold to talk")
                : String(localized: "talk.more", defaultValue: "Hold to keep talking")
        }
    }

    private var accessibilityText: String {
        if home.locked { return String(localized: "talk.a11y.locked", defaultValue: "Recording, locked. Tap to pause.") }
        if home.isRecording { return String(localized: "talk.a11y.recording", defaultValue: "Recording. Release to pause.") }
        return label
    }
}

/// Thin bars driven by the take's loudness: the only voice meter on the screen.
struct TalkMeter: View {
    let voice: VoiceEngine
    @State private var history: [CGFloat] = Array(repeating: 0, count: 36)

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
            Canvas { gc, size in
                let w = size.width / CGFloat(history.count)
                for (i, v) in history.enumerated() {
                    let h = max(3, min(1, v * 1.6) * size.height * 0.6)
                    let rect = CGRect(x: CGFloat(i) * w + w * 0.3, y: (size.height - h) / 2, width: w * 0.4, height: h)
                    gc.fill(Path(roundedRect: rect, cornerRadius: w * 0.2), with: .style(OpenCodeColors.textStrong))
                }
            }
            .onChange(of: context.date) {
                history.removeFirst()
                history.append(CGFloat(voice.level))
            }
        }
        .accessibilityHidden(true)
    }
}
