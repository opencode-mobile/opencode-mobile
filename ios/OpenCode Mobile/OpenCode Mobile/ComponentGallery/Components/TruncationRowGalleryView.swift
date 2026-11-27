import SwiftUI

struct TruncationRowGalleryView: View {
    @State private var taps = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(verbatim: "Full message")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(verbatim: "Sits at the cut of a long user message and opens the whole text in the Select text sheet.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            OpenCodeTruncationRow(
                variant: .fullMessage(shownCharacters: 1_000, totalCharacters: 588_638),
                action: { taps += 1 }
            )
            Text(verbatim: "Tapped \(taps) times")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)

            Text(verbatim: "In a message")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            OpenCodeOutgoingMessage(
                content: [
                    .text(
                        id: "cut",
                        text: truncationRowGallerySample,
                        limit: OpenCodeOutgoingMessageLimit(
                            shownCharacters: truncationRowGallerySample.count,
                            totalCharacters: 588_638,
                            onShowFull: { taps += 1 }
                        )
                    ),
                ],
                metadataLabel: "Build · DeepSeek V4 Flash",
                onTap: {}
            )
        }
    }
}

private let truncationRowGallerySample = (1 ... 25)
    .map { "09:41:\(String(format: "%02d", $0)) INFO worker \($0) finished step ok" }
    .joined(separator: "\n")
