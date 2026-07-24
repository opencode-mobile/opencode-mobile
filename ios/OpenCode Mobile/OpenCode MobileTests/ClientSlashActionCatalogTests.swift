import Foundation
import Testing
@testable import OpenCode_Mobile

struct ClientSlashActionCatalogTests {
    @Test func visibleActions_emptySession_showsOnlyNew() {
        let visible = ClientSlashActionCatalog.visibleActions(
            ClientSlashActionVisibilityInput(
                visibleUserMessageCount: 0,
                hasUndoableUserMessage: false,
                hasActiveSessionRevert: false,
                hasSelectedComposerModel: false
            )
        )
        #expect(visible == [.new])
    }

    @Test func visibleActions_userMessageWithoutModel_showsNewForkUndo() {
        let visible = ClientSlashActionCatalog.visibleActions(
            ClientSlashActionVisibilityInput(
                visibleUserMessageCount: 1,
                hasUndoableUserMessage: true,
                hasActiveSessionRevert: false,
                hasSelectedComposerModel: false
            )
        )
        #expect(visible == [.new, .fork, .undo])
    }

    @Test func visibleActions_userMessageWithModel_includesCompact() {
        let visible = ClientSlashActionCatalog.visibleActions(
            ClientSlashActionVisibilityInput(
                visibleUserMessageCount: 1,
                hasUndoableUserMessage: true,
                hasActiveSessionRevert: false,
                hasSelectedComposerModel: true
            )
        )
        #expect(visible == [.new, .fork, .undo, .compact])
    }

    @Test func visibleActions_activeRevert_includesRedo() {
        let visible = ClientSlashActionCatalog.visibleActions(
            ClientSlashActionVisibilityInput(
                visibleUserMessageCount: 0,
                hasUndoableUserMessage: false,
                hasActiveSessionRevert: true,
                hasSelectedComposerModel: true
            )
        )
        #expect(visible == [.new, .redo])
    }

    @Test func rowTitle_matchesAllowlistCopy() {
        #expect(ClientSlashAction.new.rowTitle == "[new] Start a new session")
        #expect(ClientSlashAction.fork.rowTitle == "[fork] Fork this session")
        #expect(ClientSlashAction.undo.rowTitle == "[undo] Undo last message")
        #expect(ClientSlashAction.redo.rowTitle == "[redo] Redo undone message")
        #expect(ClientSlashAction.compact.rowTitle == "[compact] Compact session context")
    }

    @Test func visibilityInput_filtersMessagesAtOrAfterRevertBoundary() {
        let input = ClientSlashActionCatalog.visibilityInput(
            messages: [
                ClientSlashMessageRef(id: "u1", isUser: true, isPending: false, isStreaming: false, hasNonEmptyText: true),
                ClientSlashMessageRef(id: "a1", isUser: false, isPending: false, isStreaming: false, hasNonEmptyText: true),
                ClientSlashMessageRef(id: "u2", isUser: true, isPending: false, isStreaming: false, hasNonEmptyText: true),
                ClientSlashMessageRef(id: "a2", isUser: false, isPending: false, isStreaming: false, hasNonEmptyText: true),
            ],
            revertMessageID: "u2",
            hasSelectedComposerModel: true
        )
        #expect(input.visibleUserMessageCount == 1)
        #expect(input.hasUndoableUserMessage)
        #expect(input.hasActiveSessionRevert)
        #expect(input.hasSelectedComposerModel)
    }

    @Test func visibilityInput_pendingUser_doesNotCountAsUndoable() {
        let input = ClientSlashActionCatalog.visibilityInput(
            messages: [
                ClientSlashMessageRef(id: "u1", isUser: true, isPending: true, isStreaming: false, hasNonEmptyText: true),
            ],
            revertMessageID: nil,
            hasSelectedComposerModel: false
        )
        #expect(input.visibleUserMessageCount == 0)
        #expect(!input.hasUndoableUserMessage)
    }

    @Test func visibilityInput_emptyTextUser_countsForForkButNotUndo() {
        let input = ClientSlashActionCatalog.visibilityInput(
            messages: [
                ClientSlashMessageRef(id: "u1", isUser: true, isPending: false, isStreaming: false, hasNonEmptyText: false),
            ],
            revertMessageID: nil,
            hasSelectedComposerModel: true
        )
        #expect(input.visibleUserMessageCount == 1)
        #expect(!input.hasUndoableUserMessage)
    }
}
