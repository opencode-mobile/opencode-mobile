import SwiftUI

struct RenderedMarkdownGalleryView: View {
    @State private var viewModel = RenderedMarkdownGalleryViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(RenderedMarkdownGalleryMocks.samples) { sample in
                        OpenCodeChoiceBadge(label: .verbatim(sample.label), selected: sample.id == viewModel.selectedSample.id) {
                            viewModel.selectSample(sample.id)
                        }
                    }
                }
            }

            Self.render(viewModel.selectedSample)
        }
    }

    /// One sample as the gallery shows it; captures reuse it.
    @ViewBuilder
    static func render(_ sample: RenderedMarkdownGalleryMocks.Sample) -> some View {
        if sample.usesOutgoingMessage {
            OpenCodeOutgoingMessage(
                text: sample.rawMarkdown,
                renderedMarkdown: sample.document,
                metadataLabel: "Build · Claude Sonnet · just now"
            )
        } else {
            OpenCodeRenderedMarkdown(
                document: sample.document,
                variant: sample.variant
            )
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
        }
    }
}

#Preview {
    RenderedMarkdownGalleryView()
        .padding()
        .openCodeTheme()
}
