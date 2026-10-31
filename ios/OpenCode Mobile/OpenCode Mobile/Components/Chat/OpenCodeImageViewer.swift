import SwiftUI
import UIKit

/// Full-screen viewer over a group of images on black: pages left and right, pinch or
/// double-tap to zoom, swipe down or the close control to dismiss, share through the system
/// share sheet (which also saves to Photos). The caller owns the shown page: `index` in,
/// `onIndexChange` out.
struct OpenCodeImageViewer: View {
    /// Dragging the picture down this far (or flicking it) dismisses the viewer.
    static let dismissDistance: CGFloat = 120
    static let dismissVelocity: CGFloat = 800

    let items: [OpenCodeImageGalleryItem]
    let index: Int
    let onIndexChange: (Int) -> Void
    let onDismiss: () -> Void

    @State private var dragOffset: CGFloat = 0
    @State private var pictures: [String: UIImage] = [:]
    @State private var sharing: UIImage?

    init(
        items: [OpenCodeImageGalleryItem],
        index: Int,
        onIndexChange: @escaping (Int) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.items = items
        self.index = index
        self.onIndexChange = onIndexChange
        self.onDismiss = onDismiss
    }

    private var current: Int { min(max(index, 0), max(items.count - 1, 0)) }
    private var currentItem: OpenCodeImageGalleryItem? { items.indices.contains(current) ? items[current] : nil }
    private var currentPicture: UIImage? { currentItem.flatMap { pictures[$0.id] ?? $0.preview } }

    /// The black backdrop fades as the picture is dragged toward dismissal.
    private var backdropOpacity: Double {
        1 - min(Double(dragOffset / (Self.dismissDistance * 3)), 0.6)
    }

    var body: some View {
        ZStack {
            Color.black
                .opacity(backdropOpacity)
                .ignoresSafeArea()
            TabView(selection: Binding(get: { current }, set: { onIndexChange($0) })) {
                ForEach(Array(items.enumerated()), id: \.element.id) { position, item in
                    OpenCodeImageViewerPage(
                        item: item,
                        accessibilityLabel: OpenCodeImageGalleryItem.accessibilityLabel(
                            index: position,
                            count: items.count,
                            filename: item.filename
                        ),
                        onLoaded: { pictures[item.id] = $0 },
                        onDismissDrag: { dragOffset = max(0, $0) },
                        onDismissDragEnd: { translation, velocity in
                            if translation > Self.dismissDistance || velocity > Self.dismissVelocity {
                                onDismiss()
                            } else {
                                withAnimation(.spring(duration: 0.25)) { dragOffset = 0 }
                            }
                        }
                    )
                    .tag(position)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .offset(y: dragOffset)
            .ignoresSafeArea()
        }
        .overlay(alignment: .top) { topBar.opacity(dragOffset > 0 ? 0 : 1) }
        .overlay(alignment: .bottom) { bottomBar.opacity(dragOffset > 0 ? 0 : 1) }
        .statusBarHidden()
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) {
            if let sharing {
                ActivityShareView(items: [sharing])
                    .presentationDetents([.medium, .large])
            }
        }
        .accessibilityAction(.escape, onDismiss)
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 12) {
            roundButton(label: "Close", action: onDismiss) {
                OpenCodeIconImage(icon: .close, size: 22)
            }
            VStack(spacing: 2) {
                if items.count > 1 {
                    Text("\(current + 1) of \(items.count)")
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(Color.white)
                }
                if let filename = currentItem?.filename {
                    Text(filename)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(Color.white.opacity(0.7))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            // Balances the close control so the label stays centred.
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    private var bottomBar: some View {
        HStack {
            Spacer()
            roundButton(label: "Share", action: { sharing = currentPicture }) {
                // Native share glyph: the Lucide catalog has no share icon yet.
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 18, weight: .regular))
            }
            .disabled(currentPicture == nil)
            .opacity(currentPicture == nil ? 0.4 : 1)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    private func roundButton<Label: View>(
        label: String,
        action: @escaping () -> Void,
        @ViewBuilder icon: () -> Label
    ) -> some View {
        Button(action: action) {
            icon()
                .foregroundStyle(Color.white)
                .frame(width: 44, height: 44)
                .background(Color.white.opacity(0.14), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

extension OpenCodeImageViewer {
    /// One picture already in memory, with no paging.
    init(image: UIImage, filename: String, onDismiss: @escaping () -> Void) {
        self.init(
            items: [OpenCodeImageGalleryItem(id: "image", filename: filename, preview: image)],
            index: 0,
            onIndexChange: { _ in },
            onDismiss: onDismiss
        )
    }
}

/// One page: the picture zoomable in place, a placeholder while it loads, or a failure note.
private struct OpenCodeImageViewerPage: View {
    let item: OpenCodeImageGalleryItem
    let accessibilityLabel: String
    let onLoaded: (UIImage) -> Void
    let onDismissDrag: (CGFloat) -> Void
    let onDismissDragEnd: (_ translation: CGFloat, _ velocity: CGFloat) -> Void

    @State private var loaded: UIImage?
    @State private var failed = false

    private var displayed: UIImage? { loaded ?? item.preview }

    var body: some View {
        Group {
            if let image = displayed {
                OpenCodeZoomableImage(
                    image: image,
                    onDismissDrag: onDismissDrag,
                    onDismissDragEnd: onDismissDragEnd
                )
                .accessibilityElement()
                .accessibilityLabel(accessibilityLabel)
                .accessibilityAddTraits(.isImage)
            } else if failed {
                VStack(spacing: 6) {
                    Text("Could not load this image")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(Color.white)
                    if let filename = item.filename {
                        Text(filename)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(Color.white.opacity(0.7))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityElement(children: .combine)
            } else {
                ProgressView()
                    .tint(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel("\(accessibilityLabel), loading")
            }
        }
        .task(id: item.id) {
            if let preview = item.preview { onLoaded(preview) }
            guard loaded == nil, let source = item.source else {
                failed = item.preview == nil
                return
            }
            if let image = await source.load() {
                loaded = image
                onLoaded(image)
            } else {
                failed = item.preview == nil
            }
        }
    }
}

/// A picture in a scroll view that zooms by pinch or double tap and reports a downward drag
/// (only while not zoomed) so the viewer can dismiss.
private struct OpenCodeZoomableImage: UIViewRepresentable {
    let image: UIImage
    let onDismissDrag: (CGFloat) -> Void
    let onDismissDragEnd: (_ translation: CGFloat, _ velocity: CGFloat) -> Void

    func makeUIView(context: Context) -> OpenCodeZoomingImageScrollView {
        let view = OpenCodeZoomingImageScrollView()
        view.setImage(image)
        return view
    }

    func updateUIView(_ view: OpenCodeZoomingImageScrollView, context: Context) {
        if view.imageView.image !== image { view.setImage(image) }
        view.onDismissDrag = onDismissDrag
        view.onDismissDragEnd = onDismissDragEnd
    }
}

final class OpenCodeZoomingImageScrollView: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    static let maximumZoom: CGFloat = 4
    static let doubleTapZoom: CGFloat = 2.5

    let imageView = UIImageView()
    var onDismissDrag: ((CGFloat) -> Void)?
    var onDismissDragEnd: ((CGFloat, CGFloat) -> Void)?
    private var fittedBoundsSize: CGSize = .zero
    private lazy var dismissPan = UIPanGestureRecognizer(target: self, action: #selector(handleDismissPan(_:)))

    init() {
        super.init(frame: .zero)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = Self.maximumZoom
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .fast
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        addSubview(imageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
        dismissPan.delegate = self
        addGestureRecognizer(dismissPan)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setImage(_ image: UIImage) {
        // A sharper version of the picture already on screen (preview, then the decoded full image)
        // swaps in place, so a pinch or double tap made while it loaded keeps its zoom and offset.
        if zoomScale > minimumZoomScale, let previous = imageView.image, Self.sameAspect(previous.size, image.size) {
            imageView.image = image
            return
        }
        imageView.image = image
        zoomScale = 1
        fittedBoundsSize = .zero
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale <= minimumZoomScale, bounds.size != fittedBoundsSize, let image = imageView.image {
            fittedBoundsSize = bounds.size
            let fitted = Self.fittedSize(image.size, in: bounds.size)
            imageView.frame = CGRect(origin: .zero, size: fitted)
            contentSize = fitted
        }
        centerImage()
    }

    static func sameAspect(_ lhs: CGSize, _ rhs: CGSize) -> Bool {
        guard lhs.width > 0, lhs.height > 0, rhs.width > 0, rhs.height > 0 else { return false }
        return abs(lhs.width / lhs.height - rhs.width / rhs.height) < 0.01
    }

    static func fittedSize(_ size: CGSize, in bounds: CGSize) -> CGSize {
        guard size.width > 0, size.height > 0, bounds.width > 0, bounds.height > 0 else { return .zero }
        let scale = min(bounds.width / size.width, bounds.height / size.height)
        return CGSize(width: size.width * scale, height: size.height * scale)
    }

    private func centerImage() {
        let horizontal = max((bounds.width - contentSize.width) / 2, 0)
        let vertical = max((bounds.height - contentSize.height) / 2, 0)
        let inset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
        if contentInset != inset { contentInset = inset }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) { centerImage() }

    @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
        if zoomScale > minimumZoomScale {
            setZoomScale(minimumZoomScale, animated: true)
            return
        }
        let point = recognizer.location(in: imageView)
        let size = CGSize(width: bounds.width / Self.doubleTapZoom, height: bounds.height / Self.doubleTapZoom)
        zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
    }

    @objc private func handleDismissPan(_ recognizer: UIPanGestureRecognizer) {
        let translation = recognizer.translation(in: self).y
        switch recognizer.state {
        case .changed:
            onDismissDrag?(translation)
        case .ended, .cancelled, .failed:
            onDismissDragEnd?(translation, recognizer.velocity(in: self).y)
        default:
            break
        }
    }

    override func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        guard recognizer === dismissPan else { return super.gestureRecognizerShouldBegin(recognizer) }
        guard zoomScale <= minimumZoomScale + 0.01 else { return false }
        let velocity = dismissPan.velocity(in: self)
        return velocity.y > 0 && velocity.y > abs(velocity.x)
    }
}
