import ImageIO
import UIKit

/// The small JPEG kept for a tool-returned image: what the timeline gallery draws and what the
/// session cache stores on disk instead of the full picture. Built without decoding the whole
/// image; call off the main thread.
nonisolated enum ToolThumbnail {
    /// Long edge of the first attempt, then of the fallback when the first is over `maxBytes`.
    static let dimensions: [CGFloat] = [400, 300]
    static let compressionQuality: CGFloat = 0.7
    static let maxBytes = 64 * 1024

    /// A thumbnail of the picture in `dataURL`, or nil when it does not decode or no attempt
    /// fits in `maxBytes`.
    static func make(dataURL: String, mime: String, maxBytes: Int = maxBytes) -> Data? {
        guard let data = AttachmentDataURL.decodedData(from: dataURL, expectedMime: mime) else { return nil }
        return make(data: data, maxBytes: maxBytes)
    }

    /// Tries each of `dimensions` in turn and keeps the first JPEG within `maxBytes`.
    static func make(data: Data, maxBytes: Int = maxBytes) -> Data? {
        for dimension in dimensions {
            guard let jpeg = render(data: data, maxDimension: dimension) else { return nil }
            if jpeg.count <= maxBytes { return jpeg }
        }
        return nil
    }

    /// A JPEG of `data` no longer than `maxDimension` on its long edge, on white (JPEG has no
    /// alpha; transparent screenshots would otherwise turn black).
    static func render(data: Data, maxDimension: CGFloat) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              cgImage.width > 0, cgImage.height > 0
        else { return nil }
        let size = CGSize(width: cgImage.width, height: cgImage.height)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: cgImage).draw(in: CGRect(origin: .zero, size: size))
        }
        return rendered.jpegData(compressionQuality: compressionQuality)
    }
}
