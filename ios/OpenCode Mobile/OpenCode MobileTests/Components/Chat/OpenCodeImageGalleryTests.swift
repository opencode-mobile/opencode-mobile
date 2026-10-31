import CoreGraphics
import Testing
@testable import OpenCode_Mobile

struct OpenCodeImageGalleryTests {
    @Test func layoutBySelectsTheArrangementForTheCount() {
        #expect(OpenCodeImageGalleryLayout(count: 1) == .single)
        #expect(OpenCodeImageGalleryLayout(count: 2) == .pair)
        #expect(OpenCodeImageGalleryLayout(count: 3) == .trio)
        #expect(OpenCodeImageGalleryLayout(count: 4) == .grid(overflow: 0))
        #expect(OpenCodeImageGalleryLayout(count: 5) == .grid(overflow: 2))
        #expect(OpenCodeImageGalleryLayout(count: 18) == .grid(overflow: 15))
    }

    @Test func layoutDrawsAtMostFourTiles() {
        #expect([1, 2, 3, 4, 9].map { OpenCodeImageGalleryLayout(count: $0).tileCount } == [1, 2, 3, 4, 4])
    }

    @Test func pairIsTwoEqualThreeByFourColumns() {
        let frames = mosaic(.pair).frames(width: 304)
        #expect(frames == [
            CGRect(x: 0, y: 0, width: 150, height: 200),
            CGRect(x: 154, y: 0, width: 150, height: 200),
        ])
    }

    @Test func trioIsOneLargeTileAndTwoStackedAtFixedHeight() {
        let frames = mosaic(.trio).frames(width: 244)
        #expect(frames.count == 3)
        #expect(frames[0].height == OpenCodeImageGallery.trioHeight)
        #expect(abs(frames[0].width / frames[1].width - 1.4) < 0.001)
        #expect(frames[1].height == frames[2].height)
        #expect(frames[2].maxY == OpenCodeImageGallery.trioHeight)
        #expect(frames[1].minX == frames[0].maxX + OpenCodeImageGallery.spacing)
    }

    @Test func gridIsTwoByTwoFourByThreeTiles() {
        let frames = mosaic(.grid(overflow: 2)).frames(width: 304)
        #expect(frames.count == 4)
        #expect(frames.allSatisfy { $0.width == 150 && $0.height == 112.5 })
        #expect(frames[3] == CGRect(x: 154, y: 116.5, width: 150, height: 112.5))
    }

    @Test func accessibilityLabelNamesPositionAndFile() {
        #expect(OpenCodeImageGalleryItem.accessibilityLabel(index: 1, count: 5, filename: "login.png") == "Image 2 of 5, login.png")
        #expect(OpenCodeImageGalleryItem.accessibilityLabel(index: 0, count: 3, filename: nil) == "Image 1 of 3")
        #expect(OpenCodeImageGalleryItem.accessibilityLabel(index: 0, count: 1, filename: "a.png") == "Image, a.png")
    }

    @Test func emptyFilenameMeansUnknown() {
        #expect(OpenCodeImageGalleryItem(id: "x", filename: "").filename == nil)
    }

    private func mosaic(_ layout: OpenCodeImageGalleryLayout) -> OpenCodeImageMosaicLayout {
        OpenCodeImageMosaicLayout(layout: layout, spacing: OpenCodeImageGallery.spacing, maxWidth: OpenCodeImageGallery.maxMosaicWidth)
    }
}
