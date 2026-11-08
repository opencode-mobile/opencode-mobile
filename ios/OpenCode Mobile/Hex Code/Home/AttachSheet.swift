import CoreTransferable
import os
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Where the Attach sheet goes next, once it has closed.
enum AttachRoute: Equatable {
    case photos
    case camera
    case files
}

/// The paperclip's sheet (app plan, "Attach files (M7)"): Photos and videos (the system photo
/// picker), Take photo (the system camera) and Files (the system file picker). No in-app grid:
/// none of them needs photo library or file access.
struct AttachSheet: View {
    /// Files is on: the server declares the upload route and `paths.tmp`.
    var filesAvailable = true
    let onPick: (AttachRoute) -> Void
    let onClose: () -> Void

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("home.attach.title", defaultValue: "Attach")),
            leading: { EmptyView() },
            trailing: {
                HexButton(LocalizedStringResource("common.close", defaultValue: "Close"), action: onClose)
            }
        ) {
            VStack(spacing: 0) {
                HexRow(icon: .img, LocalizedStringResource("home.attach.photos", defaultValue: "Photos and videos")) {
                    onPick(.photos)
                }
                // Dimmed where there is no camera (the simulator).
                HexRow(icon: .scan, LocalizedStringResource("home.attach.camera", defaultValue: "Take photo")) {
                    onPick(.camera)
                }
                .disabled(!CameraPicker.isAvailable)
                .opacity(CameraPicker.isAvailable ? 1 : 0.35)
                // Dimmed, saying why, on a server without the upload route or `paths.tmp`.
                HexRow(
                    icon: .doc,
                    LocalizedStringResource("home.attach.files", defaultValue: "Files"),
                    value: filesAvailable ? nil : String(localized: "home.attach.files.unavailable", defaultValue: "Needs a newer OpenCode")
                ) {
                    onPick(.files)
                }
                .disabled(!filesAvailable)
                .opacity(filesAvailable ? 1 : 0.35)
            }
        }
    }

    /// Three rows under the head.
    static let height: CGFloat = 232
}

nonisolated extension PickedImage {
    /// A photo picker item: a PNG is a screenshot (iOS saves them as PNG), anything else a photo.
    init(item: PhotosPickerItem) {
        let png = item.supportedContentTypes.first?.conforms(to: .png) ?? false
        self.init(kind: png ? .screenshot : .photo) {
            guard let data = try await item.loadTransferable(type: Data.self) else { throw Unreadable() }
            return .data(data)
        }
    }

    /// A photo just taken.
    init(photo: UIImage) {
        self.init(kind: .photo) { .image(photo) }
    }
}

nonisolated extension PickedAttachment {
    /// Photo picker items, in order: a video is a file (its exact bytes, uploaded), named "Video
    /// 1.mov", "Video 2.mp4"…; anything else an image.
    static func from(_ items: [PhotosPickerItem]) -> [PickedAttachment] {
        var videos = 0
        return items.map { item in
            guard let movie = item.supportedContentTypes.first(where: { $0.conforms(to: .movie) || $0.conforms(to: .video) }) else {
                return .image(PickedImage(item: item))
            }
            videos += 1
            return .file(PickedFile(video: item, type: movie, number: videos))
        }
    }
}

nonisolated extension PickedFile {
    /// A video from Photos and videos: the picker's movie file, exported as is (`.mov` or `.mp4`)
    /// into the cache. Photos gives no file name, so it is "Video <n>.<ext>".
    init(video item: PhotosPickerItem, type: UTType, number: Int) {
        let ext = type.preferredFilenameExtension ?? "mov"
        self.init(name: "Video \(number).\(ext)", mime: Self.mime(type), kind: .video) {
            guard let movie = try await item.loadTransferable(type: PickedMovie.self) else { throw Unreadable() }
            return movie.file
        }
    }
}

/// A movie from the photo picker, copied into the cache as it is.
nonisolated struct PickedMovie: Transferable {
    let file: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            PickedMovie(file: try PickedFile.copyIntoCache(received.file, name: received.file.lastPathComponent))
        }
    }
}

extension View {
    /// The system photo picker for the Attach sheet's Photos and videos: images, screenshots and
    /// videos, `.compatible` encoding (JPEG in place of HEIC), up to `limit`, in the order picked.
    func attachPhotosPicker(isPresented: Binding<Bool>, limit: Int, onPicked: @escaping ([PickedAttachment]) -> Void) -> some View {
        modifier(AttachPhotosPicker(isPresented: isPresented, limit: limit, onPicked: onPicked))
    }

    /// The system file picker for the Attach sheet's Files: any file but not folders, several at
    /// once, each copied into the cache as soon as it is picked. No permission.
    func attachFilesPicker(isPresented: Binding<Bool>, onPicked: @escaping ([PickedFile]) -> Void) -> some View {
        fileImporter(isPresented: isPresented, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case let .success(urls):
                onPicked(urls.map { PickedFile(url: $0) })
            case let .failure(error):
                HexLog.dictation.error("attach: the file picker failed: \(String(describing: error), privacy: .public)")
            }
        }
    }
}

private struct AttachPhotosPicker: ViewModifier {
    @Binding var isPresented: Bool
    let limit: Int
    let onPicked: ([PickedAttachment]) -> Void

    @State private var selection: [PhotosPickerItem] = []

    func body(content: Content) -> some View {
        content
            .photosPicker(
                isPresented: $isPresented,
                selection: $selection,
                maxSelectionCount: max(1, limit),
                selectionBehavior: .ordered,
                matching: .any(of: [.screenshots, .images, .videos]),
                preferredItemEncoding: .compatible
            )
            .onChange(of: selection) { _, items in
                guard !items.isEmpty else { return }
                selection = []
                onPicked(PickedAttachment.from(items))
            }
    }
}

/// The system camera (`UIImagePickerController`), full screen. The camera usage text covers both
/// pairing and photos.
struct CameraPicker: UIViewControllerRepresentable {
    let onPhoto: (UIImage) -> Void
    let onClose: () -> Void

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker

        init(_ parent: CameraPicker) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onPhoto(image) }
            parent.onClose()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.onClose()
        }
    }
}
