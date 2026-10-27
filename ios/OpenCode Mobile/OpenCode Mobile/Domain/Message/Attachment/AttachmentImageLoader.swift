import Foundation
import ImageIO
import UIKit

/// Loads the picture behind an image attachment for the timeline and the full-screen viewer:
/// decoded from an inline data URL, or fetched from the server through `GET /api/fs/read/*`.
/// A failure returns nil so the timeline keeps the chip, with no error (#644).
nonisolated protocol AttachmentImageLoading: Sendable {
    func image(for file: FilePart, directory: String?) async -> UIImage?
}

nonisolated final class AttachmentImageLoader: AttachmentImageLoading, @unchecked Sendable {
    static let maxPixelSize = 1600
    static let maxServerBytes = 64 * 1024 * 1024

    private let fetch: @Sendable (_ path: String, _ directory: String?) async throws -> Data
    private let cache = NSCache<NSString, UIImage>()

    init(fetch: @escaping @Sendable (_ path: String, _ directory: String?) async throws -> Data) {
        self.fetch = fetch
        cache.totalCostLimit = 96 * 1024 * 1024
    }

    convenience init(filesystem: FilesystemAPIClient) {
        self.init { path, directory in
            let body = try await filesystem.readServerFileV2(
                path: path,
                directory: directory,
                limit: AttachmentImageLoader.maxServerBytes
            )
            guard !body.exceededLimit else { throw URLError(.dataLengthExceedsMaximum) }
            return body.data
        }
    }

    func image(for file: FilePart, directory: String?) async -> UIImage? {
        guard file.kind == .image else { return nil }
        let key = cacheKey(for: file, directory: directory)
        if let key, let cached = cache.object(forKey: key) { return cached }
        let data: Data?
        if let path = file.serverPath {
            data = try? await fetch(path, directory)
        } else {
            data = AttachmentDataURL.decodedData(from: file.dataURL, expectedMime: file.mime)
        }
        guard let data, let image = Self.downsampled(data) else { return nil }
        if let key {
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * 4))
        }
        return image
    }

    private func cacheKey(for file: FilePart, directory: String?) -> NSString? {
        if let path = file.serverPath { return "s|\(directory ?? "")|\(path)" as NSString }
        guard !file.dataURL.isEmpty else { return nil }
        return "d|\(file.id)|\(file.dataURL.utf8.count)" as NSString
    }

    static func downsampled(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
