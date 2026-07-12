import SwiftUI

struct RenderedMarkdownGalleryView: View {
    @State private var viewModel = RenderedMarkdownGalleryViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(RenderedMarkdownGalleryMocks.samples) { sample in
                        OpenCodeChoiceBadge(label: sample.label, selected: sample.id == viewModel.selectedSample.id) {
                            viewModel.selectSample(sample.id)
                        }
                    }
                }
            }

            if viewModel.selectedSample.usesOutgoingMessage {
                OpenCodeOutgoingMessage(
                    text: viewModel.selectedSample.rawMarkdown,
                    renderedMarkdown: viewModel.selectedSample.document,
                    metadataLabel: "Build · Claude Sonnet · just now"
                )
            } else {
                OpenCodeRenderedMarkdown(
                    document: viewModel.selectedSample.document,
                    variant: viewModel.selectedSample.variant
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
}

#Preview {
    RenderedMarkdownGalleryView()
        .padding()
        .openCodeTheme()
}
