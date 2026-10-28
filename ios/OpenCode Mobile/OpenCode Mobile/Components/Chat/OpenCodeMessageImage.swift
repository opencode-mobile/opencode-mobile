import SwiftUI
import UIKit

private struct AttachmentImageLoadingKey: EnvironmentKey {
    static let defaultValue: (any AttachmentImageLoading)? = nil
}

private struct AttachmentImageDirectoryKey: EnvironmentKey {
    static let defaultValue: String? = nil
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
}

/// An image attachment shown inline in the timeline: aspect ratio kept, height capped, never
/// cropped to a square. Tapping opens `OpenCodeImageViewer`. While the picture loads, or when it
/// cannot load, the attachment shows as its chip; a failed load is not an error.
struct OpenCodeMessageImage: View {
    /// Tall pictures stop here so a phone screenshot does not take over the thread.
    static let maxHeight: CGFloat = 220

    let attachment: OpenCodeAttachmentPresentation

    @Environment(\.attachmentImageLoading) private var loader
    @Environment(\.attachmentImageDirectory) private var directory
    @State private var loaded: UIImage?
    @State private var showsViewer = false

    private var displayed: UIImage? { loaded ?? attachment.image }

    var body: some View {
        content
            .task(id: attachment.id) {
                guard loaded == nil, let file = attachment.imageFile, let loader else { return }
                loaded = await loader.image(for: file, directory: directory)
            }
    }

    @ViewBuilder
    private var content: some View {
        if let image = displayed, image.size.width > 0, image.size.height > 0 {
            CappedAspectLayout(aspect: image.size.width / image.size.height, maxHeight: Self.maxHeight) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(RoundedRectangle(cornerRadius: OpenCodeRadius.small))
                    .overlay(RoundedRectangle(cornerRadius: OpenCodeRadius.small).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
            }
            .contentShape(Rectangle())
            .onTapGesture { showsViewer = true }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Image, \(attachment.filename)")
            .accessibilityAddTraits(.isButton)
            .fullScreenCover(isPresented: $showsViewer) {
                OpenCodeImageViewer(image: image, filename: attachment.filename) { showsViewer = false }
            }
        } else {
            OpenCodeMessageAttachment(
                attachment: OpenCodeAttachmentPresentation(
                    id: attachment.id,
                    filename: attachment.filename,
                    variant: .image,
                    image: nil,
                    detail: attachment.detail
                )
            ).chip
        }
    }
}

/// Sizes its one child to the picture's aspect ratio, no taller than `maxHeight` and no wider
/// than the space offered.
private struct CappedAspectLayout: Layout {
    let aspect: CGFloat
    let maxHeight: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let widestAtCap = maxHeight * aspect
        let offered = proposal.width.map { $0.isFinite ? $0 : widestAtCap } ?? widestAtCap
        let width = min(offered, widestAtCap)
        return CGSize(width: width, height: width / aspect)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}

/// Full-screen image viewer: the whole picture letterboxed on black. Tapping outside the picture
/// or the close control dismisses; tapping the picture itself does nothing.
struct OpenCodeImageViewer: View {
    let image: UIImage
    let filename: String
    let onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture(perform: onDismiss)
                .accessibilityHidden(true)
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .onTapGesture {}
                .accessibilityLabel("Image, \(filename)")
        }
        .overlay(alignment: .topTrailing) {
            Button(action: onDismiss) {
                OpenCodeIconImage(icon: .close, size: 24)
                    .foregroundStyle(OpenCodeColors.iconStrongBase)
                    .frame(width: 44, height: 44)
                    .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
            }
            .buttonStyle(.plain)
            .padding(12)
            .accessibilityLabel("Close")
        }
    }
}
