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
        }
        .scrollIndicators(.hidden)
        .frame(maxHeight: 72)
        .fixedSize(horizontal: false, vertical: true)
    }

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

    private let barCount = 24
    private let barWidth: CGFloat = 3
    private let barMin: CGFloat = 4
    private let barMax: CGFloat = 18
    private let orbit: CGFloat = 40

    var body: some View {
        let tint = finishing ? OpenCodeColors.success : OpenCodeColors.interactive
        ZStack {
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                for index in 0..<barCount {
                    let angle = (Double(index) / Double(barCount)) * 2 * .pi - .pi / 2
                    let wobble = 0.55 + 0.45 * abs(sin(Double(index) * 0.9))
                    let length = barMin + (barMax - barMin) * level * wobble
                    let inner = orbit
                    let outer = orbit + length
                    var path = Path()
                    path.move(to: point(center, angle: angle, radius: inner))
                    path.addLine(to: point(center, angle: angle, radius: outer))
                    context.stroke(
                        path,
                        with: .color(tint),
                        style: StrokeStyle(lineWidth: barWidth, lineCap: .round)
                    )
                }
            }
            .frame(width: 132, height: 132)

            Circle()
                .fill(tint)
                .frame(width: 64, height: 64)
                .scaleEffect(1 + level * 0.08)

            OpenCodeIconImage(icon: .mic, size: 28)
                .foregroundStyle(OpenCodeColors.textOnBrandBase)
                .scaleEffect(1 + level * 0.08)
        }
        .frame(width: 132, height: 132)
        .animation(.easeOut(duration: 0.12), value: level)
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
