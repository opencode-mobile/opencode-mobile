import SwiftUI

enum OpenCodeSnackbarVariant: String, CaseIterable, Identifiable {
    case `default`
    case error

    var id: String { rawValue }
}

struct OpenCodeSnackbarMessage: Equatable, Identifiable {
    let id: UInt64
    let text: String
    let variant: OpenCodeSnackbarVariant
    /// Trailing text button, such as "Retry". A snackbar with an action stays up longer.
    let actionLabel: String?

    init(
        text: String,
        variant: OpenCodeSnackbarVariant = .default,
        actionLabel: String? = nil,
        id: UInt64 = UInt64.random(in: 1...UInt64.max)
    ) {
        self.id = id
        self.text = text
        self.variant = variant
        self.actionLabel = actionLabel
    }
}

private enum OpenCodeSnackbarMetrics {
    static let cornerRadius: CGFloat = 8
    static let paddingHorizontal: CGFloat = 16
    static let paddingVertical: CGFloat = 12
    static let screenMarginHorizontal: CGFloat = 16
    static let screenMarginBottom: CGFloat = 16
    static let autoDismissNanoseconds: UInt64 = 4_000_000_000
    static let actionAutoDismissNanoseconds: UInt64 = 10_000_000_000
    static let actionSpacing: CGFloat = 12
}

/// Host for one-shot auto-dismissing snackbars. When `message` becomes non-nil,
/// presents it and calls `onDismissed` after auto-dismiss or tap so the caller
/// can clear presentation state. Tapping a message's action calls `onAction`, then dismisses.
/// `onHeightChange` reports how far the shown snackbar reaches above the host's
/// bottom edge (card plus its bottom margin), or 0 when none is shown, so a list
/// behind it can keep its last row clear.
struct OpenCodeSnackbarHost: View {
    let message: OpenCodeSnackbarMessage?
    let onDismissed: () -> Void
    var onAction: (OpenCodeSnackbarMessage) -> Void = { _ in }
    var onHeightChange: (CGFloat) -> Void = { _ in }

    @State private var presented: OpenCodeSnackbarMessage?
    @State private var dismissTask: Task<Void, Never>?

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            if let presented {
                snackbarBody(presented)
                    .padding(.horizontal, OpenCodeSnackbarMetrics.screenMarginHorizontal)
                    .padding(.bottom, OpenCodeSnackbarMetrics.screenMarginBottom)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { onHeightChange($0) }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // Empty space around the snackbar is not hit-testable, so the screen behind stays usable.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .animation(.easeInOut(duration: 0.2), value: presented?.id)
        .onChange(of: message?.id) { _, _ in
            present(message)
        }
        .onChange(of: presented == nil) { _, hidden in
            if hidden { onHeightChange(0) }
        }
        .onAppear {
            present(message)
        }
        .onDisappear {
            dismissTask?.cancel()
            dismissTask = nil
            onHeightChange(0)
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func snackbarBody(_ message: OpenCodeSnackbarMessage) -> some View {
        HStack(spacing: OpenCodeSnackbarMetrics.actionSpacing) {
            Text(message.text)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(foregroundColor(for: message.variant))
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isStaticText)
                .accessibilityLabel(message.text)
            if let actionLabel = message.actionLabel {
                Button {
                    onAction(message)
                    dismissPresented()
                } label: {
                    Text(actionLabel)
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(foregroundColor(for: message.variant))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(actionLabel)
                .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small)) {
                    onAction(message)
                    dismissPresented()
                }
            }
        }
            .padding(.horizontal, OpenCodeSnackbarMetrics.paddingHorizontal)
            .padding(.vertical, OpenCodeSnackbarMetrics.paddingVertical)
            .background(backgroundColor(for: message.variant), in: RoundedRectangle(cornerRadius: OpenCodeSnackbarMetrics.cornerRadius))
            .shadow(color: Color.black.opacity(0.18), radius: 4, x: 0, y: 2)
            .accessibilityElement(children: .contain)
            .allowsHitTesting(true)
            .onTapGesture {
                dismissPresented()
            }
    }

    private func backgroundColor(for variant: OpenCodeSnackbarVariant) -> Color {
        switch variant {
        case .default:
            OpenCodeColors.surfaceRaisedBase
        case .error:
            OpenCodeColors.error
        }
    }

    private func foregroundColor(for variant: OpenCodeSnackbarVariant) -> Color {
        switch variant {
        case .default:
            OpenCodeColors.textStrong
        case .error:
            OpenCodeColors.iconInvertBase
        }
    }

    private func present(_ message: OpenCodeSnackbarMessage?) {
        dismissTask?.cancel()
        guard let message else {
            presented = nil
            return
        }
        presented = message
        let delay = message.actionLabel == nil
            ? OpenCodeSnackbarMetrics.autoDismissNanoseconds
            : OpenCodeSnackbarMetrics.actionAutoDismissNanoseconds
        dismissTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            dismissPresented()
        }
    }

    private func dismissPresented() {
        dismissTask?.cancel()
        dismissTask = nil
        guard presented != nil else { return }
        presented = nil
        onDismissed()
    }
}

#Preview("Default") {
    ZStack {
        OpenCodeColors.backgroundBase.ignoresSafeArea()
        OpenCodeSnackbarHost(
            message: OpenCodeSnackbarMessage(text: "Session compacted.", variant: .default, id: 1),
            onDismissed: {}
        )
    }
    .openCodeTheme()
}

#Preview("Action") {
    ZStack {
        OpenCodeColors.backgroundBase.ignoresSafeArea()
        OpenCodeSnackbarHost(
            message: OpenCodeSnackbarMessage(
                text: "/btw failed: “What did we change so far?”",
                variant: .error,
                actionLabel: "Retry",
                id: 3
            ),
            onDismissed: {}
        )
    }
    .openCodeTheme()
}

#Preview("Error") {
    ZStack {
        OpenCodeColors.backgroundBase.ignoresSafeArea()
        OpenCodeSnackbarHost(
            message: OpenCodeSnackbarMessage(
                text: "Couldn't compact session context.",
                variant: .error,
                id: 2
            ),
            onDismissed: {}
        )
    }
    .openCodeTheme()
}
