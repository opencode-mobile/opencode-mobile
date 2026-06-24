import Observation

@Observable
final class RenderedMarkdownGalleryViewModel {
    var selectedSampleID = RenderedMarkdownGalleryMocks.samples[0].id

    var selectedSample: RenderedMarkdownGalleryMocks.Sample {
        RenderedMarkdownGalleryMocks.samples.first { $0.id == selectedSampleID }
            ?? RenderedMarkdownGalleryMocks.samples[0]
    }

    func selectSample(_ id: String) {
        selectedSampleID = id
    }
}
