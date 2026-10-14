import SwiftUI

enum OpenCodeDictationCardVariant: Equatable {
    case listening
    case finishing
    case unavailable(OpenCodeDictationUnavailableReason)
}

enum OpenCodeDictationUnavailableReason: Equatable {
    case permissionDenied
    case modelNotInstalled
    case recognizerError(String)

    var message: String {
        switch self {
        case .permissionDenied:
            return "Microphone access is off"
        case .modelNotInstalled:
            return "The on-device speech model is not installed"
        case let .recognizerError(message):
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "Dictation stopped" : trimmed
        }
    }

    var actionTitle: String {
        switch self {
        case .permissionDenied:
            return "Open Settings"
        case .modelNotInstalled:
            return "Download"
        case .recognizerError:
            return "Retry"
        }
    }
}

/// Listening takeover for composer dictation. Stateless: the parent owns the
/// transcript, level, status copy, and elapsed label.
struct OpenCodeDictationCard: View {
    let variant: OpenCodeDictationCardVariant
    var committed: String = ""
    var volatileText: String = ""
    var level: Double = 0
    var status: String = ""
    var elapsed: String = ""
    var onCancel: () -> Void
    var onInsert: () -> Void
    var onUnavailableAction: () -> Void = {}

    var body: some View {
        VStack(spacing: 16) {
            switch variant {
            case .listening, .finishing:
                listeningBody
            case let .unavailable(reason):
                Text(reason.message)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
            }
            actionRow
        }
        .accessibilityElement(children: .contain)
    }

    private var listeningBody: some View {
        VStack(spacing: 12) {
            DictationOrb(level: clampedLevel, finishing: variant == .finishing)
            transcript
            statusRow
        }
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                Group {
                    if committed.isEmpty, volatileText.isEmpty {
                        Text("Listening…")
                            .foregroundStyle(OpenCodeColors.textWeak)
                    } else {
                        (Text(committed).foregroundStyle(OpenCodeColors.textStrong)
                            + Text(volatileText).foregroundStyle(OpenCodeColors.textWeak))
                    }
                }
                .font(OpenCodeTypography.body)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                Color.clear
                    .frame(height: 1)
                    .id(Self.transcriptEnd)
            }
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.bottom)
            // Follow the newest words as the transcript grows past the visible lines.
            .onChange(of: committed + volatileText) {
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo(Self.transcriptEnd, anchor: .bottom)
                }
            }
        }
        .frame(maxHeight: 72)
        .fixedSize(horizontal: false, vertical: true)
    }

    private static let transcriptEnd = "dictation-transcript-end"

    private var statusRow: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(OpenCodeColors.error)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(status)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
            Spacer(minLength: 8)
            Text(elapsed)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .monospacedDigit()
        }
    }

    @ViewBuilder
    private var actionRow: some View {
        VStack(spacing: 8) {
            OpenCodeButton(
                variant: .fullWidthText(title: "Cancel"),
                contrast: .low,
                action: onCancel
            )
            .accessibilityLabel("Cancel dictation")

            switch variant {
            case .listening, .finishing:
                OpenCodeButton(
                    variant: .fullWidthText(title: "Insert"),
                    enabled: variant == .listening && hasTranscript,
                    action: onInsert
                )
                .accessibilityLabel("Insert transcript")
            case let .unavailable(reason):
                OpenCodeButton(
                    variant: .fullWidthText(title: reason.actionTitle),
                    action: onUnavailableAction
                )
            }
        }
    }

    private var hasTranscript: Bool {
        !(committed + volatileText).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var clampedLevel: Double {
        min(1, max(0, level.isFinite ? level : 0))
    }
}

private struct DictationOrb: View {
    let level: Double
    let finishing: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var motion = DictationOrbMotion()

    private let barCount = 24
    private let barWidth: CGFloat = 3
    private let barMin: CGFloat = 4
    private let barMax: CGFloat = 28
    private let orbit: CGFloat = 40
    private let orbSize: CGFloat = 64
    private let canvasSize: CGFloat = 148

    var body: some View {
        let tint = finishing ? OpenCodeColors.success : OpenCodeColors.interactive
        TimelineView(.animation) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            let current = motion.advance(to: finishing ? 0 : level, at: time)
            let wobbleTime = reduceMotion ? 0 : time
            ZStack {
                Canvas { context, size in
                    let center = CGPoint(x: size.width / 2, y: size.height / 2)
                    let haloRadius = orbSize / 2 * (1 + 0.6 * current)
                    context.fill(
                        Path(ellipseIn: CGRect(
                            x: center.x - haloRadius,
                            y: center.y - haloRadius,
                            width: haloRadius * 2,
                            height: haloRadius * 2
                        )),
                        with: .color(tint.opacity(0.22 * current))
                    )
                    for index in 0..<barCount {
                        let angle = (Double(index) / Double(barCount)) * 2 * .pi - .pi / 2
                        let extent = DictationOrbMotion.barExtent(index: index, level: current, time: wobbleTime)
                        let length = barMin + (barMax - barMin) * extent
                        var path = Path()
                        path.move(to: point(center, angle: angle, radius: orbit))
                        path.addLine(to: point(center, angle: angle, radius: orbit + length))
                        context.stroke(
                            path,
                            with: .color(tint),
                            style: StrokeStyle(lineWidth: barWidth, lineCap: .round)
                        )
                    }
                }
                .frame(width: canvasSize, height: canvasSize)

                ZStack {
                    Circle()
                        .fill(tint)
                        .frame(width: orbSize, height: orbSize)
                    OpenCodeIconImage(icon: .mic, size: 28)
                        .foregroundStyle(OpenCodeColors.textOnBrandBase)
                }
                .scaleEffect(1 + 0.18 * current)
            }
        }
        .frame(width: canvasSize, height: canvasSize)
        .animation(.easeInOut(duration: 0.24), value: finishing)
        .accessibilityHidden(true)
    }

    private func point(_ center: CGPoint, angle: Double, radius: CGFloat) -> CGPoint {
        CGPoint(
            x: center.x + CGFloat(cos(angle)) * radius,
            y: center.y + CGFloat(sin(angle)) * radius
        )
    }
}

/// Per-frame level smoothing for the orb. A reference type so advancing it while
/// rendering a timeline frame does not invalidate the view.
final class DictationOrbMotion {
    private static let attack: Double = 0.05
    private static let release: Double = 0.22

    private(set) var value: Double = 0
    private var lastTime: TimeInterval?

    /// Fast attack, slower release, so speech pops and pauses settle.
    func advance(to target: Double, at time: TimeInterval) -> Double {
        let clamped = min(1, max(0, target.isFinite ? target : 0))
        let dt = lastTime.map { min(max(time - $0, 0), 0.1) } ?? 0
        lastTime = time
        guard dt > 0 else { return value }
        let tau = clamped > value ? Self.attack : Self.release
        value += (clamped - value) * (1 - exp(-dt / tau))
        return value
    }

    /// Bar length as a fraction of its range: a small idle breath plus a per-bar wave scaled by level.
    static func barExtent(index: Int, level: Double, time: TimeInterval) -> Double {
        let i = Double(index)
        let wave = 0.5 + 0.25 * sin(time * 7 + i * 0.9) + 0.25 * sin(time * 11 - i * 1.3)
        let idle = 0.06 * (0.5 + 0.5 * sin(time * 2.4 + i * 0.5))
        return min(1, max(0, idle + level * (0.35 + 0.65 * wave)))
    }
}

#Preview {
    OpenCodeDictationCard(
        variant: .listening,
        committed: "Hello",
        volatileText: " there",
        level: 0.45,
        status: "Listening · English · on-device",
        elapsed: "0:12",
        onCancel: {},
        onInsert: {}
    )
    .padding()
    .openCodeTheme()
}
