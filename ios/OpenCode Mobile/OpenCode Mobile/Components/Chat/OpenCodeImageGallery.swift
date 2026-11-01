import SwiftUI
import UIKit

/// How a gallery gets one picture. The caller decides where the bytes come from (inline data,
/// a server file) and how they are decoded; the gallery and viewer only ask for the picture.
struct OpenCodeImageSource: Sendable {
    let load: @Sendable () async -> UIImage?

    init(_ load: @escaping @Sendable () async -> UIImage?) {
        self.load = load
    }
}

/// One picture in an `OpenCodeImageGallery` or `OpenCodeImageViewer`.
struct OpenCodeImageGalleryItem: Identifiable {
    let id: String
    /// Shown as a caption and read by VoiceOver; nil when the name is unknown.
    let filename: String?
    /// A picture already in memory (a small preview or the full image), shown at once.
    let preview: UIImage?
    /// Loads the full picture. With neither a preview nor a source the tile shows as failed.
    let source: OpenCodeImageSource?

    init(id: String, filename: String? = nil, preview: UIImage? = nil, source: OpenCodeImageSource? = nil) {
        self.id = id
        self.filename = filename?.isEmpty == true ? nil : filename
        self.preview = preview
        self.source = source
    }

    /// "Image 2 of 5, login.png": the tile and viewer page label.
    static func accessibilityLabel(index: Int, count: Int, filename: String?) -> String {
        let position = count > 1 ? "Image \(index + 1) of \(count)" : "Image"
        guard let filename else { return position }
        return "\(position), \(filename)"
    }
}

/// Which arrangement a gallery uses for its number of images.
enum OpenCodeImageGalleryLayout: Equatable {
    /// One image at its own aspect ratio, height capped.
    case single
    /// Two equal 3:4 columns.
    case pair
    /// One large tile on the left and two stacked tiles on the right.
    case trio
    /// A 2×2 grid of 4:3 tiles. When `overflow` > 0 the fourth tile shows "+overflow"
    /// and stands for itself and the images not shown.
    case grid(overflow: Int)

    init(count: Int) {
        switch count {
        case ...1: self = .single
        case 2: self = .pair
        case 3: self = .trio
        default: self = .grid(overflow: count > 4 ? count - 3 : 0)
        }
    }

    /// How many tiles the layout draws.
    var tileCount: Int {
        switch self {
        case .single: 1
        case .pair: 2
        case .trio: 3
        case .grid: 4
        }
    }
}

/// Images from one source shown inline in the timeline: one image keeps its aspect ratio
/// (height capped), two to four form a mosaic, more than four show four tiles with "+N" on
/// the last. Each tile loads its own picture when it appears, shows a neutral placeholder while
/// loading and a failure tile with the file name when it cannot load. Tapping a tile that
/// is not failed calls `onOpen` with its index; a failed tile never blocks the others.
struct OpenCodeImageGallery: View {
    /// A single image stops here so a phone screenshot does not take over the thread.
    static let singleMaxHeight: CGFloat = 220
    /// The three-image mosaic is this tall.
    static let trioHeight: CGFloat = 230
    static let spacing: CGFloat = 4
    /// Mosaics stop growing past this width on wide screens.
    static let maxMosaicWidth: CGFloat = 420

    let items: [OpenCodeImageGalleryItem]
    let onOpen: ((Int) -> Void)?

    init(items: [OpenCodeImageGalleryItem], onOpen: ((Int) -> Void)? = nil) {
        self.items = items
        self.onOpen = onOpen
    }

    private var layout: OpenCodeImageGalleryLayout { OpenCodeImageGalleryLayout(count: items.count) }

    var body: some View {
        if items.isEmpty {
            EmptyView()
        } else if layout == .single {
            OpenCodeImageTile(
                item: items[0],
                accessibilityLabel: OpenCodeImageGalleryItem.accessibilityLabel(
                    index: 0,
                    count: 1,
                    filename: items[0].filename
                ),
                shape: .natural(maxHeight: Self.singleMaxHeight),
                overflow: 0,
                action: onOpen.map { open in { open(0) } }
            )
        } else {
            OpenCodeImageMosaicLayout(layout: layout, spacing: Self.spacing, maxWidth: Self.maxMosaicWidth) {
                ForEach(Array(items.prefix(layout.tileCount).enumerated()), id: \.element.id) { index, item in
                    OpenCodeImageTile(
                        item: item,
                        accessibilityLabel: tileLabel(index: index, item: item),
                        shape: .fill,
                        overflow: overflow(at: index),
                        action: onOpen.map { open in { open(index) } }
                    )
                }
            }
        }
    }

    private func overflow(at index: Int) -> Int {
        guard case .grid(let overflow) = layout, index == layout.tileCount - 1 else { return 0 }
        return overflow
    }

    private func tileLabel(index: Int, item: OpenCodeImageGalleryItem) -> String {
        let label = OpenCodeImageGalleryItem.accessibilityLabel(index: index, count: items.count, filename: item.filename)
        let more = overflow(at: index)
        return more > 0 ? "\(label), \(more - 1) more" : label
    }
}

/// Places the tiles of a two-, three- or four-tile mosaic in the width offered.
struct OpenCodeImageMosaicLayout: Layout {
    let layout: OpenCodeImageGalleryLayout
    let spacing: CGFloat
    let maxWidth: CGFloat

    /// Left column of the three-image mosaic against the right one.
    private static let trioRatio: CGFloat = 1.4
    private static let fallbackWidth: CGFloat = 280

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = resolvedWidth(proposal)
        return CGSize(width: width, height: frames(width: width).last.map { $0.maxY } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let frames = frames(width: bounds.width)
        for (subview, frame) in zip(subviews, frames) {
            subview.place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(frame.size)
            )
        }
    }

    private func resolvedWidth(_ proposal: ProposedViewSize) -> CGFloat {
        guard let width = proposal.width, width.isFinite else { return Self.fallbackWidth }
        return min(width, maxWidth)
    }

    /// Tile frames in order; the last frame ends at the mosaic's bottom.
    func frames(width: CGFloat) -> [CGRect] {
        let half = max(0, (width - spacing) / 2)
        switch layout {
        case .single:
            return [CGRect(x: 0, y: 0, width: width, height: width)]
        case .pair:
            let height = half * 4 / 3
            return [
                CGRect(x: 0, y: 0, width: half, height: height),
                CGRect(x: half + spacing, y: 0, width: half, height: height),
            ]
        case .trio:
            let height = OpenCodeImageGallery.trioHeight
            let left = max(0, (width - spacing) * Self.trioRatio / (Self.trioRatio + 1))
            let right = max(0, width - spacing - left)
            let small = max(0, (height - spacing) / 2)
            return [
                CGRect(x: 0, y: 0, width: left, height: height),
                CGRect(x: left + spacing, y: 0, width: right, height: small),
                CGRect(x: left + spacing, y: small + spacing, width: right, height: small),
            ]
        case .grid:
            let height = half * 3 / 4
            return [
                CGRect(x: 0, y: 0, width: half, height: height),
                CGRect(x: half + spacing, y: 0, width: half, height: height),
                CGRect(x: 0, y: height + spacing, width: half, height: height),
                CGRect(x: half + spacing, y: height + spacing, width: half, height: height),
            ]
        }
    }
}

/// One tile: the picture cropped to fill, or a placeholder while loading, or a failure tile.
private struct OpenCodeImageTile: View {
    enum Shape {
        /// The picture's own aspect ratio, no taller than `maxHeight`.
        case natural(maxHeight: CGFloat)
        /// Fills the frame the mosaic gives it.
        case fill
    }

    let item: OpenCodeImageGalleryItem
    let accessibilityLabel: String
    let shape: Shape
    /// "+N" on the tile when > 0.
    let overflow: Int
    let action: (() -> Void)?

    @State private var loaded: UIImage?
    @State private var failed = false

    private var displayed: UIImage? { loaded ?? item.preview }

    var body: some View {
        content
            .task(id: item.id) { await load() }
    }

    private func load() async {
        guard loaded == nil else { return }
        guard let source = item.source else {
            failed = item.preview == nil
            return
        }
        if let image = await source.load() {
            loaded = image
            failed = false
        } else {
            failed = item.preview == nil
        }
    }

    @ViewBuilder
    private var content: some View {
        if let image = displayed, image.size.width > 0, image.size.height > 0 {
            let picture = framed(aspect: image.size.width / image.size.height) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
            .overlay(alignment: .bottomLeading) { caption }
            .overlay { overflowLabel }
            .clipShape(RoundedRectangle(cornerRadius: OpenCodeRadius.small))
            .overlay(RoundedRectangle(cornerRadius: OpenCodeRadius.small).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
            .contentShape(Rectangle())
            if let action {
                Button(action: action) { picture }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(accessibilityLabel)
                    .accessibilityAddTraits(.isButton)
                    .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), activate: action)
            } else {
                picture
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(accessibilityLabel)
                    .accessibilityAddTraits(.isImage)
            }
        } else if failed {
            framed(aspect: 4 / 3) { failureTile }
                .clipShape(RoundedRectangle(cornerRadius: OpenCodeRadius.small))
                .overlay(RoundedRectangle(cornerRadius: OpenCodeRadius.small).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(accessibilityLabel), could not load")
        } else {
            framed(aspect: 4 / 3) { OpenCodeImageLoadingFill() }
                .clipShape(RoundedRectangle(cornerRadius: OpenCodeRadius.small))
                .overlay(RoundedRectangle(cornerRadius: OpenCodeRadius.small).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(accessibilityLabel), loading")
        }
    }

    @ViewBuilder
    private func framed<Content: View>(aspect: CGFloat, @ViewBuilder content: () -> Content) -> some View {
        switch shape {
        case .natural(let maxHeight):
            OpenCodeCappedAspectLayout(aspect: aspect, maxHeight: maxHeight) {
                Color.clear.overlay { content() }.clipped()
            }
        case .fill:
            Color.clear
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay { content() }
                .clipped()
        }
    }

    @ViewBuilder
    private var caption: some View {
        if case .fill = shape, overflow == 0, let filename = item.filename {
            Text(filename)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(Color.white)
                .lineLimit(1)
                .truncationMode(.middle)
                .padding(.horizontal, 6)
                .padding(.top, 12)
                .padding(.bottom, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                )
        }
    }

    @ViewBuilder
    private var overflowLabel: some View {
        if overflow > 0 {
            ZStack {
                Color.black.opacity(0.55)
                Text("+\(overflow)")
                    .font(OpenCodeTypography.title)
                    .foregroundStyle(Color.white)
            }
        }
    }

    private var failureTile: some View {
        VStack(spacing: 4) {
            OpenCodeIconImage(icon: .description, size: 20)
                .foregroundStyle(OpenCodeColors.iconWeakBase)
            Text("Could not load")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
            if let filename = item.filename {
                Text(filename)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.surfaceRaisedBase)
    }
}

/// Neutral placeholder for a picture that is still loading: a soft pulse, still under Reduce Motion.
struct OpenCodeImageLoadingFill: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    var body: some View {
        OpenCodeColors.surfaceRaisedBase
            .overlay(OpenCodeColors.textStrong.opacity(dimmed ? 0.03 : 0.08))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    dimmed = true
                }
            }
    }
}

/// Sizes its one child to the picture's aspect ratio, no taller than `maxHeight` and no wider
/// than the space offered.
struct OpenCodeCappedAspectLayout: Layout {
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

#Preview("Image gallery") {
    let colors: [UIColor] = [.systemTeal, .systemOrange, .systemPurple, .systemBlue, .systemGreen, .systemPink]
    let items = colors.enumerated().map { index, color in
        OpenCodeImageGalleryItem(
            id: "preview-\(index)",
            filename: "capture-\(index + 1).png",
            preview: UIGraphicsImageRenderer(size: CGSize(width: 120, height: 90)).image { context in
                color.setFill()
                context.fill(CGRect(x: 0, y: 0, width: 120, height: 90))
            }
        )
    }
    return ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(1...5, id: \.self) { count in
                OpenCodeImageGallery(items: Array(items.prefix(count + (count == 5 ? 1 : 0))))
            }
        }
        .padding()
    }
    .openCodeTheme()
}
