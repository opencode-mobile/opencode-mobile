import Foundation
import Testing
@testable import OpenCode_Mobile

/// Live v2 turns end to end: wire events (shaped like the captured `/api/event`
/// stream) through the event adapter and message store into the chip timeline
/// frame, then a REST reload over the live state.
struct V2LiveTurnTimelineTests {
    private static let session = "ses_v2"
    private let events = EventDomainAdapter()
    private let adapter = MessageDomainAdapter()
    private let store = InMemoryMessageStore()

    @Test func multiStepTurn_keepsEachStepTextInItsOwnMessageAndParagraph() {
        apply(prompt("u1", "Explore the project", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Let me look around the project.", at: 1_110))
        apply(tool("a1", callID: "call_glob", name: "glob", at: 1_120))
        apply(stepEnded("a1", finish: "tool-calls", at: 1_130))
        apply(step("a2", at: 1_200))
        apply(tool("a2", callID: "call_read", name: "read", at: 1_210))
        apply(stepEnded("a2", finish: "tool-calls", at: 1_220))
        apply(step("a3", at: 1_300))
        apply(text("a3", "`handler` is defined in `src/app.ts`.", at: 1_310))
        apply(stepEnded("a3", finish: "stop", at: 1_320))
        apply(idle(at: 1_330))

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.map(\.id) == ["u1", "a1", "a2", "a3"])
        #expect(messages[1].parts.map(\.testPartID) == ["a1:text:0", "call_glob"])
        #expect(messages[1].text == "Let me look around the project.")
        #expect(messages[3].text == "`handler` is defined in `src/app.ts`.")

        let frame = chipFrame(messages)
        #expect(frame.messages.map(\.id) == ["u1", "a1"])
        let paragraphs = stepParagraphs(frame.messages[1].blocks)
        #expect(paragraphs.map(\.paragraph.text) == [
            "Let me look around the project.",
            "`handler` is defined in `src/app.ts`.",
        ])
        #expect(Set(paragraphs.map(\.blockID)).count == paragraphs.count)
        // Each paragraph keeps the markdown its own message was decorated with.
        #expect(paragraphs.map { $0.paragraph.renderedMarkdown?.sourceHash } == [
            stableMarkdownSourceHash("Let me look around the project."),
            stableMarkdownSourceHash("`handler` is defined in `src/app.ts`."),
        ])
    }

    @Test func secondTurn_staysBelowFirstReplyAndUnmerged() {
        apply(prompt("u1", "First", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "## Reply one", at: 1_110))
        apply(stepEnded("a1", finish: "stop", at: 1_120))
        apply(idle(at: 1_130))
        apply(prompt("u2", "Second", at: 2_000))
        apply(step("a2", at: 2_100))
        apply(text("a2", "## Reply two", at: 2_110))
        apply(stepEnded("a2", finish: "stop", at: 2_120))
        apply(idle(at: 2_130))

        let frame = chipFrame(store.messages(sessionID: Self.session))
        #expect(frame.messages.map(\.id) == ["u1", "a1", "u2", "a2"])
        #expect(frame.messages[3].message.text == "## Reply two")
    }

    @Test func steerAndQueuedPromptsInOneExecution_renderInDeliveryOrder() {
        apply(prompt("u1", "First prompt", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(enqueued("u2", "Steer prompt", at: 1_150))
        apply(text("a1", "Answer to the first prompt.", at: 1_200))
        apply(stepEnded("a1", finish: "stop", at: 1_210))
        apply(delivered("u2", at: 1_220))
        apply(step("a2", at: 1_300))
        apply(enqueued("u3", "Queued prompt", at: 1_350))
        apply(text("a2", "Answer to the steer prompt.", at: 1_400))
        apply(stepEnded("a2", finish: "stop", at: 1_410))
        apply(delivered("u3", at: 1_420))
        apply(step("a3", at: 1_500))
        apply(text("a3", "Answer to the queued prompt.", at: 1_510))
        apply(stepEnded("a3", finish: "stop", at: 1_520))
        apply(idle(at: 1_530))

        let frame = chipFrame(store.messages(sessionID: Self.session))
        #expect(frame.messages.map(\.id) == ["u1", "a1", "u2", "a2", "u3", "a3"])
        #expect(frame.messages.filter { $0.message.role == .assistant }.map(\.message.text) == [
            "Answer to the first prompt.",
            "Answer to the steer prompt.",
            "Answer to the queued prompt.",
        ])
    }

    @Test func restReloadAfterLiveTurn_replacesLivePartsInPlace() throws {
        apply(prompt("u1", "Explore", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Let me look around the project.", at: 1_110))
        apply(tool("a1", callID: "call_glob", name: "glob", at: 1_120))
        apply(stepEnded("a1", finish: "tool-calls", at: 1_130))
        apply(step("a2", at: 1_200))
        apply(text("a2", "Done.", at: 1_210))
        apply(stepEnded("a2", finish: "stop", at: 1_220))

        let rest = try restMessages(
            """
            [
              {"id":"u1","type":"user","text":"Explore","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[
                {"type":"text","text":"Let me look around the project."},
                {"type":"tool","id":"call_glob","name":"glob","state":{"status":"completed","input":{}}}
              ],"time":{"created":1100,"completed":1130}},
              {"id":"a2","type":"assistant","content":[{"type":"text","text":"Done."}],
               "time":{"created":1200,"completed":1220}}
            ]
            """
        )
        #expect(store.mergeAuthoritativeIfRevision(
            sessionID: Self.session,
            messages: rest,
            expectedRevision: store.revision(sessionID: Self.session)
        ))

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.map(\.id) == ["u1", "a1", "a2"])
        #expect(messages[1].parts.map(\.testPartID) == ["a1:text:0", "call_glob"])
        #expect(messages[2].parts.map(\.testPartID) == ["a2:text:0"])
        #expect(messages[2].text == "Done.")
    }

    @Test func catchUpReload_dropsStaleWaitingPromptAndKeepsTurnsApart() throws {
        // Foreground catch-up: SSE saw turn 1 and the enqueue of prompt 2, then
        // dropped. REST has the whole second turn.
        apply(prompt("u1", "Tell me about the handler", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Reply `one`.", at: 1_110))
        apply(stepEnded("a1", finish: "stop", at: 1_120))
        apply(idle(at: 1_130))
        apply(enqueued("u2", "Second turn while backgrounded", at: 2_000))

        let rest = try restMessages(
            """
            [
              {"id":"u1","type":"user","text":"Tell me about the handler","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[{"type":"text","text":"Reply `one`."}],
               "time":{"created":1100,"completed":1120}},
              {"id":"u2","type":"user","text":"Second turn while backgrounded","time":{"created":2000}},
              {"id":"a2","type":"assistant","content":[{"type":"text","text":"Answer two."}],
               "time":{"created":2100,"completed":2120}}
            ]
            """
        )
        #expect(store.mergeAuthoritativeIfRevision(
            sessionID: Self.session,
            messages: rest,
            expectedRevision: store.revision(sessionID: Self.session)
        ))

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.map(\.id) == ["u1", "a1", "u2", "a2"])
        #expect(messages[2].isWaitingForPickup == false)
        #expect(chipFrame(messages).messages.map(\.id) == ["u1", "a1", "u2", "a2"])
    }

    @Test func liveInfoAfterRestSeed_keepsTheReloadedTimestamp() throws {
        let rest = try restMessages(
            """
            [
              {"id":"u1","type":"user","text":"Go","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[{"type":"text","text":"Working"}],"time":{"created":1100}}
            ]
            """
        )
        store.replaceAll(sessionID: Self.session, messages: rest)
        // A partial v2 assistant info (text.started) must not wipe the REST time.
        apply(textStarted("a1", ordinal: 1, at: 1_200))
        apply(prompt("u2", "Next", at: 2_000))

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.first { $0.id == "a1" }?.sentAtEpochMs == 1_100)
        #expect(chipFrame(messages).messages.map(\.id) == ["u1", "a1", "u2"])
    }

    @Test func revertCommitted_dropsTheRevertedTurnAndEverythingAfterIt() {
        apply(prompt("u1", "First prompt", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Answer one.", at: 1_110))
        apply(stepEnded("a1", finish: "stop", at: 1_120))
        apply(idle(at: 1_130))
        apply(prompt("u2", "Queued prompt", at: 2_000))
        apply(step("a2", at: 2_100))
        apply(text("a2", "Answer two.", at: 2_110))
        apply(stepEnded("a2", finish: "stop", at: 2_120))
        apply(idle(at: 2_130))
        // Captured shape: staged on "Revert message", committed when the edit is sent.
        apply([envelope("session.revert.staged", 3_000, #""revert":{"messageID":"u2","files":[]}"#)])
        #expect(store.messages(sessionID: Self.session).map(\.id) == ["u1", "a1", "u2", "a2"])

        apply([envelope("session.revert.committed", 3_100, #""to":"u2""#)])
        apply(prompt("u3", "Queued prompt v2", at: 3_110))

        #expect(store.messages(sessionID: Self.session).map(\.id) == ["u1", "a1", "u3"])
    }

    // MARK: Wire events

    private func prompt(_ id: String, _ text: String, at: Int64) -> [String] {
        enqueued(id, text, at: at) + delivered(id, at: at + 10)
    }

    private func enqueued(_ id: String, _ text: String, at: Int64) -> [String] {
        [envelope(
            "session.inbox.enqueued",
            at,
            #""inboxID":"\#(id)","item":{"id":"\#(id)","type":"user","payload":{"text":"\#(text)"},"delivery":"steer"}"#
        )]
    }

    private func delivered(_ id: String, at: Int64) -> [String] {
        [envelope("session.inbox.delivered", at, #""inboxID":"\#(id)""#)]
    }

    private func step(_ id: String, at: Int64) -> [String] {
        [envelope(
            "session.step.started",
            at,
            #""agent":"build","model":{"id":"m","providerID":"p"},"assistantMessageID":"\#(id)""#
        )]
    }

    private func textStarted(_ id: String, ordinal: Int, at: Int64) -> [String] {
        [envelope("session.text.started", at, #""assistantMessageID":"\#(id)","ordinal":\#(ordinal)"#)]
    }

    private func text(_ id: String, _ value: String, at: Int64) -> [String] {
        let fields = #""assistantMessageID":"\#(id)","ordinal":0"#
        let encoded = jsonString(value)
        return textStarted(id, ordinal: 0, at: at)
            + [envelope("session.text.delta", at + 1, "\(fields),\"delta\":\(encoded)")]
            + [envelope("session.text.ended", at + 2, "\(fields),\"text\":\(encoded)")]
    }

    private func tool(_ id: String, callID: String, name: String, at: Int64) -> [String] {
        let fields = #""assistantMessageID":"\#(id)","id":"\#(callID)","name":"\#(name)""#
        return [
            envelope("session.tool.input.started", at, fields),
            envelope("session.tool.called", at + 1, fields + #","input":{"pattern":"*"}"#),
            envelope("session.tool.success", at + 2, fields + #","content":[{"type":"text","text":"ok"}]"#),
        ]
    }

    private func stepEnded(_ id: String, finish: String, at: Int64) -> [String] {
        [envelope("session.step.ended", at, #""assistantMessageID":"\#(id)","finish":"\#(finish)""#)]
    }

    private func idle(at: Int64) -> [String] {
        [envelope("session.execution.succeeded", at, "")]
    }

    private func envelope(_ type: String, _ created: Int64, _ fields: String) -> String {
        let session = #""sessionID":"\#(Self.session)""#
        let data = fields.isEmpty ? session : "\(session),\(fields)"
        return #"{"id":"evt_\#(type)\#(created)","created":\#(created),"type":"\#(type)","data":{\#(data)}}"#
    }

    private func jsonString(_ value: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [value], options: [.fragmentsAllowed])
        let array = String(decoding: data, as: UTF8.self)
        return String(array.dropFirst().dropLast())
    }

    private func apply(_ lines: [String]) {
        for event in lines.flatMap({ events.parseEvents(dataLine: $0) }) {
            guard event.sessionID == Self.session, let properties = event.rawProperties else { continue }
            switch event.type {
            case "message.updated":
                guard let info = properties["info"]?.dictionaryValue else { continue }
                _ = store.apply(sessionID: Self.session, patch: .infoUpdated(info))
            case "message.part.updated":
                guard let part = properties["part"]?.dictionaryValue else { continue }
                _ = store.apply(
                    sessionID: Self.session,
                    patch: .partUpdated(part: part, delta: properties["delta"]?.stringValue)
                )
            case "message.part.delta":
                guard let messageID = properties["messageID"]?.stringValue,
                      let partID = properties["partID"]?.stringValue,
                      let delta = properties["delta"]?.stringValue
                else { continue }
                _ = store.apply(
                    sessionID: Self.session,
                    patch: .partDelta(messageID: messageID, partID: partID, delta: delta)
                )
            case "session.revert.committed":
                guard let messageID = properties["messageID"]?.stringValue else { continue }
                _ = store.apply(sessionID: Self.session, patch: .removedFrom(messageID: messageID))
            case "session.idle":
                store.finishStreamingText(sessionID: Self.session)
            default:
                continue
            }
        }
    }

    // MARK: Display

    /// Chip timeline frame with every message decorated by its own document.
    private func chipFrame(_ messages: [Message]) -> DisplayFrame {
        DisplayFrameBuilder.build(
            sessionKey: "v2-live-\(UUID().uuidString)",
            frameRevision: 1,
            sourceRevision: 1,
            displayMessages: messages.map { message in
                DisplayMessage(
                    message: message,
                    renderedMarkdown: message.text.isEmpty
                        ? nil
                        : NativeMarkdownDocument(
                            sourceHash: stableMarkdownSourceHash(message.text),
                            blocks: [.paragraph([.text(message.text)])]
                        )
                )
            },
            chipTimeline: true
        )
    }

    private func stepParagraphs(
        _ blocks: [SessionChatTimelineBlock]
    ) -> [(blockID: String, paragraph: SessionChatStepParagraphBlock)] {
        blocks.compactMap { block in
            guard case .stepParagraph(_, let paragraph) = block else { return nil }
            return (block.blockID, paragraph)
        }
    }

    private func restMessages(_ body: String) throws -> [Message] {
        let dtos = try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(body.utf8))
        return adapter.messages(fromV2: dtos)
    }
}
