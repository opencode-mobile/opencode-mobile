import SwiftUI
import UIKit

struct SessionChatMessageActionsTarget: Equatable {
    let id: String
    let text: String
    /// True for outgoing user messages: they offer "Revert message" and "Fork from here".
    let showsRevertMessage: Bool
    /// The code block the tap landed on, offered as its own copy action.
    var codeBlock: String? = nil
}

struct SessionChatMessageActionsSheetModifier: ViewModifier {
    @Binding var target: SessionChatMessageActionsTarget?
    /// Set to open the Select text sheet for the message.
    @Binding var selectTextTarget: SessionChatSelectTextTarget?
    var newFeatures = NewFeatureStore.shared
    let onRevertMessage: (String) -> Void
    let onForkFromMessage: (String) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: "session_chat_sheet.message_actions.title",
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
                title: "session_chat_sheet.message_actions.revert",
                startDecorator: .icon(.undo),
                action: {
                    logAction(ProductAnalytics.Action.messageActionsRevert)
                    if let target {
                        onRevertMessage(target.id)
                    }
                    self.target = nil
                }
            )
            OpenCodeBottomSheetEntry(
                title: "session_chat_sheet.message_actions.fork",
                startDecorator: .icon(.callSplit),
                action: {
                    logAction(ProductAnalytics.Action.messageActionsFork)
                    let messageID = target?.id
                    self.target = nil
                    if let messageID {
                        onForkFromMessage(messageID)
                    }
                }
            )
        }
        if let codeBlock = target?.codeBlock {
            OpenCodeBottomSheetEntry(
                title: "session_chat_sheet.message_actions.copy_code_block",
                startDecorator: .icon(.code),
                action: {
                    logAction(ProductAnalytics.Action.messageActionsCopyCodeBlock)
                    UIPasteboard.general.string = codeBlock
                    self.target = nil
                }
            )
        }
        OpenCodeBottomSheetEntry(
            title: "session_chat_sheet.message_actions.copy_message",
            startDecorator: .icon(.contentCopy),
            action: {
                logAction(ProductAnalytics.Action.messageActionsCopy)
                if let target {
                    UIPasteboard.general.string = target.text
                }
                self.target = nil
            }
        )
        OpenCodeBottomSheetEntry(
            title: "session_chat_sheet.message_actions.select_text",
            startDecorator: .icon(.textSelect),
            isNew: newFeatures.isNew(.selectMessageText),
            action: {
                logAction(ProductAnalytics.Action.messageActionsSelectText)
                newFeatures.markUsed(.selectMessageText)
                let message = target
                self.target = nil
                if let message {
                    ProductAnalytics.logScreenView(
                        screenId: ProductAnalytics.Screen.sessionSelectText,
                        presentation: ProductAnalytics.Presentation.sheet,
                        trigger: ProductAnalytics.Trigger.present
                    )
                    selectTextTarget = SessionChatSelectTextTarget(
                        id: message.id,
                        text: message.text,
                        codeBlock: message.codeBlock
                    )
                }
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
        selectTextTarget: Binding<SessionChatSelectTextTarget?>,
        onRevertMessage: @escaping (String) -> Void,
        onForkFromMessage: @escaping (String) -> Void
    ) -> some View {
        modifier(
            SessionChatMessageActionsSheetModifier(
                target: target,
                selectTextTarget: selectTextTarget,
                onRevertMessage: onRevertMessage,
                onForkFromMessage: onForkFromMessage
            )
        )
    }
}
