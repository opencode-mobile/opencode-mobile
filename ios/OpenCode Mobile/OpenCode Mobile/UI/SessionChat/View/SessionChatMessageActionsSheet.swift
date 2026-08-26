import SwiftUI
import UIKit

struct SessionChatMessageActionsTarget: Equatable {
    let id: String
    let text: String
    let showsRevertMessage: Bool
}

struct SessionChatMessageActionsSheetModifier: ViewModifier {
    @Binding var target: SessionChatMessageActionsTarget?
    let onRevertMessage: (String) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Message",
                content: { sheetEntries }
            )
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { target != nil },
            set: { presented in
                if !presented {
                    logAction(ProductAnalytics.Action.messageActionsDismiss)
                    target = nil
                }
            }
        )
    }

    @ViewBuilder
    private var sheetEntries: some View {
        if target?.showsRevertMessage == true {
            OpenCodeBottomSheetEntry(
                title: "Revert message",
                startDecorator: .icon(.undo, accessibilityLabel: "Revert message"),
                action: {
                    logAction(ProductAnalytics.Action.messageActionsRevert)
                    if let target {
                        onRevertMessage(target.id)
                    }
                    self.target = nil
                }
            )
        }
        OpenCodeBottomSheetEntry(
            title: "Copy message",
            startDecorator: .icon(.contentCopy, accessibilityLabel: "Copy message"),
            action: {
                logAction(ProductAnalytics.Action.messageActionsCopy)
                if let target {
                    UIPasteboard.general.string = target.text
                }
                self.target = nil
            }
        )
    }

    /// Message text stays out of Analytics; only the taxonomy action is reported.
    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionMessageActions,
            actionId: actionId
        )
    }
}

extension View {
    func sessionChatMessageActionsSheet(
        target: Binding<SessionChatMessageActionsTarget?>,
        onRevertMessage: @escaping (String) -> Void
    ) -> some View {
        modifier(
            SessionChatMessageActionsSheetModifier(
                target: target,
                onRevertMessage: onRevertMessage
            )
        )
    }
}
