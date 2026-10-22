import SwiftUI
import UIKit

private let overflowMenuCornerRadius: CGFloat = 8
private let overflowMenuMinWidth: CGFloat = 200
private let overflowMenuPaddingVertical: CGFloat = 4
private let overflowMenuEntryMinHeight: CGFloat = 48
private let overflowMenuEntryPaddingHorizontal: CGFloat = 12
private let overflowMenuEntryIconSize: CGFloat = 20
private let overflowMenuEntryIconSpacing: CGFloat = 12
private let overflowMenuEntrySubtitleSpacing: CGFloat = 2
private let overflowMenuEntrySubtitlePaddingVertical: CGFloat = 6
private let overflowMenuAnchorEndInset: CGFloat = 4
private let overflowMenuAnchorTopOffset: CGFloat = 8
/// Gap between an iPhone Duo rail item and the menu that opens beside it.
private let overflowMenuRailItemSpacing: CGFloat = 8

struct OpenCodeOverflowMenuEntry: Identifiable {
    let id: String
    let title: String
    /// Secondary line under the title, such as why a disabled entry is unavailable.
    let subtitle: String?
    let icon: OpenCodeIcon?
    let enabled: Bool
    let destructive: Bool
    let accessibilityLabel: String?
    let accessibilityIdentifier: String?
    let action: () -> Void

    init(
        id: String = UUID().uuidString,
        title: String,
        subtitle: String? = nil,
        icon: OpenCodeIcon?,
        enabled: Bool = true,
        destructive: Bool = false,
        accessibilityLabel: String? = nil,
        accessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle?.isEmpty == true ? nil : subtitle
        self.icon = icon
        self.enabled = enabled
        self.destructive = destructive
        self.accessibilityLabel = accessibilityLabel
        self.accessibilityIdentifier = accessibilityIdentifier
        self.action = action
    }

    /// Spoken label: the title (or override), then the subtitle.
    var spokenLabel: String {
        let label = accessibilityLabel ?? title
        guard let subtitle else { return label }
        return "\(label), \(subtitle)"
    }
}

struct OpenCodeTopBarOverflowHost<TopBar: View>: View {
    @Binding var isPresented: Bool
    let entries: [OpenCodeOverflowMenuEntry]
    @ViewBuilder let topBar: () -> TopBar

    @State private var anchorFrame: CGRect = .zero
    @State private var verticalBar: OpenCodeTopBarVerticalBarState?

    /// Beside the rail item while the bar draws its iPhone Duo vertical bar,
    /// otherwise under the trailing end of the bar.
    private var menuAnchor: (frame: CGRect, placement: OverflowMenuPlacement) {
        if let railAnchor = verticalBar?.overflowAnchor {
            return (railAnchor, .besideRailItem)
        }
        return (anchorFrame, .belowTrailing)
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
                    anchorFrame: menuAnchor.frame,
                    placement: menuAnchor.placement,
                    entries: entries,
                    onDismiss: { isPresented = false }
                )
            }
            // The bar's rail items hang below the host; keep them above the screen content.
            .zIndex(verticalBar == nil ? 0 : 1)
    }
}

/// The overflow menu opened from a control inside the content, such as a +
/// button: the panel sits above `anchor`, leading-aligned, or below it when
/// there is no room above.
struct OpenCodeAnchoredOverflowHost<Anchor: View>: View {
    @Binding var isPresented: Bool
    let entries: [OpenCodeOverflowMenuEntry]
    @ViewBuilder let anchor: () -> Anchor

    @State private var anchorFrame: CGRect = .zero

    var body: some View {
        anchor()
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
                    anchorFrame: anchorFrame,
                    placement: .aboveLeading,
                    entries: entries,
                    onDismiss: { isPresented = false }
                )
            }
    }
}

/// Where the panel sits relative to its anchor.
private enum OverflowMenuPlacement {
    /// Under the top bar, trailing-aligned.
    case belowTrailing
    /// Over a control, leading-aligned; flips below when there is no room.
    case aboveLeading
    /// Beside an item in the iPhone Duo vertical bar, top-aligned with it.
    case besideRailItem
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
    let anchorFrame: CGRect
    let placement: OverflowMenuPlacement
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
            anchorFrame: anchorFrame,
            placement: placement,
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
            anchorFrame: CGRect,
            placement: OverflowMenuPlacement,
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

            if isPresented, anchorFrame != .zero {
                if overlayController == nil {
                    let controller = UIHostingController(
                        rootView: OpenCodeOverflowMenuOverlayContent(
                            anchorFrame: anchorFrame,
                            placement: placement,
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
                        anchorFrame: anchorFrame,
                        placement: placement,
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
    let anchorFrame: CGRect
    let placement: OverflowMenuPlacement
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
        switch placement {
        case .belowTrailing:
            return CGPoint(
                x: anchorFrame.maxX - overflowMenuAnchorEndInset - (panelSize.width / 2),
                y: anchorFrame.maxY + overflowMenuAnchorTopOffset + (panelSize.height / 2)
            )
        case .aboveLeading:
            let above = anchorFrame.minY - overflowMenuAnchorTopOffset - panelSize.height
            let top = above >= 0 ? above : anchorFrame.maxY + overflowMenuAnchorTopOffset
            return CGPoint(x: anchorFrame.minX + (panelSize.width / 2), y: top + (panelSize.height / 2))
        case .besideRailItem:
            return CGPoint(
                x: anchorFrame.minX - overflowMenuRailItemSpacing - (panelSize.width / 2),
                y: anchorFrame.minY + (panelSize.height / 2)
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
                        VStack(alignment: .leading, spacing: overflowMenuEntrySubtitleSpacing) {
                            Text(entry.title)
                                .font(OpenCodeTypography.body)
                                .foregroundStyle(overflowEntryTextColor(entry))
                                .lineLimit(1)
                            if let subtitle = entry.subtitle {
                                Text(subtitle)
                                    .font(OpenCodeTypography.caption)
                                    .foregroundStyle(OpenCodeColors.textWeak)
                                    .lineLimit(2)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(.vertical, entry.subtitle == nil ? 0 : overflowMenuEntrySubtitlePaddingVertical)
                    }
                    .frame(minWidth: overflowMenuMinWidth, minHeight: overflowMenuEntryMinHeight, alignment: .leading)
                    .padding(.horizontal, overflowMenuEntryPaddingHorizontal)
                    .opacity(entry.enabled ? 1 : 0.7)
                }
                .buttonStyle(.plain)
                .disabled(!entry.enabled)
                .accessibilityLabel(entry.spokenLabel)
                .modifier(OverflowEntryIdentifier(identifier: entry.accessibilityIdentifier))
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

private struct OverflowEntryIdentifier: ViewModifier {
    let identifier: String?

    func body(content: Content) -> some View {
        if let identifier {
            content.accessibilityIdentifier(identifier)
        } else {
            content
        }
    }
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
            subtitle: "Why it is unavailable",
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
