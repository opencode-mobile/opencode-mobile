import SwiftUI
import UIKit

private struct AttachmentImageLoadingKey: EnvironmentKey {
    static let defaultValue: (any AttachmentImageLoading)? = nil
}

private struct AttachmentImageDirectoryKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

private struct OpenImageViewerKey: EnvironmentKey {
    static let defaultValue: SessionChatOpenImageViewerAction? = nil
}

extension EnvironmentValues {
    /// Loads the full picture of a timeline image (inline data or server file).
    var attachmentImageLoading: (any AttachmentImageLoading)? {
        get { self[AttachmentImageLoadingKey.self] }
        set { self[AttachmentImageLoadingKey.self] = newValue }
    }

    /// The session directory, sent as `location[directory]` when reading a server file.
    var attachmentImageDirectory: String? {
        get { self[AttachmentImageDirectoryKey.self] }
        set { self[AttachmentImageDirectoryKey.self] = newValue }
    }

    /// Opens the image viewer over a group of timeline images.
    var openImageViewer: SessionChatOpenImageViewerAction? {
        get { self[OpenImageViewerKey.self] }
        set { self[OpenImageViewerKey.self] = newValue }
    }
}

/// Tapping a gallery tile: the whole group and the tapped image, sent to the Timeline region.
struct SessionChatOpenImageViewerAction {
    let open: ([FilePart], Int) -> Void

    func callAsFunction(_ images: [FilePart], index: Int) {
        open(images, index)
    }
}

/// A timeline image group (one or more images) as an `OpenCodeImageGallery`. Each tile loads
/// through the screen's attachment image loader; a tap opens the viewer over the group.
struct SessionChatImageGalleryView: View {
    let images: [FilePart]

    @Environment(\.attachmentImageLoading) private var loader
    @Environment(\.attachmentImageDirectory) private var directory
    @Environment(\.openImageViewer) private var openImageViewer

    var body: some View {
        OpenCodeImageGallery(
            items: images.map { $0.galleryItem(loader: loader, directory: directory) },
            onOpen: openImageViewer.map { open in { index in open(images, index: index) } }
        )
    }
}

extension FilePart {
    /// The presentation of a timeline image: its short name, any preview already decoded, and
    /// a source that loads the full picture off the main thread through `loader`.
    func galleryItem(loader: (any AttachmentImageLoading)?, directory: String?) -> OpenCodeImageGalleryItem {
        let file = self
        let name = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        return OpenCodeImageGalleryItem(
            id: id,
            filename: name.isEmpty ? nil : (name as NSString).lastPathComponent,
            preview: previewData.flatMap(UIImage.init(data:)),
            source: loader.map { loader in
                OpenCodeImageSource { await loader.image(for: file, directory: directory) }
            }
        )
    }
}

extension View {
    /// Presents the image viewer while the Timeline region has one open.
    func sessionChatImageViewer(
        state: SessionChatImageViewerState?,
        loader: (any AttachmentImageLoading)?,
        directory: String?,
        onIndexChange: @escaping (Int) -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        fullScreenCover(
            isPresented: Binding(get: { state != nil }, set: { if !$0 { onDismiss() } })
        ) {
            if let state {
                OpenCodeImageViewer(
                    items: state.images.map { $0.galleryItem(loader: loader, directory: directory) },
                    index: state.index,
                    onIndexChange: onIndexChange,
                    onDismiss: onDismiss
                )
            }
        }
    }
}
