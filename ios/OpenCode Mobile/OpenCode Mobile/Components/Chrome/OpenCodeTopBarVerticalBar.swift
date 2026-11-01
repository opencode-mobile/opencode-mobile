import SwiftUI
import UIKit

/// Geometry of the iPhone Duo vertical bar, measured against the system bar in the
/// iOS 27.1 iPhone Duo simulator. iOS moves system toolbar items into this rail
/// but never converts custom bars, so `OpenCodeTopBar` draws its icon items here
/// itself.
enum OpenCodeVerticalBarLayout {
    /// Rail items are 48pt glass circles on a 60pt pitch.
    static let itemSize: CGFloat = 48
    static let itemPitch: CGFloat = 60
    /// Top of the first item when nothing occludes the top of the rail
    /// (Closed, turned: first centre at y 48).
    static let topClearance: CGFloat = 24
    /// Space between an occlusion region (status column, camera) and the next item.
    static let occlusionGap: CGFloat = 3.5
    /// The system centres items 6.5pt leading of the rail's midline: x 902 in the
    /// 867–951 rail, 417.5 in 382–466, 629.5 in 594–678.
    static let centreLeadingShift: CGFloat = 6.5

    /// Whether a bar draws its items in the rail. Only a bar at the top of the
    /// window whose frame reaches the trailing inset participates, so a split
    /// view's sidebar column (no trailing inset) keeps its horizontal bar.
    static func participates(
        verticalEdge: HorizontalEdge?,
        trailingInset: CGFloat,
        topInWindow: CGFloat
    ) -> Bool {
        verticalEdge == .trailing && trailingInset > 0 && topInWindow <= 1
    }

    /// Frames of `count` rail items, top to bottom, in the same space as
    /// `railMinX` and `occlusions`. Items start at `topClearance` and skip every
    /// occlusion region that overlaps the rail horizontally.
    static func itemFrames(
        count: Int,
        railMinX: CGFloat,
        railWidth: CGFloat,
        occlusions: [CGRect]
    ) -> [CGRect] {
        guard count > 0, railWidth > 0 else { return [] }
        let railMaxX = railMinX + railWidth
        let blockers = occlusions
            .filter { !$0.isEmpty && $0.maxX > railMinX && $0.minX < railMaxX }
            .sorted { $0.minY < $1.minY }
        let centreX = railMinX + railWidth / 2 - centreLeadingShift

        var frames: [CGRect] = []
        var top = topClearance
        for _ in 0..<count {
            top = firstClearTop(from: top, blockers: blockers)
            frames.append(
                CGRect(x: centreX - itemSize / 2, y: top, width: itemSize, height: itemSize)
            )
            top += itemPitch
        }
        return frames
    }

    /// Status column height to assume when the window reports no occlusion
    /// regions: 120pt on the open inner display, 170pt on the closed outer display,
    /// none when the closed display is turned.
    static func fallbackStatusColumnHeight(
        horizontalSizeClass: UserInterfaceSizeClass?,
        verticalSizeClass: UserInterfaceSizeClass?
    ) -> CGFloat {
        if verticalSizeClass == .compact { return 0 }
        if horizontalSizeClass == .regular { return 120 }
        return 170
    }

    private static func firstClearTop(from start: CGFloat, blockers: [CGRect]) -> CGFloat {
        var top = start
        var moved = true
        while moved {
            moved = false
            for blocker in blockers
            where blocker.minY < top + itemSize + occlusionGap && blocker.maxY + occlusionGap > top {
                top = blocker.maxY + occlusionGap
                moved = true
            }
        }
        return top
    }
}

/// One item `OpenCodeTopBar` hands to its vertical bar.
struct OpenCodeTopBarRailItem {
    let icon: OpenCodeIcon
    let accessibilityLabel: String
    let enabled: Bool
    /// The overflow menu of an `OpenCodeTopBarOverflowHost` opens from this item.
    let anchorsOverflowMenu: Bool
    let action: () -> Void
}

/// What a top bar tells its `OpenCodeTopBarOverflowHost` about its vertical bar.
struct OpenCodeTopBarVerticalBarState: Equatable {
    /// Global frame of the item the overflow menu opens from.
    var overflowAnchor: CGRect?
}

struct OpenCodeTopBarVerticalBarPreferenceKey: PreferenceKey {
    static let defaultValue: OpenCodeTopBarVerticalBarState? = nil

    static func reduce(
        value: inout OpenCodeTopBarVerticalBarState?,
        nextValue: () -> OpenCodeTopBarVerticalBarState?
    ) {
        value = value ?? nextValue()
    }
}

/// Stands in for the iPhone Duo window in previews and the component gallery,
/// where the system never reports a vertical bar. The rail is drawn just past the
/// bar's trailing edge. Feature code never sets this.
struct OpenCodeTopBarVerticalBarSimulation: Equatable {
    let trailingInset: CGFloat
    let statusColumnHeight: CGFloat

    /// Open inner display: 84pt rail under a 120pt status column.
    static let duoOpen = OpenCodeTopBarVerticalBarSimulation(trailingInset: 84, statusColumnHeight: 120)
}

private struct OpenCodeTopBarVerticalBarSimulationKey: EnvironmentKey {
    static let defaultValue: OpenCodeTopBarVerticalBarSimulation? = nil
}

extension EnvironmentValues {
    var openCodeTopBarVerticalBarSimulation: OpenCodeTopBarVerticalBarSimulation? {
        get { self[OpenCodeTopBarVerticalBarSimulationKey.self] }
        set { self[OpenCodeTopBarVerticalBarSimulationKey.self] = newValue }
    }
}

/// Overlay of `OpenCodeTopBar` that draws its rail items in the trailing inset and
/// reports through `isActive` whether the bar should drop them from its
/// horizontal row, and through `isInVerticalBarContext` whether the bar sits where
/// the system would use a vertical bar (even with nothing to put in it). It spans the bar plus the trailing safe-area inset; items below
/// the bar draw outside its frame, so no ancestor may clip it.
struct OpenCodeTopBarVerticalBarLayer: View {
    let items: [OpenCodeTopBarRailItem]
    @Binding var isActive: Bool
    @Binding var isInVerticalBarContext: Bool

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.openCodeTopBarVerticalBarSimulation) private var simulation
    /// Read whole so `toolbarVerticalEdge`, an iOS 27.1 key, is available on the
    /// first layout without an availability-gated property wrapper.
    @Environment(\.self) private var environment
    @State private var traitEdge: HorizontalEdge?

    /// `toolbarVerticalEdge` first, read synchronously so the rail is right on the
    /// first frame; the UIKit trait it mirrors covers a hosting context that does
    /// not populate the environment.
    private var verticalEdge: HorizontalEdge? {
        #if canImport(SwiftUI, _version: 8.0.85)
        if #available(iOS 27.1, *), let edge = environment.toolbarVerticalEdge {
            return edge
        }
        #endif
        return traitEdge
    }

    var body: some View {
        GeometryReader { barProxy in
            // A reader that ignores the safe area reports no trailing inset of its own,
            // so the inset is how far it reaches past the bar's safe-area frame. A bar
            // that doesn't touch the trailing edge (a split's sidebar) can't extend.
            GeometryReader { proxy in
                let trailingInset = max(0, proxy.size.width - barProxy.size.width)
                let participating = participates(in: proxy, trailingInset: trailingInset)
                let frames = itemFrames(in: proxy, trailingInset: trailingInset, participating: participating)
                ZStack(alignment: .topLeading) {
                    ForEach(Array(frames.enumerated()), id: \.offset) { entry in
                        railButton(items[entry.offset])
                            .position(x: entry.element.midX, y: entry.element.midY)
                    }
                }
                .preference(
                    key: OpenCodeTopBarVerticalBarPreferenceKey.self,
                    value: frames.isEmpty ? nil : OpenCodeTopBarVerticalBarState(
                        overflowAnchor: overflowAnchor(frames: frames, proxy: proxy)
                    )
                )
                .onAppear {
                    isActive = !frames.isEmpty
                    isInVerticalBarContext = participating
                }
                .onChange(of: frames.isEmpty) { _, isEmpty in isActive = !isEmpty }
                .onChange(of: participating) { _, value in isInVerticalBarContext = value }
            }
            .ignoresSafeArea(.container, edges: .trailing)
        }
        .background { edgeProbes }
    }

    private func participates(in proxy: GeometryProxy, trailingInset: CGFloat) -> Bool {
        if simulation != nil { return true }
        return OpenCodeVerticalBarLayout.participates(
            verticalEdge: verticalEdge,
            trailingInset: trailingInset,
            topInWindow: proxy.frame(in: .global).minY
        )
    }

    private func itemFrames(in proxy: GeometryProxy, trailingInset: CGFloat, participating: Bool) -> [CGRect] {
        guard !items.isEmpty, participating else { return [] }
        if let simulation {
            let railMinX = proxy.size.width
            return OpenCodeVerticalBarLayout.itemFrames(
                count: items.count,
                railMinX: railMinX,
                railWidth: simulation.trailingInset,
                occlusions: [
                    CGRect(x: railMinX, y: 0, width: simulation.trailingInset, height: simulation.statusColumnHeight),
                ]
            )
        }
        let railMinX = proxy.size.width - trailingInset
        var occlusions = occlusionFrames(in: proxy)
        if occlusions.isEmpty {
            let height = OpenCodeVerticalBarLayout.fallbackStatusColumnHeight(
                horizontalSizeClass: horizontalSizeClass,
                verticalSizeClass: verticalSizeClass
            )
            occlusions = [CGRect(x: railMinX, y: 0, width: trailingInset, height: height)]
        }
        return OpenCodeVerticalBarLayout.itemFrames(
            count: items.count,
            railMinX: railMinX,
            railWidth: trailingInset,
            occlusions: occlusions
        )
    }

    private func occlusionFrames(in proxy: GeometryProxy) -> [CGRect] {
        #if canImport(SwiftUI, _version: 8.0.85)
        guard #available(iOS 27.1, *) else { return [] }
        return proxy.reservedRegions(kind: .occlusion)
            .filter(\.isActive)
            .map(\.frame)
        #else
        return []
        #endif
    }

    private func overflowAnchor(frames: [CGRect], proxy: GeometryProxy) -> CGRect? {
        guard let index = items.lastIndex(where: \.anchorsOverflowMenu), index < frames.count else {
            return nil
        }
        let origin = proxy.frame(in: .global).origin
        return frames[index].offsetBy(dx: origin.x, dy: origin.y)
    }

    private func railButton(_ item: OpenCodeTopBarRailItem) -> some View {
        Button(action: item.action) {
            OpenCodeIconImage(icon: item.icon, size: 18)
                .foregroundStyle(item.enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                .frame(width: OpenCodeVerticalBarLayout.itemSize, height: OpenCodeVerticalBarLayout.itemSize)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .openCodeRailGlass()
        .opacity(item.enabled ? 1 : 0.5)
        .disabled(!item.enabled)
        .accessibilityLabel(item.accessibilityLabel)
        .openCodeKeyboardFocusable(.circle, enabled: item.enabled, activate: item.action)
    }

    @ViewBuilder
    private var edgeProbes: some View {
        #if canImport(SwiftUI, _version: 8.0.85)
        if #available(iOS 27.1, *), simulation == nil {
            OpenCodeVerticalBarTraitProbe { traitEdge = $0 }
                .accessibilityHidden(true)
        }
        #endif
    }
}

private extension View {
    @ViewBuilder
    func openCodeRailGlass() -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular.interactive(), in: .circle)
        } else {
            background(OpenCodeColors.surfaceRaisedBase, in: Circle())
        }
    }
}

// The iOS 27.1 vertical-bar and reserved-region APIs only exist in the iOS 27.1
// SDK (SwiftUI 8.0.85); `#available` alone doesn't compile against older SDKs.
#if canImport(SwiftUI, _version: 8.0.85)
/// Reports the hosting view's `UITraitCollection.verticalBarEdge`, the system's
/// preferred vertical-bar edge whether or not a system bar is visible.
@available(iOS 27.1, *)
private struct OpenCodeVerticalBarTraitProbe: UIViewRepresentable {
    let onChange: (HorizontalEdge?) -> Void

    func makeUIView(context: Context) -> OpenCodeVerticalBarTraitProbeView {
        let view = OpenCodeVerticalBarTraitProbeView()
        view.onChange = onChange
        return view
    }

    func updateUIView(_ uiView: OpenCodeVerticalBarTraitProbeView, context: Context) {
        uiView.onChange = onChange
        uiView.report()
    }
}

@available(iOS 27.1, *)
final class OpenCodeVerticalBarTraitProbeView: UIView {
    var onChange: ((HorizontalEdge?) -> Void)?
    private var reported: HorizontalEdge??

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        registerForTraitChanges(UITraitCollection.systemTraitsAffectingVerticalBarEdge) {
            (view: OpenCodeVerticalBarTraitProbeView, _: UITraitCollection) in
            view.report()
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        report()
    }

    func report() {
        guard window != nil else { return }
        let edge: HorizontalEdge? =
            switch traitCollection.verticalBarEdge {
            case .leading: .leading
            case .trailing: .trailing
            default: nil
            }
        guard reported != .some(edge) else { return }
        reported = .some(edge)
        // SwiftUI state must not change during the view update that called us.
        DispatchQueue.main.async { [weak self] in
            self?.onChange?(edge)
        }
    }
}
#endif
