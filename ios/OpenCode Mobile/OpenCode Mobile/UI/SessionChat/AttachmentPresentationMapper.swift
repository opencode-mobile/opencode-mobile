import UIKit

enum AttachmentPresentationMapper {
    private static let maxPreviewDimension: CGFloat = 256
    private static let maxPreviewBytes = 128 * 1024

    static func presentation(from attachment: Attachment) -> OpenCodeAttachmentPresentation {
        OpenCodeAttachmentPresentation(
            id: attachment.id,
            filename: attachment.filename,
            variant: variant(for: attachment.kind),
            image: previewImage(dataURL: attachment.dataURL, mime: attachment.mime, kind: attachment.kind)
        )
    }

    static func presentation(from file: FilePart) -> OpenCodeAttachmentPresentation {
        OpenCodeAttachmentPresentation(
            id: file.id,
            filename: file.filename,
            variant: variant(for: file.kind),
            image: previewImage(dataURL: file.dataURL, mime: file.mime, kind: file.kind)
        )
    }

    private static func variant(for kind: AttachmentKind) -> OpenCodeAttachmentVariant {
        switch kind {
        case .image: .image
        case .pdf: .pdf
        case .text: .text
        }
    }

    private static func previewImage(dataURL: String, mime: String, kind: AttachmentKind) -> UIImage? {
        guard kind == .image,
              let data = AttachmentDataURL.decodedData(from: dataURL, expectedMime: mime),
              let image = UIImage(data: data)
        else { return nil }
        return downsampled(image)
    }

    private static func downsampled(_ image: UIImage) -> UIImage? {
        let longest = max(image.size.width, image.size.height)
        guard longest > 0 else { return nil }
        let scale = min(1, maxPreviewDimension / longest)
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
        guard let jpeg = rendered.jpegData(compressionQuality: 0.82),
              jpeg.count <= maxPreviewBytes
        else { return rendered }
        return UIImage(data: jpeg) ?? rendered
    }
}
