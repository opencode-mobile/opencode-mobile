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

    init(text: String, variant: OpenCodeSnackbarVariant = .default, id: UInt64 = UInt64.random(in: 1...UInt64.max)) {
        self.id = id
        self.text = text
        self.variant = variant
    }
}

private enum OpenCodeSnackbarMetrics {
    static let cornerRadius: CGFloat = 8
    static let paddingHorizontal: CGFloat = 16
    static let paddingVertical: CGFloat = 12
    static let screenMarginHorizontal: CGFloat = 16
    static let screenMarginBottom: CGFloat = 16
    static let autoDismissNanoseconds: UInt64 = 4_000_000_000
}

/// Host for one-shot auto-dismissing snackbars. When `message` becomes non-nil,
/// presents it and calls `onDismissed` after auto-dismiss or tap so the caller
/// can clear presentation state.
struct OpenCodeSnackbarHost: View {
    let message: OpenCodeSnackbarMessage?
    let onDismissed: () -> Void

    @State private var presented: OpenCodeSnackbarMessage?
    @State private var dismissTask: Task<Void, Never>?

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            if let presented {
                snackbarBody(presented)
                    .padding(.horizontal, OpenCodeSnackbarMetrics.screenMarginHorizontal)
                    .padding(.bottom, OpenCodeSnackbarMetrics.screenMarginBottom)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.2), value: presented?.id)
        .onChange(of: message?.id) { _, _ in
            present(message)
        }
        .onAppear {
            present(message)
        }
        .onDisappear {
            dismissTask?.cancel()
            dismissTask = nil
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func snackbarBody(_ message: OpenCodeSnackbarMessage) -> some View {
        Text(message.text)
            .font(OpenCodeTypography.bodySecondary)
            .foregroundStyle(foregroundColor(for: message.variant))
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, OpenCodeSnackbarMetrics.paddingHorizontal)
            .padding(.vertical, OpenCodeSnackbarMetrics.paddingVertical)
            .background(backgroundColor(for: message.variant), in: RoundedRectangle(cornerRadius: OpenCodeSnackbarMetrics.cornerRadius))
            .shadow(color: Color.black.opacity(0.18), radius: 4, x: 0, y: 2)
            .accessibilityAddTraits(.isStaticText)
            .accessibilityLabel(message.text)
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
        dismissTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: OpenCodeSnackbarMetrics.autoDismissNanoseconds)
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
