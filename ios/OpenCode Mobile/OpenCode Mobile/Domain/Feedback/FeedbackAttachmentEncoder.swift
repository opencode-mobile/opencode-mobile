import CoreGraphics
import Foundation
import UIKit

/// Screenshot encoding for reports: hidden areas burned in, longest side at most
/// 1080 px, JPEG at quality 0.7 lowered step by step until it fits in 1 MB.
nonisolated enum FeedbackScreenshotEncoder {
    static let maxDimension: CGFloat = 1_080
    static let initialQuality: CGFloat = 0.7
    static let qualityStep: CGFloat = 0.1
    static let minimumQuality: CGFloat = 0.1

    /// Hide-parts box, as a fraction of the image width (140 × 44 pt on a 390 pt screen).
    static let hiddenBoxWidthFraction: CGFloat = 140.0 / 390.0
    static let hiddenBoxAspect: CGFloat = 44.0 / 140.0

    static func encode(
        _ screenshot: FeedbackScreenshot,
        maxBytes: Int = FeedbackAttachmentFile.maxBytes
    ) -> Data? {
        guard let image = UIImage(data: screenshot.imageData) else { return nil }
        var dimension = maxDimension
        while dimension >= 240 {
            let rendered = render(image, hiddenRects: screenshot.hiddenRects, maxDimension: dimension)
            var quality = initialQuality
            while true {
                if let data = rendered.jpegData(compressionQuality: quality), data.count <= maxBytes {
                    return data
                }
                guard quality - qualityStep >= minimumQuality - 0.001 else { break }
                quality -= qualityStep
            }
            dimension *= 0.75
        }
        return nil
    }

    /// The image scaled to fit `maxDimension` pixels with every hidden area
    /// filled black. Also used for the on-screen preview.
    static func render(_ image: UIImage, hiddenRects: [CGRect], maxDimension: CGFloat = maxDimension) -> UIImage {
        let pixelSize = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        let size = targetSize(for: pixelSize, maxDimension: maxDimension)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            image.draw(in: CGRect(origin: .zero, size: size))
            UIColor.black.setFill()
            for rect in hiddenRects {
                context.fill(
                    CGRect(
                        x: rect.minX * size.width,
                        y: rect.minY * size.height,
                        width: rect.width * size.width,
                        height: rect.height * size.height
                    )
                )
            }
        }
    }

    static func targetSize(for pixelSize: CGSize, maxDimension: CGFloat = maxDimension) -> CGSize {
        let longest = max(pixelSize.width, pixelSize.height)
        guard longest > maxDimension, longest > 0 else {
            return CGSize(width: max(1, pixelSize.width.rounded()), height: max(1, pixelSize.height.rounded()))
        }
        let scale = maxDimension / longest
        return CGSize(
            width: max(1, (pixelSize.width * scale).rounded()),
            height: max(1, (pixelSize.height * scale).rounded())
        )
    }

    /// The box a tap at `point` (unit coordinates) covers, kept inside the image.
    static func hiddenRect(centeredAt point: CGPoint, imageSize: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return .zero }
        let width = min(1, hiddenBoxWidthFraction)
        let height = min(1, width * hiddenBoxAspect * imageSize.width / imageSize.height)
        let x = min(max(0, point.x - width / 2), 1 - width)
        let y = min(max(0, point.y - height / 2), 1 - height)
        return CGRect(x: x, y: y, width: width, height: height)
    }
}

/// Diagnostic log attachment: the redacted JSONL export, gzipped. Falls back to
/// a shorter window when the chosen one is over 1 MB.
nonisolated enum FeedbackLogEncoder {
    static func encode(
        buffer: DiagnosticLogBuffer,
        window: DiagnosticLogExportWindow,
        maxBytes: Int = FeedbackAttachmentFile.maxBytes
    ) -> Data? {
        let windows = DiagnosticLogExportWindow.allCases
            .filter { $0.minutes <= window.minutes }
            .sorted { $0.minutes > $1.minutes }
        for candidate in windows {
            guard let jsonl = try? buffer.exportJSONL(window: candidate),
                  let gzipped = try? FeedbackGzip.compress(jsonl)
            else { continue }
            if gzipped.count <= maxBytes {
                return gzipped
            }
        }
        return nil
    }
}

/// Turns a draft into a submission: encodes the screenshot and log off the main actor.
nonisolated enum FeedbackSubmissionAssembler {
    static func assemble(
        draft: FeedbackDraft,
        metadata: FeedbackMetadata,
        reportID: String,
        logBuffer: DiagnosticLogBuffer
    ) -> FeedbackSubmission {
        var attachments: [FeedbackAttachmentFile] = []
        if let screenshot = draft.screenshot, let data = FeedbackScreenshotEncoder.encode(screenshot) {
            attachments.append(.screenshot(data))
        }
        if draft.includeLogs, let data = FeedbackLogEncoder.encode(buffer: logBuffer, window: draft.logWindow) {
            attachments.append(.logs(data))
        }
        return FeedbackSubmission(
            reportID: reportID,
            kind: draft.kind,
            message: String(draft.trimmedMessage.prefix(FeedbackDraft.messageLimit)),
            metadata: metadata,
            attachments: attachments
        )
    }
}
