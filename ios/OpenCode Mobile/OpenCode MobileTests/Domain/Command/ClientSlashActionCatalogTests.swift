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
        #expect(visible == [.new, .terminal, .files, .mcp])
    }

    @Test func visibleActions_sideQuestionServer_putsBtwFirst() {
        let input = ClientSlashActionVisibilityInput(
            visibleUserMessageCount: 0,
            hasUndoableUserMessage: false,
            hasActiveSessionRevert: false,
            hasSelectedComposerModel: false,
            supportsSideQuestion: true
        )

        #expect(ClientSlashActionCatalog.visibleActions(input).first == .btw)
        #expect(ClientSlashAction.btw.rowTitle == "[btw] Ask a side question")
        #expect(ClientSlashAction.btw.tileLabel == "Btw")
        var unsupported = input
        unsupported.supportsSideQuestion = false
        #expect(!ClientSlashActionCatalog.visibleActions(unsupported).contains(.btw))
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
        #expect(visible == [.new, .terminal, .files, .fork, .undo, .mcp])
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
        #expect(visible == [.new, .terminal, .files, .fork, .undo, .compact, .mcp])
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
        #expect(visible == [.new, .terminal, .files, .redo, .mcp])
    }

    @Test func rowTitle_matchesAllowlistCopy() {
        #expect(ClientSlashAction.new.rowTitle == "[new] Start a new session")
        #expect(ClientSlashAction.fork.rowTitle == "[fork] Fork this session")
        #expect(ClientSlashAction.undo.rowTitle == "[undo] Undo last message")
        #expect(ClientSlashAction.redo.rowTitle == "[redo] Redo undone message")
        #expect(ClientSlashAction.compact.rowTitle == "[compact] Compact session context")
        #expect(ClientSlashAction.terminal.rowTitle == "[terminal] Open project shell")
        #expect(ClientSlashAction.files.rowTitle == "[files] Browse project files")
        #expect(ClientSlashAction.mcp.rowTitle == "[mcp] Show MCP server status")
        #expect(ClientSlashAction.mcp.tileLabel == "MCP servers")
    }

    @Test func tileLabel_isCapitalizedCommand() {
        #expect(ClientSlashAction.new.tileLabel == "New")
        #expect(ClientSlashAction.fork.tileLabel == "Fork")
        #expect(ClientSlashAction.undo.tileLabel == "Undo")
        #expect(ClientSlashAction.redo.tileLabel == "Redo")
        #expect(ClientSlashAction.compact.tileLabel == "Compact")
        #expect(ClientSlashAction.terminal.tileLabel == "Terminal")
        #expect(ClientSlashAction.files.tileLabel == "Files")
    }

    @Test func visibleActions_filesAlwaysFollowsTerminal() {
        for count in [0, 1] {
            for revert in [false, true] {
                let visible = ClientSlashActionCatalog.visibleActions(
                    ClientSlashActionVisibilityInput(
                        visibleUserMessageCount: count,
                        hasUndoableUserMessage: count > 0,
                        hasActiveSessionRevert: revert,
                        hasSelectedComposerModel: true
                    )
                )
                let terminal = visible.firstIndex(of: .terminal)
                #expect(terminal != nil)
                #expect(visible.firstIndex(of: .files) == terminal.map { $0 + 1 })
            }
        }
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
