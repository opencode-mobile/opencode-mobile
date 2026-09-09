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

struct OpenCodeTopBarOverflowHost<TopBar: View>: View {
    @Binding var isPresented: Bool
    let entries: [OpenCodeOverflowMenuEntry]
    @ViewBuilder let topBar: () -> TopBar

    @State private var anchorFrame: CGRect = .zero

    var body: some View {
        topBar()
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
                    entries: entries,
                    onDismiss: { isPresented = false }
                )
            }
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
    let anchorFrame: CGRect
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
                            entries: entries,
                            onDismiss: onDismiss,
                            onEntrySelected: onDismiss
                        )
                    )
                    controller.view.backgroundColor = .clear
                    controller.view.frame = window.bounds
                    controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    window.addSubview(controller.view)
                    overlayController = controller
                } else {
                    overlayController?.rootView = OpenCodeOverflowMenuOverlayContent(
                        anchorFrame: anchorFrame,
                        entries: entries,
                        onDismiss: onDismiss,
                        onEntrySelected: onDismiss
                    )
                    overlayController?.view.frame = window.bounds
                }
            } else {
                overlayController?.view.removeFromSuperview()
                overlayController = nil
            }
        }
    }
}

private struct OpenCodeOverflowMenuOverlayContent: View {
    let anchorFrame: CGRect
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
            .position(
                x: anchorFrame.maxX - overflowMenuAnchorEndInset - (panelSize.width / 2),
                y: anchorFrame.maxY + overflowMenuAnchorTopOffset + (panelSize.height / 2)
            )
        }
        .ignoresSafeArea()
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
