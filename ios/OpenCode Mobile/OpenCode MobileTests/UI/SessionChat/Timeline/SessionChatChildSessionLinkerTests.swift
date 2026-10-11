import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatChildSessionLinkerTests {
    @Test func link_runningSubagentWithoutID_matchesChildByDescriptionPrefix() {
        let messages = [assistant("msg-1", [subagent("prt-1", description: "Android dictation card")])]
        let children = [
            child("ses-other", title: "Unrelated work", createdAt: 1),
            child("ses-child", title: "Android dictation card (@general subagent)", createdAt: 2),
        ]

        let linked = SessionChatChildSessionLinker.link(messages, children: children)

        #expect(sessionIDs(linked) == ["ses-child"])
    }

    @Test func link_metadataIDWinsAndIsNotReusedForAnotherPart() {
        let messages = [
            assistant("msg-1", [subagent("prt-1", description: "Explore", metadataSessionID: "ses-a")]),
            assistant("msg-2", [subagent("prt-2", description: "Explore")]),
        ]
        let children = [
            child("ses-a", title: "Explore", createdAt: 1),
            child("ses-b", title: "Explore", createdAt: 2),
        ]

        let linked = SessionChatChildSessionLinker.link(messages, children: children)

        #expect(sessionIDs(linked) == ["ses-a", "ses-b"])
    }

    @Test func link_parallelSameDescription_mapsInSpawnOrder() {
        let messages = [
            assistant("msg-1", [
                subagent("prt-1", description: "Review"),
                subagent("prt-2", description: "Review"),
            ]),
        ]
        let children = [
            child("ses-late", title: "Review", createdAt: 20),
            child("ses-early", title: "Review", createdAt: 10),
        ]

        let linked = SessionChatChildSessionLinker.link(messages, children: children)

        #expect(sessionIDs(linked) == ["ses-early", "ses-late"])
    }

    @Test func link_resumedSubagent_usesInputSessionID() {
        let messages = [assistant("msg-1", [subagent("prt-1", description: "Continue", inputSessionID: "ses-resumed")])]

        let linked = SessionChatChildSessionLinker.link(messages, children: [])

        #expect(sessionIDs(linked) == ["ses-resumed"])
    }

    @Test func link_noMatchingChild_leavesPartUnlinked() {
        let messages = [assistant("msg-1", [subagent("prt-1", description: "Explore")])]

        let linked = SessionChatChildSessionLinker.link(
            messages,
            children: [child("ses-other", title: "Something else", createdAt: 1)]
        )

        #expect(linked == messages)
    }

    @Test func link_nonSubagentToolsUntouched() {
        let shell = MessagePart.tool(
            ToolPart(id: "prt-1", toolName: "shell", status: .running, input: ["description": .string("Explore")])
        )
        let messages = [assistant("msg-1", [shell])]

        let linked = SessionChatChildSessionLinker.link(
            messages,
            children: [child("ses-child", title: "Explore", createdAt: 1)]
        )

        #expect(linked == messages)
    }

    @Test func unlinkedPartIDs_skipsPartsWithOwnID() {
        let messages = [
            assistant("msg-1", [
                subagent("prt-1", description: "A", metadataSessionID: "ses-a"),
                subagent("prt-2", description: "B", inputSessionID: "ses-b"),
                subagent("prt-3", description: "C"),
                subagent("prt-4", description: nil),
            ]),
        ]

        #expect(SessionChatChildSessionLinker.unlinkedPartIDs(in: messages) == ["prt-3"])
    }

    private func assistant(_ id: String, _ parts: [MessagePart]) -> Message {
        Message(id: id, role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: nil)
    }

    private func subagent(
        _ id: String,
        description: String?,
        metadataSessionID: String? = nil,
        inputSessionID: String? = nil
    ) -> MessagePart {
        var input: [String: AnyCodable] = ["agent": .string("general")]
        if let description { input["description"] = .string(description) }
        if let inputSessionID { input["sessionID"] = .string(inputSessionID) }
        return .tool(
            ToolPart(
                id: id,
                toolName: "subagent",
                status: .running,
                input: input,
                metadata: metadataSessionID.map { ["sessionID": .string($0)] }
            )
        )
    }

    private func child(_ id: String, title: String, createdAt: Int64) -> SessionChatChildSessionCandidate {
        SessionChatChildSessionCandidate(id: id, title: title, createdAtEpochMs: createdAt)
    }

    private func sessionIDs(_ messages: [Message]) -> [String] {
        messages.flatMap(\.parts).compactMap { part in
            guard case .tool(let tool) = part else { return nil }
            return tool.metadata?["sessionID"]?.stringValue
        }
    }
}
