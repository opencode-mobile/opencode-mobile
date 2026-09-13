import SwiftUI
import UIKit

struct SessionChatPendingSteerTarget: Equatable {
    let id: String
    let text: String
}

struct SessionChatPendingSteerSheetModifier: ViewModifier {
    @Binding var target: SessionChatPendingSteerTarget?
    let onMoveToQueue: (String) -> Void
    let onDelete: (String) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "Pending steer",
                content: { sheetEntries }
            )
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { target != nil },
            set: { presented in
                if !presented {
                    logAction(ProductAnalytics.Action.pendingSteerDismiss)
                    target = nil
                }
            }
        )
    }

    @ViewBuilder
    private var sheetEntries: some View {
        OpenCodeBottomSheetEntry(
            title: "Move to queue",
            startDecorator: .icon(.arrowUpward, accessibilityLabel: "Move to queue"),
            action: {
                logAction(ProductAnalytics.Action.pendingSteerQueue)
                if let target {
                    onMoveToQueue(target.id)
                }
                self.target = nil
            }
        )
        OpenCodeBottomSheetEntry(
            title: "Delete",
            startDecorator: .icon(.delete, accessibilityLabel: "Delete"),
            action: {
                logAction(ProductAnalytics.Action.pendingSteerDelete)
                if let target {
                    onDelete(target.id)
                }
                self.target = nil
            }
        )
        OpenCodeBottomSheetEntry(
            title: "Copy text",
            startDecorator: .icon(.contentCopy, accessibilityLabel: "Copy text"),
            action: {
                logAction(ProductAnalytics.Action.pendingSteerCopy)
                if let target {
                    UIPasteboard.general.string = target.text
                }
                self.target = nil
            }
        )
    }

    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionPendingSteer,
            actionId: actionId
        )
    }
}

extension View {
    func sessionChatPendingSteerSheet(
        target: Binding<SessionChatPendingSteerTarget?>,
        onMoveToQueue: @escaping (String) -> Void,
        onDelete: @escaping (String) -> Void
    ) -> some View {
        modifier(
            SessionChatPendingSteerSheetModifier(
                target: target,
                onMoveToQueue: onMoveToQueue,
                onDelete: onDelete
            )
        )
    }
}
