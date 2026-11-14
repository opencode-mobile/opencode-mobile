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

/// Tapping a gallery tile: the whole group, the tapped image, and the message the group came
/// from (so the viewer can fetch a full picture), sent to the Timeline region.
struct SessionChatOpenImageViewerAction {
    let open: ([FilePart], Int, String?) -> Void

    func callAsFunction(_ images: [FilePart], index: Int, messageID: String? = nil) {
        open(images, index, messageID)
    }
}

/// A timeline image group (one or more images) as an `OpenCodeImageGallery`. A tile draws the
/// image's thumbnail when it has one and otherwise loads through the screen's attachment image
/// loader; a tap opens the viewer over the group.
struct SessionChatImageGalleryView: View {
    let images: [FilePart]
    /// The message the images belong to, when the row knows it.
    var messageID: String?

    @Environment(\.attachmentImageLoading) private var loader
    @Environment(\.attachmentImageDirectory) private var directory
    @Environment(\.openImageViewer) private var openImageViewer

    var body: some View {
        OpenCodeImageGallery(
            items: images.map { $0.galleryItem(loader: loader, directory: directory) },
            onOpen: openImageViewer.map { open in { index in open(images, index: index, messageID: messageID) } }
        )
    }
}

extension FilePart {
    /// A gallery tile: its short name, the thumbnail when there is one (the full picture is then
    /// never decoded for the tile), else a source that loads the picture off the main thread
    /// through `loader`. With neither, the tile waits on a placeholder.
    func galleryItem(loader: (any AttachmentImageLoading)?, directory: String?) -> OpenCodeImageGalleryItem {
        let preview = previewData.flatMap(UIImage.init(data:))
        let source = preview == nil && MessageImageLoader.isFull(self) ? imageSource(loader: loader, directory: directory) : nil
        return OpenCodeImageGalleryItem(
            id: id,
            filename: shortFilename,
            preview: preview,
            source: source,
            isPending: preview == nil && source == nil,
            loadKey: galleryLoadKey
        )
    }

    /// A viewer page: the thumbnail at once, and the full picture once this copy carries it.
    func viewerItem(
        loader: (any AttachmentImageLoading)?,
        directory: String?,
        fullState: SessionChatFullImageState
    ) -> OpenCodeImageGalleryItem {
        let isFull = MessageImageLoader.isFull(self)
        let state: OpenCodeImageFullState? = switch fullState {
        case .thumbnail, .loading: .loading
        case .failed: .failed
        case .full: .ready
        }
        return OpenCodeImageGalleryItem(
            id: id,
            filename: shortFilename,
            preview: previewData.flatMap(UIImage.init(data:)),
            source: isFull ? imageSource(loader: loader, directory: directory) : nil,
            fullState: state,
            loadKey: galleryLoadKey
        )
    }

    private var shortFilename: String? {
        let name = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : (name as NSString).lastPathComponent
    }

    /// Changes when this image gains a thumbnail or its full picture under the same id.
    private var galleryLoadKey: String {
        "\(id)|\(dataURL.utf8.count)|\(previewData?.count ?? 0)|\(serverPath ?? "")"
    }

    private func imageSource(loader: (any AttachmentImageLoading)?, directory: String?) -> OpenCodeImageSource? {
        let file = self
        return loader.map { loader in
            OpenCodeImageSource { await loader.image(for: file, directory: directory) }
        }
    }
}

extension View {
    /// Presents the image viewer while the Timeline region has one open.
    func sessionChatImageViewer(
        state: SessionChatImageViewerState?,
        loader: (any AttachmentImageLoading)?,
        directory: String?,
        onIndexChange: @escaping (Int) -> Void,
        onRetry: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        fullScreenCover(
            isPresented: Binding(get: { state != nil }, set: { if !$0 { onDismiss() } })
        ) {
            if let state {
                OpenCodeImageViewer(
                    items: state.images.map {
                        $0.viewerItem(loader: loader, directory: directory, fullState: state.fullState(of: $0.id))
                    },
                    index: state.index,
                    onIndexChange: onIndexChange,
                    onDismiss: onDismiss,
                    onRetry: { _ in onRetry() }
                )
            }
        }
    }
}
