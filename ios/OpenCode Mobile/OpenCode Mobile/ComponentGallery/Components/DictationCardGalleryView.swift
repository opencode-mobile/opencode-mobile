import SwiftUI

struct DictationCardGalleryView: View {
    private enum Sample: String, CaseIterable, Identifiable {
        case listening = "Listening"
        case finishing = "Finishing"
        case permissionDenied = "Permission denied"
        case modelNotInstalled = "Model not installed"
        case recognizerError = "Recognizer error"

        var id: String { rawValue }

        var variant: OpenCodeDictationCardVariant {
            switch self {
            case .listening:
                .listening
            case .finishing:
                .finishing
            case .permissionDenied:
                .unavailable(.permissionDenied)
            case .modelNotInstalled:
                .unavailable(.modelNotInstalled)
            case .recognizerError:
                .unavailable(.recognizerError("Dictation stopped"))
            }
        }

        var showsLevel: Bool {
            self == .listening || self == .finishing
        }
    }

    @State private var sample = Sample.listening
    @State private var level = 0.4

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Variant")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            OpenCodeChipFlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(Sample.allCases) { option in
                    OpenCodeChoiceBadge(
                        label: option.rawValue,
                        selected: sample == option,
                        action: { sample = option }
                    )
                }
            }

            if sample.showsLevel {
                Text("Level")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Slider(value: $level, in: 0...1)
            }

            OpenCodeDictationCard(
                variant: sample.variant,
                committed: sample.showsLevel ? "Hello" : "",
                volatileText: sample == .listening ? " there" : "",
                level: level,
                status: "Listening · English · on-device",
                elapsed: "0:12",
                onCancel: {},
                onInsert: {},
                onUnavailableAction: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    DictationCardGalleryView()
        .padding()
        .openCodeTheme()
}
