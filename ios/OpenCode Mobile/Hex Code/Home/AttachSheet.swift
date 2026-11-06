import PhotosUI
import SwiftUI
import UIKit

/// Where the Attach sheet goes next, once it has closed.
enum AttachRoute: Equatable {
    case photos
    case camera
}

/// The paperclip's sheet (app plan, "Attach"): Photos (the system photo picker) and Take photo
/// (the system camera). No in-app grid: neither needs photo library access.
struct AttachSheet: View {
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
                HexRow(icon: .img, LocalizedStringResource("home.attach.photos", defaultValue: "Photos")) {
                    onPick(.photos)
                }
                // Dimmed where there is no camera (the simulator).
                HexRow(icon: .scan, LocalizedStringResource("home.attach.camera", defaultValue: "Take photo")) {
                    onPick(.camera)
                }
                .disabled(!CameraPicker.isAvailable)
                .opacity(CameraPicker.isAvailable ? 1 : 0.35)
            }
        }
    }

    /// Two rows under the head.
    static let height: CGFloat = 176
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

extension View {
    /// The system photo picker for the Attach sheet's Photos: images and screenshots, `.compatible`
    /// encoding (JPEG in place of HEIC), up to `limit`, in the order picked.
    func attachPhotosPicker(isPresented: Binding<Bool>, limit: Int, onPicked: @escaping ([PickedImage]) -> Void) -> some View {
        modifier(AttachPhotosPicker(isPresented: isPresented, limit: limit, onPicked: onPicked))
    }
}

private struct AttachPhotosPicker: ViewModifier {
    @Binding var isPresented: Bool
    let limit: Int
    let onPicked: ([PickedImage]) -> Void

    @State private var selection: [PhotosPickerItem] = []

    func body(content: Content) -> some View {
        content
            .photosPicker(
                isPresented: $isPresented,
                selection: $selection,
                maxSelectionCount: max(1, limit),
                selectionBehavior: .ordered,
                matching: .any(of: [.screenshots, .images]),
                preferredItemEncoding: .compatible
            )
            .onChange(of: selection) { _, items in
                guard !items.isEmpty else { return }
                selection = []
                onPicked(items.map(PickedImage.init(item:)))
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
