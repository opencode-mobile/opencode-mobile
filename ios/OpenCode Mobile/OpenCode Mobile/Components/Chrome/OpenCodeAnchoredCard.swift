import SwiftUI
import UIKit

/// Where an anchored card sits: under its anchor with the leading edges lined up, `gap` below
/// it, and at least `edgeInset` from the trailing screen edge.
enum OpenCodeAnchoredCardLayout {
    /// Gap between a floating pill and its card: the same gap as between neighbouring pills.
    static let gap: CGFloat = 8
    /// Inset from the screen edge, the same as the Session Chat corner slots.
    static let edgeInset: CGFloat = 16
    /// Corner radius of the card, matching a floating pill's capsule.
    static let cornerRadius: CGFloat = OpenCodeRadius.large

    static func frame(
        anchor: CGRect,
        containerWidth: CGFloat,
        preferredWidth: CGFloat,
        gap: CGFloat = gap,
        edgeInset: CGFloat = edgeInset
    ) -> CGRect {
        let x = max(anchor.minX, 0)
        let width = max(min(preferredWidth, containerWidth - x - edgeInset), 0)
        return CGRect(x: x, y: anchor.maxY + gap, width: width, height: 0)
    }
}

/// A plain card anchored under `anchor`, with no pointing arrow. It is added to the window so it
/// draws above the screen; tapping outside, the VoiceOver escape gesture or Escape dismisses it.
struct OpenCodeAnchoredCardHost<Anchor: View, Card: View>: View {
    @Binding var isPresented: Bool
    let preferredWidth: CGFloat
    @ViewBuilder let anchor: () -> Anchor
    @ViewBuilder let card: () -> Card

    @State private var anchorFrame: CGRect = .zero
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        anchor()
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear { anchorFrame = geometry.frame(in: .global) }
                        .onChange(of: geometry.frame(in: .global)) { _, frame in anchorFrame = frame }
                }
            }
            .background {
                OpenCodeAnchoredCardWindowOverlay(
                    isPresented: isPresented,
                    anchorFrame: anchorFrame,
                    preferredWidth: preferredWidth,
                    colorScheme: colorScheme,
                    card: AnyView(card()),
                    onDismiss: { isPresented = false }
                )
            }
            // An anchor that leaves the screen takes its card with it.
            .onDisappear { isPresented = false }
    }
}

private struct OpenCodeAnchoredCardWindowOverlay: UIViewRepresentable {
    let isPresented: Bool
    let anchorFrame: CGRect
    let preferredWidth: CGFloat
    let colorScheme: ColorScheme
    let card: AnyView
    let onDismiss: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.update(
            isPresented: isPresented,
            content: OpenCodeAnchoredCardOverlayContent(
                anchorFrame: anchorFrame,
                preferredWidth: preferredWidth,
                colorScheme: colorScheme,
                card: card,
                onDismiss: onDismiss
            ),
            hostView: uiView
        )
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.remove()
    }

    final class Coordinator {
        private var controller: OpenCodeOverlayHostingController<OpenCodeAnchoredCardOverlayContent>?

        func update(isPresented: Bool, content: OpenCodeAnchoredCardOverlayContent, hostView: UIView) {
            guard isPresented, content.anchorFrame != .zero, let window = hostView.window else {
                remove()
                return
            }
            let style: UIUserInterfaceStyle = content.colorScheme == .dark ? .dark : .light
            if let controller {
                controller.rootView = content
                controller.overrideUserInterfaceStyle = style
                controller.view.frame = window.bounds
                OpenCodeOverlayEscape.register(self, layer: controller.layer, dismiss: content.onDismiss)
                return
            }
            let controller = OpenCodeOverlayHostingController(rootView: content)
            controller.overrideUserInterfaceStyle = style
            controller.safeAreaRegions = []
            controller.view.backgroundColor = .clear
            controller.view.isOpaque = false
            controller.view.accessibilityViewIsModal = true
            controller.view.frame = window.bounds
            controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            window.addSubview(controller.view)
            OpenCodeWindowOverlayRegistry.shared.attach(controller.view)
            self.controller = controller
            // Hardware Escape dismisses the newest window overlay, as for bottom sheets.
            OpenCodeOverlayEscape.register(self, layer: controller.layer, dismiss: content.onDismiss)
            controller.takeEscapeFocusIfFree()
            // Move VoiceOver into the card.
            UIAccessibility.post(notification: .screenChanged, argument: controller.view)
        }

        func remove() {
            guard let view = controller?.view else { return }
            OpenCodeWindowOverlayRegistry.shared.detach(view)
            OpenCodeOverlayEscape.unregister(self)
            controller?.resignFirstResponder()
            view.removeFromSuperview()
            controller = nil
            UIAccessibility.post(notification: .screenChanged, argument: nil)
        }
    }
}

private struct OpenCodeAnchoredCardOverlayContent: View {
    let anchorFrame: CGRect
    let preferredWidth: CGFloat
    let colorScheme: ColorScheme
    let card: AnyView
    let onDismiss: () -> Void

    var body: some View {
        GeometryReader { proxy in
            let frame = OpenCodeAnchoredCardLayout.frame(
                anchor: anchorFrame,
                containerWidth: proxy.size.width,
                preferredWidth: preferredWidth
            )
            ZStack(alignment: .topLeading) {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)
                    .accessibilityHidden(true)

                card
                    .frame(width: frame.width, alignment: .leading)
                    .background(
                        OpenCodeColors.surfaceRaisedBase,
                        in: RoundedRectangle(cornerRadius: OpenCodeAnchoredCardLayout.cornerRadius, style: .continuous)
                    )
                    .overlay {
                        RoundedRectangle(cornerRadius: OpenCodeAnchoredCardLayout.cornerRadius, style: .continuous)
                            .strokeBorder(OpenCodeColors.borderWeakBase, lineWidth: 1)
                    }
                    .shadow(color: .black.opacity(0.12), radius: 16, y: 6)
                    .accessibilityElement(children: .contain)
                    .accessibilityAddTraits(.isModal)
                    .accessibilityAction(.escape, onDismiss)
                    .offset(x: frame.minX, y: frame.minY)
            }
        }
        .ignoresSafeArea()
        .environment(\.colorScheme, colorScheme)
    }
}
