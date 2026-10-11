import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import OpenCode_Mobile

@MainActor
struct FileImageDownsamplerTests {
    @Test func largeImageIsDecodedAtMost4096PixelsOnTheLongerSide() async throws {
        let data = try Self.pngData(width: 6_000, height: 3_000)

        let image = try #require(await FileImageDownsampler.image(from: data))

        #expect(max(image.width, image.height) <= FileImageDownsampler.maxPixelSize)
        #expect(image.width == 4_096)
        #expect(abs(Double(image.width) / Double(image.height) - 2) < 0.01)
    }

    @Test func smallImageKeepsItsSize() throws {
        let data = try Self.pngData(width: 120, height: 60)
        let image = try #require(FileImageDownsampler.downsample(data))
        #expect(image.width == 120)
        #expect(image.height == 60)
    }

    @Test func undecodableBytesReturnNil() {
        #expect(FileImageDownsampler.downsample(Data([0x89, 0x50, 0x4E, 0x47])) == nil)
        #expect(FileImageDownsampler.downsample(Data()) == nil)
    }

    /// A solid PNG; it compresses to a small file however large the bitmap is.
    private static func pngData(width: Int, height: Int) throws -> Data {
        let context = try #require(CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try #require(context.makeImage())
        let output = NSMutableData()
        let destination = try #require(CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        #expect(CGImageDestinationFinalize(destination))
        return output as Data
    }
}

@MainActor
struct FileViewerCopyTests {
    @Test func tooLargeCopyMatchesAndroid() {
        #expect(FileViewerCopy.tooLargeTitle == "Too large to preview")
        #expect(FileViewerCopy.tooLargeDetail(byteCount: nil) == "This file is bigger than the app previews.")
        #expect(FileViewerCopy.tooLargeDetail(byteCount: 40_000_000) == "This file is bigger than the app previews.\n\(FileViewerCopy.fileSize(40_000_000))")
    }

    @Test func fileSizeUsesDecimalUnits() {
        // ByteCountFormatter's `.file` style is decimal: 40,000,000 bytes is 40 MB, not 38.1 MB.
        let megabytes = FileViewerCopy.fileSize(40_000_000)
        #expect(megabytes.contains("40"))
        #expect(megabytes.lowercased().contains("mb"))
        #expect(FileViewerCopy.fileSize(1_500).lowercased().contains("kb"))
    }
}
