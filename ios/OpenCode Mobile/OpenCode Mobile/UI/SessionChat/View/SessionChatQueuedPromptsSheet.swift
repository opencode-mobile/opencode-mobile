import SwiftUI

struct SessionChatQueuedPromptsSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let prompts: [QueuedPrompt]
    let onSteer: (String) -> Void
    let onDelete: (String) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Queued prompts",
                content: { sheetRows }
            )
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented {
                    logAction(ProductAnalytics.Action.queuedPromptsDismiss)
                    isPresented = false
                }
            }
        )
    }

    private var sheetRows: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(prompts) { prompt in
                    OpenCodeListItem(
                        title: prompt.text,
                        action: {
                            logAction(ProductAnalytics.Action.queuedPromptsSteer)
                            onSteer(prompt.id)
                        },
                        trailing: {
                            OpenCodeButton(
                                variant: .iconSquare(.delete, accessibilityLabel: "Delete"),
                                contrast: .low,
                                action: {
                                    logAction(ProductAnalytics.Action.queuedPromptsDelete)
                                    onDelete(prompt.id)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionQueuedPrompts,
            actionId: actionId
        )
    }
}

extension View {
    func sessionChatQueuedPromptsSheet(
        isPresented: Binding<Bool>,
        prompts: [QueuedPrompt],
        onSteer: @escaping (String) -> Void,
        onDelete: @escaping (String) -> Void
    ) -> some View {
        modifier(
            SessionChatQueuedPromptsSheetModifier(
                isPresented: isPresented,
                prompts: prompts,
                onSteer: onSteer,
                onDelete: onDelete
            )
        )
    }
}
