import CoreGraphics
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// An image ready to go with a message (app plan, "Attach" › Preparing): upright, its longest side
/// at most 2000 px, JPEG quality 0.8, sRGB, no metadata, in a file in the app's cache. The file is
/// removed when the last reference goes: when the draft is sent (and Undo can no longer bring it
/// back) or cleared.
nonisolated final class PreparedImage: Sendable {
    let file: URL
    /// About 120 px on its longest side: 40 pt at 3×.
    let thumbnail: UIImage
    let pixelWidth: Int
    let pixelHeight: Int
    let byteCount: Int

    init(file: URL, thumbnail: UIImage, pixelWidth: Int, pixelHeight: Int, byteCount: Int) {
        self.file = file
        self.thumbnail = thumbnail
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.byteCount = byteCount
    }

    deinit {
        try? FileManager.default.removeItem(at: file)
    }

    /// The JPEG as a `data:` URL, canonical base64 with no line breaks.
    func dataURL() throws -> String {
        "data:image/jpeg;base64,\(try Data(contentsOf: file).base64EncodedString())"
    }
}

/// Decodes, rotates upright, scales and re-encodes a picked image. Runs off the main thread.
nonisolated enum ImagePreparer {
    /// The server's own limit: it keeps an image this size as it is.
    static let maxPixelSize = 2000
    static let quality: CGFloat = 0.8
    static let thumbnailPixelSize = 120
    /// Refused at pick time; a 2000 px JPEG at 0.8 is far smaller (the server's limit is 20 MiB).
    static let maxBytes = 5 * 1024 * 1024

    enum Failure: Error, Equatable {
        case unreadable
        case encodeFailed
        case tooLarge
    }

    /// The app's cache folder for prepared images. Emptied at launch: nothing there outlives the
    /// draft it belongs to.
    static var cacheFolder: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appending(path: "HexCodeAttachments", directoryHint: .isDirectory)
    }

    static func emptyCache(_ folder: URL = cacheFolder) {
        try? FileManager.default.removeItem(at: folder)
    }

    /// Bytes from the photo picker, in any format ImageIO reads (HEIC, PNG, JPEG…).
    static func prepare(data: Data, in folder: URL = cacheFolder) throws -> PreparedImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0
        else { throw Failure.unreadable }
        // Scale down only, never up. Decoding and scaling in one step never loads a 48 MP bitmap.
        let longest = min(max(width, height), maxPixelSize)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: longest,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw Failure.unreadable
        }
        return try finish(image, in: folder)
    }

    /// A photo from the camera: drawn upright at its final size.
    static func prepare(image: UIImage, in folder: URL = cacheFolder) throws -> PreparedImage {
        let size = image.size
        guard size.width > 0, size.height > 0 else { throw Failure.unreadable }
        let pixels = CGSize(width: size.width * image.scale, height: size.height * image.scale)
        let factor = min(1, CGFloat(maxPixelSize) / max(pixels.width, pixels.height))
        let target = CGSize(width: (pixels.width * factor).rounded(), height: (pixels.height * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let upright = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let cgImage = upright.cgImage else { throw Failure.unreadable }
        return try finish(cgImage, in: folder)
    }

    /// sRGB, 8 bits, opaque (transparency on white), then JPEG with no metadata, and the thumbnail.
    private static func finish(_ image: CGImage, in folder: URL) throws -> PreparedImage {
        let srgb = try redraw(image, width: image.width, height: image.height)
        let jpeg = try encode(srgb)
        guard jpeg.count <= maxBytes else { throw Failure.tooLarge }
        let thumbnail = try thumbnail(of: jpeg)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appending(path: "\(UUID().uuidString).jpg")
        try jpeg.write(to: file, options: .atomic)
        return PreparedImage(file: file, thumbnail: thumbnail, pixelWidth: srgb.width, pixelHeight: srgb.height, byteCount: jpeg.count)
    }

    private static func redraw(_ image: CGImage, width: Int, height: Int) throws -> CGImage {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil,
                  width: width,
                  height: height,
                  bitsPerComponent: 8,
                  bytesPerRow: 0,
                  space: space,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              )
        else { throw Failure.encodeFailed }
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        context.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
        context.fill(rect)
        context.interpolationQuality = .high
        context.draw(image, in: rect)
        guard let result = context.makeImage() else { throw Failure.encodeFailed }
        return result
    }

    /// JPEG at 0.8. Only the quality is passed, so no EXIF, GPS or TIFF metadata is written.
    private static func encode(_ image: CGImage) throws -> Data {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw Failure.encodeFailed
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw Failure.encodeFailed }
        return output as Data
    }

    private static func thumbnail(of jpeg: Data) throws -> UIImage {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceThumbnailMaxPixelSize: thumbnailPixelSize,
              ] as CFDictionary)
        else { throw Failure.encodeFailed }
        return UIImage(cgImage: image)
    }
}

/// An image picked in the Attach sheet: from Photos (its bytes, loaded on demand) or the camera.
nonisolated struct PickedImage: Sendable {
    enum Content: Sendable {
        case data(Data)
        case image(UIImage)
    }

    struct Unreadable: Error {}

    let kind: DraftImage.Kind
    let load: @Sendable () async throws -> Content

    /// Prepares off the main thread.
    @Sendable
    static func prepare(_ content: Content) async throws -> PreparedImage {
        try await Task.detached(priority: .userInitiated) {
            switch content {
            case let .data(data): try ImagePreparer.prepare(data: data)
            case let .image(image): try ImagePreparer.prepare(image: image)
            }
        }.value
    }
}
