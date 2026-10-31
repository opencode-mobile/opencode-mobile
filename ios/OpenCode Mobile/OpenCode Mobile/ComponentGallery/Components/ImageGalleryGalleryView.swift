import SwiftUI
import UIKit

/// Image Gallery and Image Viewer: every layout by count, loading and failed tiles, inside
/// an outgoing bubble, and the paged viewer opened from any tile.
struct ImageGalleryGalleryView: View {
    private struct Viewer: Identifiable {
        let id = UUID()
        let items: [OpenCodeImageGalleryItem]
        var index: Int
    }

    @State private var viewer: Viewer?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            section("1 image (inline)", items: SnapshotEvidenceImageMocks.items(1, prefix: "one"))
            section("2 images", items: SnapshotEvidenceImageMocks.items(2, prefix: "two"))
            section("3 images", items: SnapshotEvidenceImageMocks.items(3, prefix: "three"))
            section("4 images", items: SnapshotEvidenceImageMocks.items(4, prefix: "four"))
            section("8 images (+5)", items: SnapshotEvidenceImageMocks.items(8, prefix: "eight"))
            section("Loading and failed", items: Self.loadingAndFailed)
            section("Failed single image", items: [Self.failed(id: "failed-single", filename: "missing.png")])

            Text("In an outgoing message")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            OpenCodeOutgoingMessage(
                content: [
                    .images(
                        id: "bubble",
                        items: SnapshotEvidenceImageMocks.items(2, prefix: "bubble"),
                        onOpen: { open(SnapshotEvidenceImageMocks.items(2, prefix: "bubble"), at: $0) }
                    ),
                    .text(id: "text", text: "Both phones clip the login button. Can you fix it?"),
                ],
                metadataLabel: ""
            )
        }
        .fullScreenCover(item: $viewer) { viewer in
            OpenCodeImageViewer(
                items: viewer.items,
                index: viewer.index,
                onIndexChange: { self.viewer?.index = $0 },
                onDismiss: { self.viewer = nil }
            )
        }
    }

    private func section(_ title: String, items: [OpenCodeImageGalleryItem]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            OpenCodeImageGallery(items: items, onOpen: { open(items, at: $0) })
        }
    }

    private func open(_ items: [OpenCodeImageGalleryItem], at index: Int) {
        viewer = Viewer(items: items, index: index)
    }

    private static func failed(id: String, filename: String) -> OpenCodeImageGalleryItem {
        OpenCodeImageGalleryItem(id: id, filename: filename, source: OpenCodeImageSource { nil })
    }

    private static let loadingAndFailed: [OpenCodeImageGalleryItem] = [
        SnapshotEvidenceImageMocks.items(1, prefix: "mixed")[0],
        OpenCodeImageGalleryItem(
            id: "mixed-loading",
            filename: "still-loading.png",
            source: OpenCodeImageSource {
                try? await Task.sleep(for: .seconds(3_600))
                return nil
            }
        ),
        failed(id: "mixed-failed", filename: "missing-capture.png"),
    ]
}

/// Image Viewer: open one picture, a group, or a group with a failed image.
struct ImageViewerGalleryView: View {
    private struct Viewer: Identifiable {
        let id = UUID()
        let items: [OpenCodeImageGalleryItem]
        var index: Int
    }

    @State private var viewer: Viewer?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeButton(variant: .fullWidthText(title: "Open one image"), contrast: .low) {
                viewer = Viewer(items: SnapshotEvidenceImageMocks.items(1, prefix: "viewer-one"), index: 0)
            }
            OpenCodeButton(variant: .fullWidthText(title: "Open 5 images at the second"), contrast: .low) {
                viewer = Viewer(items: SnapshotEvidenceImageMocks.items(5, prefix: "viewer-five"), index: 1)
            }
            OpenCodeButton(variant: .fullWidthText(title: "Open a group with a failed image"), contrast: .low) {
                viewer = Viewer(
                    items: SnapshotEvidenceImageMocks.items(2, prefix: "viewer-failed") + [
                        OpenCodeImageGalleryItem(
                            id: "viewer-failed-missing",
                            filename: "missing-capture.png",
                            source: OpenCodeImageSource { nil }
                        ),
                    ],
                    index: 2
                )
            }
        }
        .fullScreenCover(item: $viewer) { viewer in
            OpenCodeImageViewer(
                items: viewer.items,
                index: viewer.index,
                onIndexChange: { self.viewer?.index = $0 },
                onDismiss: { self.viewer = nil }
            )
        }
    }
}

#Preview("Image gallery gallery") {
    ScrollView {
        ImageGalleryGalleryView().padding()
    }
    .openCodeTheme()
}
