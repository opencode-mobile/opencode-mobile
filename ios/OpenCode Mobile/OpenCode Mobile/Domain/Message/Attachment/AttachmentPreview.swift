import ImageIO
import UIKit

nonisolated enum AttachmentPreview {
    private static let maxDimension: CGFloat = 256
    private static let maxBytes = 128 * 1024

    nonisolated static func make(data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        return jpeg(from: image)
    }

    /// Thumbnail of an image file without decoding the whole picture.
    nonisolated static func make(fileURL: URL) -> Data? {
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return jpeg(from: UIImage(cgImage: cgImage))
    }

    nonisolated static func jpeg(from image: UIImage) -> Data? {
        let longest = max(image.size.width, image.size.height)
        guard longest > 0 else { return nil }
        let scale = min(1, maxDimension / longest)
        let target = CGSize(
            width: max(1, (image.size.width * scale).rounded(.down)),
            height: max(1, (image.size.height * scale).rounded(.down))
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let jpeg = rendered.jpegData(compressionQuality: 0.82), jpeg.count <= maxBytes else {
            return rendered.jpegData(compressionQuality: 0.82)
        }
        return jpeg
    }
}
