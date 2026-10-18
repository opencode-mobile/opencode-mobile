import SwiftUI

struct FeedbackComposerGalleryView: View {
    private enum Sample: String, CaseIterable, Identifiable {
        case empty = "Empty"
        case attachments = "Attachments"
        case typed = "Typed"
        case sending = "Sending"
        case dictation = "Dictation"

        var id: String { rawValue }
    }

    @State private var sample = Sample.attachments
    @State private var showMic = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("State")
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
                OpenCodeChoiceBadge(
                    label: "Mic",
                    selected: showMic,
                    action: { showMic.toggle() }
                )
            }

            OpenCodeFeedbackComposer(
                state: state,
                dictation: sample == .dictation ? dictation : nil,
                onAction: { _ in }
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var state: OpenCodeFeedbackComposerState {
        let message = switch sample {
        case .empty, .attachments, .dictation: ""
        case .typed, .sending: "The approval card is missing until I pull to refresh."
        }
        let attachments: [OpenCodeAttachmentPresentation] = switch sample {
        case .empty: []
        default: [
            .init(
                id: "screenshot",
                filename: "Screenshot",
                variant: .image,
                image: Self.sampleScreenshot,
                detail: "1 area hidden"
            ),
            .init(id: "logs", filename: "Logs · 15 min", variant: .text, detail: "Tap to preview"),
        ]
        }
        let addChips: [OpenCodeFeedbackComposerAddChip] = sample == .empty
            ? [.init(id: "screenshot", title: "Screenshot"), .init(id: "logs", title: "Logs")]
            : []
        let send: OpenCodeFeedbackComposerSendState = switch sample {
        case .sending: .sending
        case .typed: .enabled
        default: .disabled
        }
        return OpenCodeFeedbackComposerState(
            message: message,
            placeholder: "What happened? What did you expect?",
            attachments: attachments,
            addChips: addChips,
            characterLimit: 5_000,
            send: send,
            showDictationButton: showMic
        )
    }

    private var dictation: OpenCodeSessionComposerDictation {
        OpenCodeSessionComposerDictation(
            variant: .listening,
            committed: "The session list still shows",
            volatileText: " Running",
            level: 0.4,
            status: "Listening · English · on-device",
            startedAt: Date()
        )
    }

    private static let sampleScreenshot: UIImage = {
        let size = CGSize(width: 90, height: 195)
        return UIGraphicsImageRenderer(size: size).image { context in
            UIColor.secondarySystemBackground.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor.systemGray3.setFill()
            for row in 0..<6 {
                context.fill(CGRect(x: 10, y: 30 + CGFloat(row) * 22, width: 70, height: 8))
            }
            UIColor.black.setFill()
            context.fill(CGRect(x: 10, y: 70, width: 50, height: 18))
        }
    }()
}

#Preview {
    FeedbackComposerGalleryView()
        .padding()
        .openCodeTheme()
}
