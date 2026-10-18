import SwiftUI
import UIKit

private let overflowMenuCornerRadius: CGFloat = 8
private let overflowMenuMinWidth: CGFloat = 200
private let overflowMenuPaddingVertical: CGFloat = 4
private let overflowMenuEntryMinHeight: CGFloat = 48
private let overflowMenuEntryPaddingHorizontal: CGFloat = 12
private let overflowMenuEntryIconSize: CGFloat = 20
private let overflowMenuEntryIconSpacing: CGFloat = 12
private let overflowMenuAnchorEndInset: CGFloat = 4
private let overflowMenuAnchorTopOffset: CGFloat = 8
private let overflowMenuRailItemSpacing: CGFloat = 8

struct OpenCodeOverflowMenuEntry: Identifiable {
    let id: String
    let title: String
    let icon: OpenCodeIcon?
    let enabled: Bool
    let destructive: Bool
    let accessibilityLabel: String?
    let action: () -> Void

    init(
        id: String = UUID().uuidString,
        title: String,
        icon: OpenCodeIcon?,
        enabled: Bool = true,
        destructive: Bool = false,
        accessibilityLabel: String? = nil,
        action: @escaping () -> Void
    ) {
        self.id = id
        self.title = title
        self.icon = icon
        self.enabled = enabled
        self.destructive = destructive
        self.accessibilityLabel = accessibilityLabel
        self.action = action
    }
}

/// Where the overflow menu opens from, in global coordinates.
enum OpenCodeOverflowMenuAnchor: Equatable {
    /// Under the trailing end of the top bar.
    case belowTrailing(CGRect)
    /// Beside an item in the iPhone Duo vertical bar, top-aligned with it.
    case besideRailItem(CGRect)

    var frame: CGRect {
        switch self {
        case let .belowTrailing(frame), let .besideRailItem(frame): frame
        }
    }
}

struct OpenCodeTopBarOverflowHost<TopBar: View>: View {
    @Binding var isPresented: Bool
    let entries: [OpenCodeOverflowMenuEntry]
    @ViewBuilder let topBar: () -> TopBar

    @State private var anchorFrame: CGRect = .zero
    @State private var verticalBar: OpenCodeTopBarVerticalBarState?

    private var anchor: OpenCodeOverflowMenuAnchor {
        if let railAnchor = verticalBar?.overflowAnchor {
            return .besideRailItem(railAnchor)
        }
        return .belowTrailing(anchorFrame)
    }

    var body: some View {
        topBar()
            .onPreferenceChange(OpenCodeTopBarVerticalBarPreferenceKey.self) { state in
                verticalBar = state
            }
            .transformPreference(OpenCodeTopBarVerticalBarPreferenceKey.self) { $0 = nil }
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear {
                            anchorFrame = geometry.frame(in: .global)
                        }
                        .onChange(of: geometry.frame(in: .global)) { _, frame in
                            anchorFrame = frame
                        }
                }
            }
            .background {
                OpenCodeOverflowMenuWindowOverlay(
                    isPresented: isPresented,
                    anchor: anchor,
                    entries: entries,
                    onDismiss: { isPresented = false }
                )
            }
            // The bar's rail items hang below the host; keep them above the screen content.
            .zIndex(verticalBar == nil ? 0 : 1)
    }
}

struct OpenCodeOverflowMenuTrigger: View {
    let action: () -> Void

    init(action: @escaping () -> Void = {}) {
        self.action = action
    }

    var body: some View {
        OpenCodeButton(
            variant: .iconCircle(.moreVert, accessibilityLabel: "More options"),
            contrast: .low,
            action: action
        )
    }
}

private struct OpenCodeOverflowMenuWindowOverlay: UIViewRepresentable {
    let isPresented: Bool
    let anchor: OpenCodeOverflowMenuAnchor
    let entries: [OpenCodeOverflowMenuEntry]
    let onDismiss: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onDismiss: onDismiss)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.update(
            isPresented: isPresented,
            anchor: anchor,
            entries: entries,
            hostView: uiView
        )
    }

    final class Coordinator {
        private let onDismiss: () -> Void
        private var overlayController: UIHostingController<OpenCodeOverflowMenuOverlayContent>?

        init(onDismiss: @escaping () -> Void) {
            self.onDismiss = onDismiss
        }

        func update(
            isPresented: Bool,
            anchor: OpenCodeOverflowMenuAnchor,
            entries: [OpenCodeOverflowMenuEntry],
            hostView: UIView
        ) {
            guard let window = hostView.window ?? UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
            else {
                return
            }

            if isPresented, anchor.frame != .zero {
                if overlayController == nil {
                    let controller = UIHostingController(
                        rootView: OpenCodeOverflowMenuOverlayContent(
                            anchor: anchor,
                            entries: entries,
                            onDismiss: onDismiss,
                            onEntrySelected: onDismiss
                        )
                    )
                    controller.view.backgroundColor = .clear
                    controller.view.frame = window.bounds
                    controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    window.addSubview(controller.view)
                    OpenCodeWindowOverlayRegistry.shared.attach(controller.view)
                    overlayController = controller
                } else {
                    overlayController?.rootView = OpenCodeOverflowMenuOverlayContent(
                        anchor: anchor,
                        entries: entries,
                        onDismiss: onDismiss,
                        onEntrySelected: onDismiss
                    )
                    overlayController?.view.frame = window.bounds
                }
            } else {
                if let view = overlayController?.view {
                    OpenCodeWindowOverlayRegistry.shared.detach(view)
                }
                overlayController?.view.removeFromSuperview()
                overlayController = nil
            }
        }
    }
}

private struct OpenCodeOverflowMenuOverlayContent: View {
    let anchor: OpenCodeOverflowMenuAnchor
    let entries: [OpenCodeOverflowMenuEntry]
    let onDismiss: () -> Void
    let onEntrySelected: () -> Void

    @State private var panelSize: CGSize = .zero

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .onTapGesture(perform: onDismiss)

            OpenCodeOverflowMenuPanel(
                entries: entries,
                onEntrySelected: onEntrySelected
            )
            .background {
                GeometryReader { proxy in
                    Color.clear
                        .onAppear { panelSize = proxy.size }
                        .onChange(of: proxy.size) { _, size in panelSize = size }
                }
            }
            .position(panelCenter)
        }
        .ignoresSafeArea()
    }

    private var panelCenter: CGPoint {
        switch anchor {
        case let .belowTrailing(frame):
            CGPoint(
                x: frame.maxX - overflowMenuAnchorEndInset - (panelSize.width / 2),
                y: frame.maxY + overflowMenuAnchorTopOffset + (panelSize.height / 2)
            )
        case let .besideRailItem(frame):
            CGPoint(
                x: frame.minX - overflowMenuRailItemSpacing - (panelSize.width / 2),
                y: frame.minY + (panelSize.height / 2)
            )
        }
    }
}

private struct OpenCodeOverflowMenuPanel: View {
    let entries: [OpenCodeOverflowMenuEntry]
    let onEntrySelected: () -> Void

    private var reservesIconColumn: Bool {
        entries.contains { $0.icon != nil }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(entries) { entry in
                Button {
                    guard entry.enabled else { return }
                    entry.action()
                    onEntrySelected()
                } label: {
                    HStack(spacing: reservesIconColumn ? overflowMenuEntryIconSpacing : 0) {
                        if reservesIconColumn {
                            Group {
                                if let icon = entry.icon {
                                    OpenCodeIconImage(icon: icon, size: overflowMenuEntryIconSize)
                                } else {
                                    Color.clear
                                        .frame(
                                            width: overflowMenuEntryIconSize,
                                            height: overflowMenuEntryIconSize
                                        )
                                }
                            }
                            .foregroundStyle(overflowEntryIconColor(entry))
                            .accessibilityHidden(true)
                        }
                        Text(entry.title)
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(overflowEntryTextColor(entry))
                            .lineLimit(1)
                    }
                    .frame(minWidth: overflowMenuMinWidth, minHeight: overflowMenuEntryMinHeight, alignment: .leading)
                    .padding(.horizontal, overflowMenuEntryPaddingHorizontal)
                    .opacity(entry.enabled ? 1 : 0.7)
                }
                .buttonStyle(.plain)
                .disabled(!entry.enabled)
                .accessibilityLabel(entry.accessibilityLabel ?? entry.title)
            }
        }
        .padding(.vertical, overflowMenuPaddingVertical)
        .fixedSize(horizontal: true, vertical: true)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: overflowMenuCornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: overflowMenuCornerRadius)
                .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1)
        }
    }
}

#Preview("Light") {
    OpenCodeTopBarOverflowHost(
        isPresented: .constant(true),
        entries: previewOverflowMenuEntries()
    ) {
        OpenCodeTopBar(
            title: "Session",
            leading: .back(action: {}),
            actions: [
                .icon(.moreVert, accessibilityLabel: "More options", action: {}),
            ],
            showDivider: true
        )
    }
    .openCodeTheme()
}

#Preview("Dark") {
    OpenCodeTopBarOverflowHost(
        isPresented: .constant(true),
        entries: previewOverflowMenuEntries()
    ) {
        OpenCodeTopBar(
            title: "Session",
            leading: .back(action: {}),
            showDivider: true
        )
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}

private func overflowEntryTextColor(_ entry: OpenCodeOverflowMenuEntry) -> Color {
    switch (entry.enabled, entry.destructive) {
    case (false, _):
        OpenCodeColors.textWeak
    case (true, true):
        OpenCodeColors.error
    case (true, false):
        OpenCodeColors.textStrong
    }
}

private func overflowEntryIconColor(_ entry: OpenCodeOverflowMenuEntry) -> Color {
    switch (entry.enabled, entry.destructive) {
    case (false, _):
        OpenCodeColors.iconBase
    case (true, true):
        OpenCodeColors.error
    case (true, false):
        OpenCodeColors.iconStrongBase
    }
}

private func previewOverflowMenuEntries() -> [OpenCodeOverflowMenuEntry] {
    [
        OpenCodeOverflowMenuEntry(
            title: "Working tree",
            icon: .check,
            accessibilityLabel: "Working tree, selected",
            action: {}
        ),
        OpenCodeOverflowMenuEntry(
            title: "Main branch",
            icon: nil,
            action: {}
        ),
        OpenCodeOverflowMenuEntry(
            title: "Disabled action",
            icon: .settings,
            enabled: false,
            action: {}
        ),
        OpenCodeOverflowMenuEntry(
            title: "Delete",
            icon: .close,
            destructive: true,
            action: {}
        ),
    ]
}
