import SwiftUI

struct SessionChatPendingPromptsSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let prompts: [PendingPrompt]
    let onSteer: (String) -> Void
    let onQueue: (String) -> Void
    let onDelete: (String) -> Void
    let onClearAll: () -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Up next",
                pinnedTop: {
                    OpenCodeSectionHeader(title: "\(prompts.count) waiting", variant: .plain)
                },
                content: { sheetContent },
                pinnedBottom: {
                    OpenCodeButton(variant: .fullWidthText(title: "Clear all"), contrast: .low) {
                        logAction(ProductAnalytics.Action.pendingPromptsClear)
                        onClearAll()
                    }
                }
            )
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented {
                    logAction(ProductAnalytics.Action.pendingPromptsDismiss)
                    isPresented = false
                }
            }
        )
    }

    private var sheetContent: some View {
        VStack(spacing: 0) {
            ForEach(prompts) { prompt in
                pendingPromptRow(prompt)
            }
        }
    }

    private func pendingPromptRow(_ prompt: PendingPrompt) -> some View {
        HStack(alignment: .center, spacing: 12) {
            leadingBadge(for: prompt)

            Text(prompt.text)
                .font(OpenCodeTypography.body)
                .foregroundStyle(prompt.delivery == .steer ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture {
                    guard !prompt.isSettling, prompt.delivery == .queue else { return }
                    logAction(ProductAnalytics.Action.pendingPromptsSteer)
                    onSteer(prompt.id)
                }

            if prompt.isSettling {
                Text("Sending…")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            } else {
                swapButton(for: prompt)
                OpenCodeButton(
                    variant: .iconSquare(.delete, accessibilityLabel: "Delete"),
                    contrast: .low,
                    action: {
                        logAction(ProductAnalytics.Action.pendingPromptsDelete)
                        onDelete(prompt.id)
                    }
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private func leadingBadge(for prompt: PendingPrompt) -> some View {
        switch prompt.delivery {
        case .steer:
            OpenCodeIconImage(icon: .arrowWarmUp, size: 12)
                .foregroundStyle(OpenCodeColors.interactive)
                .frame(width: 22, height: 22)
                .background(OpenCodeColors.surfaceInteractiveWeak, in: RoundedRectangle(cornerRadius: 6))
        case .queue:
            Text("\(queuedPosition(for: prompt))")
                .font(OpenCodeTypography.caption.weight(.semibold))
                .foregroundStyle(OpenCodeColors.warning)
                .frame(width: 22, height: 22)
                .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 6))
        }
    }

    @ViewBuilder
    private func swapButton(for prompt: PendingPrompt) -> some View {
        switch prompt.delivery {
        case .queue:
            OpenCodeButton(
                variant: .iconSquare(.arrowWarmUp, accessibilityLabel: "Steer now"),
                contrast: .low,
                action: {
                    logAction(ProductAnalytics.Action.pendingPromptsSteer)
                    onSteer(prompt.id)
                }
            )
        case .steer:
            OpenCodeButton(
                variant: .iconSquare(.arrowUpward, accessibilityLabel: "Move to queue"),
                contrast: .low,
                action: {
                    logAction(ProductAnalytics.Action.pendingPromptsQueue)
                    onQueue(prompt.id)
                }
            )
        }
    }

    private func queuedPosition(for prompt: PendingPrompt) -> Int {
        (prompts.filter { $0.delivery == .queue }.firstIndex { $0.id == prompt.id } ?? 0) + 1
    }

    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionPendingPrompts,
            actionId: actionId
        )
    }
}

extension View {
    func sessionChatPendingPromptsSheet(
        isPresented: Binding<Bool>,
        prompts: [PendingPrompt],
        onSteer: @escaping (String) -> Void,
        onQueue: @escaping (String) -> Void,
        onDelete: @escaping (String) -> Void,
        onClearAll: @escaping () -> Void
    ) -> some View {
        modifier(
            SessionChatPendingPromptsSheetModifier(
                isPresented: isPresented,
                prompts: prompts,
                onSteer: onSteer,
                onQueue: onQueue,
                onDelete: onDelete,
                onClearAll: onClearAll
            )
        )
    }
}
