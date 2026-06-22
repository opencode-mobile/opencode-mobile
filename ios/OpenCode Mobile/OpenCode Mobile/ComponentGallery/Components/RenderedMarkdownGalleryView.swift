import SwiftUI

struct RenderedMarkdownGalleryView: View {
    @State private var sampleID = RenderedMarkdownGalleryMocks.samples[0].id

    private var selectedSample: RenderedMarkdownGalleryMocks.Sample {
        RenderedMarkdownGalleryMocks.samples.first { $0.id == sampleID } ?? RenderedMarkdownGalleryMocks.samples[0]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Picker("Sample", selection: $sampleID) {
                ForEach(RenderedMarkdownGalleryMocks.samples) { sample in
                    Text(sample.label).tag(sample.id)
                }
            }
            .pickerStyle(.segmented)

            OpenCodeRenderedMarkdown(
                document: selectedSample.document,
                variant: selectedSample.variant
            )
            .padding(12)
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
