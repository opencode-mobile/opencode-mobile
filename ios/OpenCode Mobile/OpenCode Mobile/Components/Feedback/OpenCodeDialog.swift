import SwiftUI
import UIKit

private let dialogCornerRadius: CGFloat = 16
private let dialogMaxWidth: CGFloat = 360
private let dialogPaddingHorizontal: CGFloat = 20
private let dialogPaddingVertical: CGFloat = 20
private let dialogContentSpacing: CGFloat = 16
private let dialogTitleSubtitleSpacing: CGFloat = 8
private let dialogOptionSpacing: CGFloat = 8
private let dialogScrimAlpha: Double = 0.32
private let dialogPresentationDuration: TimeInterval = 0.2

struct OpenCodeDialogOption {
    let title: String
    let enabled: Bool
    let destructive: Bool
    let action: () -> Void

    init(title: String, enabled: Bool = true, destructive: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.destructive = destructive
        self.action = action
    }
}

struct OpenCodeDialogTextField {
    var text: Binding<String>
    var hint: String = ""
    var accessibilityLabel: String?
    var kind: OpenCodeTextInputKind = .text
    var enabled = true
    var errorMessage: String?

    init(
        text: Binding<String>,
        hint: String = "",
        accessibilityLabel: String? = nil,
        kind: OpenCodeTextInputKind = .text,
        enabled: Bool = true,
        errorMessage: String? = nil
    ) {
        self.text = text
        self.hint = hint
        self.accessibilityLabel = accessibilityLabel
        self.kind = kind
        self.enabled = enabled
        self.errorMessage = errorMessage
    }
}

struct OpenCodeDialog: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let subtitle: String?
    let primaryOption: OpenCodeDialogOption
    let secondaryOption: OpenCodeDialogOption
    let textField: OpenCodeDialogTextField?

    func body(content: Content) -> some View {
        content.background {
            OpenCodeDialogOverlayHost(
                isPresented: isPresented,
                title: title,
                subtitle: subtitle,
                primaryOption: primaryOption,
                secondaryOption: secondaryOption,
                textField: textField,
                onDismiss: { isPresented = false }
            )
        }
    }
}

private struct OpenCodeDialogOverlayHost: UIViewRepresentable {
    let isPresented: Bool
    let title: String
    let subtitle: String?
    let primaryOption: OpenCodeDialogOption
    let secondaryOption: OpenCodeDialogOption
    let textField: OpenCodeDialogTextField?
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
            title: title,
            subtitle: subtitle,
            primaryOption: primaryOption,
            secondaryOption: secondaryOption,
            textField: textField,
            hostView: uiView
        )
    }

    final class Coordinator {
        private let onDismiss: () -> Void
        private var overlayController: UIHostingController<OpenCodeDialogOverlayContent>?
        private var isAnimatingOut = false

        init(onDismiss: @escaping () -> Void) {
            self.onDismiss = onDismiss
        }

        func update(
            isPresented: Bool,
            title: String,
            subtitle: String?,
            primaryOption: OpenCodeDialogOption,
            secondaryOption: OpenCodeDialogOption,
            textField: OpenCodeDialogTextField?,
            hostView: UIView
        ) {
            guard let window = hostView.window ?? UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
            else {
                return
            }

            let content = OpenCodeDialogOverlayContent(
                title: title,
                subtitle: subtitle,
                primaryOption: primaryOption,
                secondaryOption: secondaryOption,
                textField: textField,
                onDismiss: onDismiss
            )

            if isPresented {
                isAnimatingOut = false
                if let overlayController {
                    overlayController.rootView = content
                    overlayController.view.frame = window.bounds
                } else {
                    let controller = UIHostingController(rootView: content)
                    controller.view.backgroundColor = .clear
                    controller.view.frame = window.bounds
                    controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    controller.view.alpha = 0
                    window.addSubview(controller.view)
                    overlayController = controller
                    UIView.animate(withDuration: dialogPresentationDuration) {
                        controller.view.alpha = 1
                    }
                }
            } else if overlayController != nil, !isAnimatingOut {
                dismissOverlay()
            }
        }

        private func dismissOverlay() {
            guard let overlayController else { return }
            isAnimatingOut = true
            UIView.animate(withDuration: dialogPresentationDuration, animations: {
                overlayController.view.alpha = 0
            }, completion: { [weak self] _ in
                overlayController.view.removeFromSuperview()
                self?.overlayController = nil
                self?.isAnimatingOut = false
            })
        }
    }
}

private struct OpenCodeDialogOverlayContent: View {
    let title: String
    let subtitle: String?
    let primaryOption: OpenCodeDialogOption
    let secondaryOption: OpenCodeDialogOption
    let textField: OpenCodeDialogTextField?
    let onDismiss: () -> Void

    @State private var keyboardOverlap: CGFloat = 0

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black.opacity(dialogScrimAlpha)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)

                OpenCodeDialogCard(
                    title: title,
                    subtitle: subtitle,
                    primaryOption: primaryOption,
                    secondaryOption: secondaryOption,
                    textField: textField
                )
                .padding(.horizontal, 24)
                .frame(maxWidth: dialogMaxWidth + 48)
                .padding(.bottom, keyboardOverlap)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { notification in
                keyboardOverlap = KeyboardOverlap.amount(
                    from: notification,
                    in: geometry.frame(in: .global)
                )
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                keyboardOverlap = 0
            }
            .animation(.easeOut(duration: 0.25), value: keyboardOverlap)
        }
    }
}

private struct OpenCodeDialogCard: View {
    let title: String
    let subtitle: String?
    let primaryOption: OpenCodeDialogOption
    let secondaryOption: OpenCodeDialogOption
    let textField: OpenCodeDialogTextField?

    var body: some View {
        VStack(alignment: .leading, spacing: dialogContentSpacing) {
            VStack(alignment: .leading, spacing: dialogTitleSubtitleSpacing) {
                Text(title)
                    .font(OpenCodeTypography.title)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }

            if let textField {
                OpenCodeTextInput(
                    text: textField.text,
                    hint: textField.hint,
                    accessibilityLabel: textField.accessibilityLabel,
                    kind: textField.kind,
                    enabled: textField.enabled,
                    errorMessage: textField.errorMessage
                )
            }

            HStack(spacing: dialogOptionSpacing) {
                OpenCodeButton(
                    variant: .fullWidthText(title: secondaryOption.title),
                    enabled: secondaryOption.enabled,
                    contrast: .low,
                    action: secondaryOption.action
                )
                .frame(maxWidth: .infinity)

                OpenCodeButton(
                    variant: .fullWidthText(title: primaryOption.title),
                    enabled: primaryOption.enabled,
                    contrast: primaryOption.destructive ? .destructive : .high,
                    action: primaryOption.action
                )
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, dialogPaddingHorizontal)
        .padding(.vertical, dialogPaddingVertical)
        .frame(maxWidth: dialogMaxWidth)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: dialogCornerRadius))
        .shadow(color: .black.opacity(0.18), radius: 16, y: 8)
        .accessibilityAddTraits(.isModal)
    }
}

extension View {
    func openCodeDialog(
        isPresented: Binding<Bool>,
        title: String,
        subtitle: String? = nil,
        primaryOption: OpenCodeDialogOption,
        secondaryOption: OpenCodeDialogOption,
        textField: OpenCodeDialogTextField? = nil
    ) -> some View {
        modifier(
            OpenCodeDialog(
                isPresented: isPresented,
                title: title,
                subtitle: subtitle,
                primaryOption: primaryOption,
                secondaryOption: secondaryOption,
                textField: textField
            )
        )
    }
}

#Preview("Confirmation") {
    Color.clear
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .openCodeDialog(
            isPresented: .constant(true),
            title: "Clear all data?",
            subtitle: "This cannot be undone.",
            primaryOption: OpenCodeDialogOption(title: "Clear", action: {}),
            secondaryOption: OpenCodeDialogOption(title: "Cancel", action: {})
        )
        .openCodeTheme()
}

#Preview("Form") {
    Color.clear
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .openCodeDialog(
            isPresented: .constant(true),
            title: "Rename session",
            primaryOption: OpenCodeDialogOption(title: "Save", action: {}),
            secondaryOption: OpenCodeDialogOption(title: "Cancel", action: {}),
            textField: OpenCodeDialogTextField(
                text: .constant("Importing icons"),
                hint: "Title"
            )
        )
        .openCodeTheme()
}
