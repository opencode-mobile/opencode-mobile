import SwiftUI
import UIKit

/// A picked screenshot, photo or file as a small rounded square (the prototype's `.att-t`): 40 pt
/// on the draft, with a small ✕ that removes it; 24 pt in Waiting to send. While an image is
/// prepared it shows a spinner (no percentage); dimmed when it won't be sent. A **file** shows the
/// `doc` icon (a video `play`) with its extension in `mono` capitals under it, on `tint(6)`; while
/// it uploads, a determinate ring on a dark scrim. The small size shows the icon only.
struct HexThumbnail: View {
    enum Size {
        /// 40 pt, radius 10: the draft.
        case regular
        /// 24 pt, radius 5: a row in Waiting to send.
        case small

        var side: CGFloat { self == .regular ? 40 : 24 }
        var radius: CGFloat { self == .regular ? HexRadius.m : HexRadius.xs }
    }

    /// A file tile's face.
    struct FileFace: Equatable {
        /// "PDF", "ZIP": at most 4 capitals; "" for none.
        var ext: String
        var isVideo = false
    }

    private enum Content {
        /// Nil while preparing.
        case image(UIImage?)
        case file(FileFace)
    }

    private let content: Content
    var size: Size = .regular
    var dimmed = false
    /// A file's upload, 0 to 1; nil when it isn't uploading.
    var progress: Double?
    /// The ✕; none when nil.
    var onRemove: (() -> Void)?
    var removeLabel: Text = Text(LocalizedStringResource("home.attach.remove", defaultValue: "Remove image"))
    /// What a screen reader calls the tile; nil leaves it to the image.
    var label: Text?

    init(image: UIImage?, size: Size = .regular, dimmed: Bool = false, onRemove: (() -> Void)? = nil, removeLabel: Text? = nil) {
        content = .image(image)
        self.size = size
        self.dimmed = dimmed
        self.onRemove = onRemove
        if let removeLabel { self.removeLabel = removeLabel }
    }

    /// A file tile: reads "<name>, file", its ✕ "Remove <name>".
    init(file: FileFace, name: String, size: Size = .regular, progress: Double? = nil, onRemove: (() -> Void)? = nil) {
        content = .file(file)
        self.size = size
        self.progress = progress
        self.onRemove = onRemove
        removeLabel = Text(verbatim: String(localized: "home.attach.remove_file", defaultValue: "Remove \(name)"))
        label = Text(verbatim: String(localized: "home.attach.file_label", defaultValue: "\(name), file"))
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size.radius, style: .continuous)
        ZStack {
            shape.fill(HexColor.tint(6))
            switch content {
            case let .image(image):
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: size.side, height: size.side)
                        .clipShape(shape)
                } else {
                    HexSpinner(size: size == .regular ? HexIconSize.regular : HexIconSize.small)
                }
            case let .file(face):
                fileFace(face)
            }
            if let progress {
                // The upload: a dark scrim and a ring that fills.
                shape.fill(Color.black.opacity(0.45))
                HexUploadRing(progress: progress, diameter: size == .regular ? 22 : 14)
            }
            shape.strokeBorder(HexColor.tint(12), lineWidth: 1)
        }
        .frame(width: size.side, height: size.side)
        .opacity(dimmed ? 0.45 : 1)
        .accessibilityElement(children: .ignore)
        .modifier(OptionalLabel(label: label))
        .overlay(alignment: .topTrailing) {
            if let onRemove {
                Button(action: onRemove) {
                    HexIconView(.x, size: 8)
                        .foregroundStyle(HexColor.text2)
                        .frame(width: 17, height: 17)
                        .background(Circle().fill(HexColor.panel3))
                        .overlay(Circle().strokeBorder(HexColor.line2, lineWidth: 1))
                        // A bigger target than the mark, reaching out past the corner.
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .offset(x: 11, y: -11)
                .accessibilityLabel(removeLabel)
            }
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func fileFace(_ face: FileFace) -> some View {
        let icon: HexIcon = face.isVideo ? .play : .doc
        if size == .small || face.ext.isEmpty {
            HexIconView(icon, size: size == .regular ? 16 : 12)
                .foregroundStyle(HexColor.text2)
        } else {
            VStack(spacing: 2) {
                HexIconView(icon, size: 14)
                    .foregroundStyle(HexColor.text2)
                Text(verbatim: face.ext)
                    .hexFont(HexTextStyle(size: 8.5, weight: .semibold, isMonospaced: true))
                    .foregroundStyle(HexColor.text3)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .padding(.horizontal, 2)
        }
    }
}

/// The upload ring (the prototype's `.att-t .ring`): a faint track and a 2.5 pt arc that fills
/// clockwise from the top.
struct HexUploadRing: View {
    let progress: Double
    var diameter: CGFloat = 22

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.25), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: max(0.02, min(1, progress)))
                .stroke(Color.white, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: diameter, height: diameter)
        .hexAnimation(.linear(duration: 0.2), value: progress)
    }
}

private struct OptionalLabel: ViewModifier {
    let label: Text?

    func body(content: Content) -> some View {
        if let label {
            content.accessibilityLabel(label)
        } else {
            content
        }
    }
}
