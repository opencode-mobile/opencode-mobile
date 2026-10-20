import Foundation
import Testing
@testable import OpenCode_Mobile

/// Live v2 turns end to end: wire events (shaped like the captured `/api/event`
/// stream) through the event adapter and message store into the chip timeline
/// frame, then a REST reload over the live state.
struct V2LiveTurnTimelineTests {
    private static let session = "ses_v2"
    private static let summary = "## Objective\n- Exercise queue and steer prompts.\n\n## Next Move\n- Wait."
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

    @Test func senderReloadsWithInboxPrompts_keepEnqueueTimeAndDeliveryOrder() throws {
        // The sending app reloads REST after every POST, so each follow-up is
        // first seen as an inbox item merged into the tail, not as a live info.
        apply(prompt("u1", "First prompt", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(enqueued("u2", "Steer prompt", at: 1_150))
        try reload(
            """
            [
              {"id":"u1","type":"user","text":"First prompt","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[{"type":"text","text":""}],"time":{"created":1100}}
            ]
            """,
            inbox: #"[{"id":"u2","sessionID":"ses_v2","time":{"created":1150},"type":"user","payload":{"text":"Steer prompt"},"delivery":"steer"}]"#
        )
        let steerWaiting = try #require(store.messages(sessionID: Self.session).first { $0.id == "u2" })
        #expect(steerWaiting.isWaitingForPickup)
        #expect(steerWaiting.followUpDelivery == .steer)
        #expect(steerWaiting.sentAtEpochMs == 1_150)

        apply(text("a1", "Answer to the first prompt.", at: 1_200))
        apply(stepEnded("a1", finish: "stop", at: 1_210))
        apply(delivered("u2", at: 1_220))
        apply(enqueued("u3", "Queued prompt", delivery: "queue", at: 1_250))
        // Manual reply mode: the steer reply has not started, so REST has no a2 yet.
        try reload(
            """
            [
              {"id":"u1","type":"user","text":"First prompt","time":{"created":1000}},
              {"id":"a1","type":"assistant","content":[{"type":"text","text":"Answer to the first prompt."}],
               "time":{"created":1100,"completed":1210}},
              {"id":"u2","type":"user","text":"Steer prompt","time":{"created":1150}}
            ]
            """,
            inbox: #"[{"id":"u3","sessionID":"ses_v2","time":{"created":1250},"type":"user","payload":{"text":"Queued prompt"},"delivery":"queue"}]"#
        )
        #expect(store.messages(sessionID: Self.session).first { $0.id == "u3" }?.isWaitingForPickup == true)

        apply(step("a2", at: 1_300))
        apply(text("a2", "Answer to the steer prompt.", at: 1_400))
        apply(stepEnded("a2", finish: "stop", at: 1_410))
        apply(delivered("u3", at: 1_420))
        apply(step("a3", at: 1_500))
        apply(text("a3", "Answer to the queued prompt.", at: 1_510))
        apply(stepEnded("a3", finish: "stop", at: 1_520))
        apply(idle(at: 1_530))

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.allSatisfy { ($0.sentAtEpochMs ?? 0) >= 1_000 })
        #expect(messages.allSatisfy { !$0.isWaitingForPickup })
        let frame = chipFrame(messages)
        #expect(frame.messages.map(\.id) == ["u1", "a1", "u2", "a2", "u3", "a3"])
        #expect(frame.messages.filter { $0.message.role == .assistant }.map(\.message.text) == [
            "Answer to the first prompt.",
            "Answer to the steer prompt.",
            "Answer to the queued prompt.",
        ])
    }

    @Test func deliveredInboxPrompt_keepsTheSessionAgentAndModel() throws {
        // The sender sees its queued prompt as an inbox item, then only a partial live info.
        try reload(
            """
            [
              {"id":"s1","type":"agent-switched","agent":"build","time":{"created":900}},
              {"id":"s2","type":"model-switched","model":{"id":"scripted","providerID":"sim","variant":"high"},
               "time":{"created":901}},
              {"id":"u1","type":"user","text":"First prompt","time":{"created":1000}},
              {"id":"a1","type":"assistant","agent":"build",
               "model":{"id":"scripted","providerID":"sim","variant":"high"},
               "content":[{"type":"text","text":"Answer."}],"time":{"created":1100}}
            ]
            """,
            inbox: #"[{"id":"u2","sessionID":"ses_v2","time":{"created":1150},"type":"user","payload":{"text":"Queued prompt"},"delivery":"queue"}]"#
        )
        apply(delivered("u2", at: 1_220))

        let queued = try #require(store.messages(sessionID: Self.session).first { $0.id == "u2" })
        #expect(!queued.isWaitingForPickup)
        #expect(queued.mode == "build")
        #expect(queued.model == "scripted")
        #expect(queued.variant == "high")
    }

    @Test func liveV2Prompts_getTheAgentAndModelRestRecords() throws {
        // Shaped like the B2-5 sim run: a server-injected prompt, a steer from this app (which
        // switches agent and model first), then more injected prompts and steers.
        apply(prompt("u1", "Say hello.", at: 1_000))
        apply(step("a1", at: 1_050, model: Self.scripted))
        apply(agentSelected("build", at: 1_200))
        apply(modelSelected(Self.scriptedHigh, at: 1_201))
        apply(enqueued("u2", "Steer from Android", at: 1_202))
        apply(text("a1", "Hello!", at: 1_300))
        apply(stepEnded("a1", finish: "stop", at: 1_310))
        apply(delivered("u2", at: 1_320))
        apply(step("a2", at: 1_330, model: Self.scriptedHigh))
        apply(text("a2", "Steered.", at: 1_340))
        apply(stepEnded("a2", finish: "stop", at: 1_350))
        apply(idle(at: 1_360))
        apply(prompt("u3", "Second hello.", at: 2_000))
        apply(agentSelected("build", at: 2_020))
        apply(step("a3", at: 2_030, model: Self.scriptedHigh))
        apply(text("a3", "Hello again!", at: 2_040))
        apply(stepEnded("a3", finish: "stop", at: 2_050))
        apply(idle(at: 2_060))

        let live = store.messages(sessionID: Self.session).filter { $0.role == .user }
        let rest = try restMessages(
            """
            [
              {"id":"u1","type":"user","text":"Say hello.","time":{"created":1000}},
              {"id":"s1","type":"agent-switched","agent":"build","time":{"created":1200}},
              {"id":"s2","type":"model-switched","model":\(Self.scriptedHigh),"time":{"created":1201}},
              {"id":"a1","type":"assistant","agent":"build","model":\(Self.scripted),
               "content":[{"type":"text","text":"Hello!"}],"time":{"created":1050}},
              {"id":"u2","type":"user","text":"Steer from Android","time":{"created":1320}},
              {"id":"a2","type":"assistant","agent":"build","model":\(Self.scriptedHigh),
               "content":[{"type":"text","text":"Steered."}],"time":{"created":1330}},
              {"id":"u3","type":"user","text":"Second hello.","time":{"created":2000}},
              {"id":"s3","type":"agent-switched","agent":"build","time":{"created":2020}},
              {"id":"a3","type":"assistant","agent":"build","model":\(Self.scriptedHigh),
               "content":[{"type":"text","text":"Hello again!"}],"time":{"created":2030}}
            ]
            """
        ).filter { $0.role == .user }

        #expect(live.map(\.id) == ["u1", "u2", "u3"])
        #expect(live.map(metaLine) == rest.map(metaLine))
        #expect(live.map(metaLine) == [nil, "build|scripted|nil", "build|scripted|high"])
    }

    @Test func switchBeforeAPrompt_appliesToThatPrompt() throws {
        apply(prompt("u1", "First", at: 1_000))
        apply(step("a1", at: 1_050, model: Self.scriptedHigh))
        apply(text("a1", "Reply.", at: 1_060))
        apply(stepEnded("a1", finish: "stop", at: 1_070))
        apply(idle(at: 1_080))
        apply(agentSelected("plan", at: 2_000))
        apply(modelSelected(#"{"id":"scripted","providerID":"sim","variant":"low"}"#, at: 2_001))
        apply(prompt("u2", "Second", at: 2_010))

        let second = try #require(store.messages(sessionID: Self.session).first { $0.id == "u2" })
        #expect(metaLine(second) == "plan|scripted|low")
    }

    @Test func liveNotices_areStandaloneRowsBetweenSteps_andRESTReplacesThemInPlace() throws {
        apply(prompt("u1", "Plan it, then build it", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Planning first.", at: 1_110))
        apply(tool("a1", callID: "call_glob", name: "glob", at: 1_120))
        apply(stepEnded("a1", finish: "tool-calls", at: 1_130))
        apply(agentSelected("plan", at: 1_200))
        apply(modelSelected(Self.scriptedHigh, at: 1_201))
        apply([envelope("session.moved", 1_202, #""location":{"directory":"/repo/wt"},"projectID":"p""#)])
        apply(step("a2", at: 1_300, model: Self.scriptedHigh))
        apply(text("a2", "Now building.", at: 1_310))
        apply(stepEnded("a2", finish: "stop", at: 1_320))
        apply(idle(at: 1_330))

        let agentID = "msg_session.agent.selected1200"
        let modelID = "msg_session.model.selected1201"
        let movedID = "msg_session.moved1202"
        let live = store.messages(sessionID: Self.session)
        #expect(live.filter { $0.role == .notice }.map(\.id) == [agentID, modelID, movedID])
        #expect(live.filter { $0.role == .user }.map(\.id) == ["u1"])

        let frame = chipFrame(live)
        #expect(frame.messages.map(\.id) == ["u1", "a1", agentID, modelID, movedID, "a2"])
        #expect(frame.messages[2].blocks == [
            .notice(
                blockID: PresentationBlockId.direct(agentID),
                notice: TimelineNotice(id: agentID, kind: .agentChanged(agent: "plan", previous: nil))
            ),
        ])
        #expect(stepParagraphs(frame.messages[1].blocks).map(\.paragraph.text) == ["Planning first."])
        #expect(stepParagraphs(frame.messages[5].blocks).map(\.paragraph.text) == ["Now building."])

        try reload(
            """
            [
              {"id":"u1","type":"user","text":"Plan it, then build it","time":{"created":1000}},
              {"id":"a1","type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},
               "content":[{"type":"text","text":"Planning first."},
                 {"type":"tool","id":"call_glob","name":"glob","state":{"status":"completed","input":{"pattern":"*"},
                  "content":[{"type":"text","text":"ok"}]}}],
               "time":{"created":1100,"completed":1130}},
              {"id":"\(agentID)","type":"agent-switched","agent":"plan","previous":"build","time":{"created":1200}},
              {"id":"\(modelID)","type":"model-switched","model":\(Self.scriptedHigh),
               "previous":{"id":"m","providerID":"p"},"time":{"created":1201}},
              {"id":"\(movedID)","type":"location-switched","location":{"directory":"/repo/wt"},
               "previous":{"location":{"directory":"/repo"}},"time":{"created":1202}},
              {"id":"a2","type":"assistant","agent":"plan","model":\(Self.scriptedHigh),
               "content":[{"type":"text","text":"Now building."}],"time":{"created":1300,"completed":1320}}
            ]
            """,
            inbox: "[]"
        )

        let reloaded = store.messages(sessionID: Self.session)
        #expect(reloaded.map(\.id) == ["u1", "a1", agentID, modelID, movedID, "a2"])
        let agent = try #require(reloaded.first { $0.id == agentID })
        #expect(agent.parts == [.notice(TimelineNotice(id: agentID, kind: .agentChanged(agent: "plan", previous: "build")))])
        #expect(chipFrame(reloaded).messages.map(\.id) == ["u1", "a1", agentID, modelID, movedID, "a2"])
    }

    @Test func liveRestartSynthetic_isANoticeNotAPrompt() throws {
        apply(prompt("u1", "Long task", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Working.", at: 1_110))
        apply([
            envelope(
                "session.synthetic",
                1_500,
                #""text":"Continue where you left off.","description":"Continuing after restart","metadata":{"notice":"restart"}"#
            ),
        ])

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.filter { $0.role == .user }.map(\.id) == ["u1"])
        let notice = try #require(messages.first { $0.role == .notice })
        #expect(notice.id == "msg_session.synthetic1500")
        #expect(notice.parts == [.notice(TimelineNotice(id: notice.id, kind: .restart))])
        let frame = chipFrame(messages)
        #expect(frame.messages.last?.blocks.count == 1)
        #expect(frame.messages.last?.message.role == .notice)
    }

    @Test func stoppedTool_isInterruptedNotFailed_liveAndAfterReload() throws {
        apply(prompt("u1", "Start the deployment.", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Starting the deployment now.", at: 1_110))
        let fields = #""assistantMessageID":"a1","id":"call_8","name":"sim_task""#
        apply([
            envelope("session.tool.input.started", 1_120, fields),
            envelope("session.tool.called", 1_121, fields + #","input":{"title":"Deploy"}"#),
            envelope(
                "session.tool.failed",
                1_200,
                fields + #","error":{"type":"aborted","message":"Tool execution interrupted"},"executed":false"#
            ),
        ])

        let message = try #require(store.messages(sessionID: Self.session).first { $0.id == "a1" })
        let liveTool = try #require(message.parts.compactMap { part -> ToolPart? in
            if case .tool(let tool) = part { return tool }
            return nil
        }.first)
        #expect(liveTool.isInterrupted)
        let frame = chipFrame(store.messages(sessionID: Self.session))
        let paragraph = try #require(stepParagraphs(frame.messages[1].blocks).first?.paragraph)
        #expect(paragraph.errorLines.isEmpty)
        #expect(paragraph.chips.first?.card.isInterrupted == true)
        #expect(paragraph.chips.first?.card.errorText == "Stopped")

        let rest = try restMessages(
            """
            [
              {"id":"u1","type":"user","text":"Start the deployment.","time":{"created":1000}},
              {"id":"a1","type":"assistant","finish":"error","error":{"type":"aborted","message":"Step interrupted"},
               "content":[{"type":"text","text":"Starting the deployment now."},
                 {"type":"tool","id":"call_8","name":"sim_task","state":{"status":"error","input":{},
                  "error":{"type":"aborted","message":"Tool execution interrupted"}}}],
               "time":{"created":1100,"completed":1200}}
            ]
            """
        )
        let restTool = rest[1].parts.compactMap { part -> ToolPart? in
            if case .tool(let tool) = part { return tool }
            return nil
        }.first
        #expect(restTool?.isInterrupted == true)
    }

    @Test func streamingShellArguments_growOnThePendingCardUntilCalledSettlesThem() throws {
        apply(prompt("u1", "List files", at: 1_000))
        apply(step("a1", at: 1_100))
        let fields = #""assistantMessageID":"a1","id":"call_sh","name":"bash""#
        apply([envelope("session.tool.input.started", 1_120, fields)])
        // Servers that do not stream tool input still show the placeholder until `ended`.
        #expect(liveToolCard("a1")?.subtitle == "Writing command…")
        apply([inputDelta(fields, #"{"comm"#, at: 1_121)])

        let waiting = try #require(liveToolCard("a1"))
        #expect(waiting.status == .pending)
        #expect(waiting.isReceivingArguments)
        #expect(waiting.subtitle == "Writing command…")

        apply([inputDelta(fields, #"and":"ls -la /tm"#, at: 1_122)])
        #expect(liveToolCard("a1")?.subtitle == "ls -la /tm")
        apply([inputDelta(fields, #"p/scratch","description":"List"#, at: 1_123)])
        #expect(liveToolCard("a1")?.subtitle == "ls -la /tmp/scratch")
        let frame = chipFrame(store.messages(sessionID: Self.session))
        let chip = try #require(stepParagraphs(frame.messages[1].blocks).first?.paragraph.chips.first)
        #expect(chip.label == "ls -la /tmp/scratch")

        apply([
            envelope(
                "session.tool.called",
                1_130,
                fields + #","input":{"command":"ls -la /tmp","description":"List"}"#
            ),
        ])
        #expect(liveTool("a1")?.input?[ToolArgumentsPreview.inputKey] == nil)
        let called = try #require(liveToolCard("a1"))
        #expect(called.status == .running)
        #expect(!called.isReceivingArguments)
        #expect(called.subtitle == "ls -la /tmp")

        apply([envelope("session.tool.success", 1_140, fields + #","content":[{"type":"text","text":"ok"}]"#)])
        let done = try #require(liveToolCard("a1"))
        #expect(done.status == .completed)
        #expect(!done.isReceivingArguments)
        #expect(done.subtitle == "ls -la /tmp")
        #expect(done.detailText == "ok")
    }

    @Test func streamingFileArguments_showThePathOnReadEditAndWriteCards() throws {
        apply(prompt("u1", "Change files", at: 1_000))
        apply(step("a1", at: 1_100))
        let cases: [(name: String, placeholder: String, partial: String, path: String)] = [
            ("read", "Preparing read…", #""filePath":"/repo/src/app.ts"#, "/repo/src/app.ts"),
            ("edit", "Preparing edit…", #""filePath":"/repo/src/app.ts","oldString":"a"#, "/repo/src/app.ts"),
            ("write", "Preparing write…", #""content":"line 1\nline 2","filePath":"/repo/NOTES"#, "/repo/NOTES"),
        ]
        for (index, item) in cases.enumerated() {
            let fields = #""assistantMessageID":"a1","id":"call_\#(item.name)","name":"\#(item.name)""#
            let at = Int64(1_200 + index * 10)
            apply([envelope("session.tool.input.started", at, fields), inputDelta(fields, "{", at: at + 1)])
            #expect(liveToolCard("a1", callID: "call_\(item.name)")?.subtitle == item.placeholder)
            apply([inputDelta(fields, item.partial, at: at + 2)])
            let card = try #require(liveToolCard("a1", callID: "call_\(item.name)"))
            #expect(card.isReceivingArguments)
            #expect(card.subtitle == item.path)
            #expect(!card.opensChangesReview)
        }
    }

    @Test func inputEnded_replacesThePreviewWithTheSettledArguments() throws {
        apply(prompt("u1", "List files", at: 1_000))
        apply(step("a1", at: 1_100))
        let fields = #""assistantMessageID":"a1","id":"call_sh","name":"bash""#
        apply([
            envelope("session.tool.input.started", 1_120, fields),
            inputDelta(fields, #"{"command":"ls -l"#, at: 1_121),
            envelope("session.tool.input.ended", 1_122, fields + #","text":"{\"command\":\"ls -la\"}""#),
        ])

        #expect(liveTool("a1")?.input == ["command": .string("ls -la")])
        let card = try #require(liveToolCard("a1"))
        #expect(card.status == .pending)
        #expect(!card.isReceivingArguments)
        #expect(card.subtitle == "ls -la")
    }

    @Test func restReloadMidStream_keepsTheStreamingArgumentsAsThePreview() throws {
        let rest = try restMessages(
            #"""
            [
              {"id":"a1","type":"assistant","content":[
                {"type":"tool","id":"call_sh","name":"bash","state":{"status":"streaming","input":"{\"command\":\"git st"}}],
               "time":{"created":1100}}
            ]
            """#
        )
        let card = try #require(toolCards(rest[0].parts).first)
        #expect(card.isReceivingArguments)
        #expect(card.subtitle == "git st")
    }

    @Test func v1AndSettledCards_keepTodaysChrome() {
        let v1Pending = ToolPart(id: "call_v1", toolName: "bash", status: .pending)
        let v1Card = toolCards([.tool(v1Pending)]).first
        #expect(v1Card?.isReceivingArguments == false)
        #expect(v1Card?.subtitle == nil)

        // A settled card ignores a stray preview the server never replaced.
        let stray: [String: AnyCodable] = [ToolArgumentsPreview.inputKey: .string(#"{"command":"rm"#)]
        for status in [ToolStatus.completed, .error] {
            let card = toolCards([.tool(ToolPart(id: "call_\(status)", toolName: "bash", status: status, input: stray))]).first
            #expect(card?.isReceivingArguments == false)
            #expect(card?.subtitle == nil)
        }
        let generic = ToolPart(id: "call_g", toolName: "sim_task", status: .pending, input: stray)
        #expect(toolCards([.tool(generic)]).first?.subtitle == nil)
    }

    @Test func realToolFailure_staysAnError() throws {
        apply(prompt("u1", "Run it", at: 1_000))
        apply(step("a1", at: 1_100))
        let fields = #""assistantMessageID":"a1","id":"call_9","name":"sim_task""#
        apply([
            envelope("session.tool.called", 1_121, fields + #","input":{}"#),
            envelope("session.tool.failed", 1_200, fields + #","error":{"type":"unknown","message":"lock timeout"}"#),
        ])
        let message = try #require(store.messages(sessionID: Self.session).first { $0.id == "a1" })
        let tool = message.parts.compactMap { part -> ToolPart? in
            if case .tool(let tool) = part { return tool }
            return nil
        }.first
        #expect(tool?.isInterrupted == false)
    }

    @Test func truncatedReply_keepsACutOffNote_liveAndFromRest() throws {
        apply(prompt("u1", "Write an essay", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "This is a very long essay that never", at: 1_110))
        apply(stepEnded("a1", finish: "length", at: 1_200))
        // A later partial info (usage) must not drop how the step ended.
        apply([
            envelope(
                "session.usage.updated",
                1_210,
                #""assistantMessageID":"a1","tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}"#
            ),
        ])
        apply(idle(at: 1_300))

        let live = try #require(store.messages(sessionID: Self.session).first { $0.id == "a1" })
        #expect(live.stopReason == .length)
        let frame = chipFrame(store.messages(sessionID: Self.session))
        #expect(frame.messages[1].blocks.last == .stopNote(blockID: "a1:stop-note", reason: .length))

        let rest = try restMessages(
            #"[{"id":"a1","type":"assistant","finish":"length","content":[{"type":"text","text":"Cut"}],"time":{"created":1100,"completed":1200}}]"#
        )
        #expect(rest.first?.stopReason == .length)
    }

    @Test func interruptedReply_keepsAnInterruptedNote_andNoAbortText() throws {
        apply(prompt("u1", "Explain", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "The handler lives in", at: 1_110))
        apply([
            envelope(
                "session.step.failed",
                1_200,
                #""assistantMessageID":"a1","error":{"type":"aborted","message":"Step interrupted"}"#
            ),
        ])
        #expect(store.messages(sessionID: Self.session).first { $0.id == "a1" }?.stopReason == .interrupted)
        let frame = chipFrame(store.messages(sessionID: Self.session))
        #expect(frame.messages[1].blocks.last == .stopNote(blockID: "a1:stop-note", reason: .interrupted))

        let rest = try restMessages(
            #"[{"id":"a1","type":"assistant","finish":"error","error":{"type":"aborted","message":"Step interrupted"},"content":[],"time":{"created":1100,"completed":1200}}]"#
        )
        #expect(rest.first?.stopReason == .interrupted)
        #expect(rest.first?.text == "")
        #expect(rest.first?.failure == nil)
    }

    @Test func providerFailure_keepsAFailureLineBesideTheReply() throws {
        apply(prompt("u1", "Explain", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Partial", at: 1_110))
        apply([
            envelope(
                "session.step.failed",
                1_200,
                #""assistantMessageID":"a1","error":{"type":"provider.auth","message":"Sign in","status":401,"response":{"body":"denied"}}"#
            ),
        ])
        let message = try #require(store.messages(sessionID: Self.session).first { $0.id == "a1" })
        #expect(message.text == "Partial")
        #expect(message.failure?.line() == "HTTP 401 · Sign in")
        let frame = chipFrame(store.messages(sessionID: Self.session))
        #expect(frame.messages[1].blocks.contains { block in
            if case .failureLine(_, let text, let body, let neutral, _, _, _) = block {
                return text == "HTTP 401 · Sign in" && body == "denied" && neutral == false
            }
            return false
        })
    }

    @Test func completeReply_hasNoStopNote() {
        apply(prompt("u1", "Say hi", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Hi.", at: 1_110))
        apply(stepEnded("a1", finish: "stop", at: 1_200))
        #expect(store.messages(sessionID: Self.session).first { $0.id == "a1" }?.stopReason == nil)
        let frame = chipFrame(store.messages(sessionID: Self.session))
        #expect(!frame.messages[1].blocks.contains { if case .stopNote = $0 { true } else { false } })
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

    @Test func restCompaction_isACompactionRowNotReplyText() throws {
        // `GET /api/session/:id/message` from the sim server, oldest first.
        let rest = try restMessages(
            """
            [
              {"id":"u1","type":"user","text":"Say hi","time":{"created":1000}},
              {"id":"a1","type":"assistant","agent":"build","content":[{"type":"text","text":"Hi there."}],
               "finish":"stop","time":{"created":1100,"completed":1200}},
              {"id":"i1","type":"idle","outcome":"succeeded","time":{"created":1201}},
              {"type":"compaction","id":"c1","time":{"created":1300},"status":"completed","reason":"manual",
               "summary":\(jsonString(Self.summary)),"recent":"","cost":0},
              {"id":"i2","type":"idle","outcome":"succeeded","time":{"created":1400}}
            ]
            """
        )
        store.replaceAll(sessionID: Self.session, messages: rest)

        let messages = store.messages(sessionID: Self.session)
        let compaction = try #require(messages.first { $0.id == "c1" })
        #expect(compaction.parts == [.compaction(CompactionPart(id: "c1", completed: true, summary: Self.summary))])
        #expect(compaction.text.isEmpty)

        let frame = chipFrame(messages)
        #expect(frame.messages.map(\.id) == ["u1", "a1", "c1"])
        #expect(stepParagraphs(frame.messages[1].blocks).map(\.paragraph.text) == ["Hi there."])
        let card = try #require(toolCard(frame.messages[2].blocks))
        #expect(card.title == SessionCompactChrome.markerTitle)
        #expect(card.detailText == Self.summary)
    }

    @Test func liveCompaction_startedThenEnded_isOneCompactionRowAfterTheReply() throws {
        apply(prompt("u1", "Say hi", at: 1_000))
        apply(step("a1", at: 1_100))
        apply(text("a1", "Hi there.", at: 1_110))
        apply(stepEnded("a1", finish: "stop", at: 1_120))
        apply(idle(at: 1_130))
        apply([
            envelope("session.compaction.started", 2_000, #""reason":"manual","recent":"","inputID":"msg_c1""#),
            envelope("session.compaction.delta", 2_010, ###""text":"## Objective""###),
        ])
        let running = try #require(store.messages(sessionID: Self.session).first { $0.id == "msg_c1" })
        #expect(running.parts == [.compaction(CompactionPart(id: "msg_c1"))])
        #expect(SessionCompactionPresence.isActive(messages: store.messages(sessionID: Self.session), isSessionBusy: true))

        apply([envelope(
            "session.compaction.ended",
            2_100,
            #""reason":"manual","text":\#(jsonString(Self.summary)),"recent":"","cost":0"#
        )])

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.map(\.id) == ["u1", "a1", "msg_c1"])
        #expect(messages[2].parts == [.compaction(CompactionPart(id: "msg_c1", completed: true, summary: Self.summary))])
        let frame = chipFrame(messages)
        #expect(frame.messages.map(\.id) == ["u1", "a1", "msg_c1"])
        #expect(frame.messages[1].message.text == "Hi there.")
        #expect(toolCard(frame.messages[2].blocks)?.detailText == Self.summary)
    }

    // MARK: Wire events

    private func prompt(_ id: String, _ text: String, at: Int64) -> [String] {
        enqueued(id, text, at: at) + delivered(id, at: at + 10)
    }

    private func enqueued(_ id: String, _ text: String, delivery: String = "steer", at: Int64) -> [String] {
        [envelope(
            "session.inbox.enqueued",
            at,
            #""inboxID":"\#(id)","item":{"id":"\#(id)","type":"user","payload":{"text":"\#(text)"},"delivery":"\#(delivery)"}"#
        )]
    }

    private func delivered(_ id: String, at: Int64) -> [String] {
        [envelope("session.inbox.delivered", at, #""inboxID":"\#(id)""#)]
    }

    private static let scripted = #"{"id":"scripted","providerID":"sim"}"#
    private static let scriptedHigh = #"{"id":"scripted","providerID":"sim","variant":"high"}"#

    private func step(_ id: String, at: Int64, model: String = #"{"id":"m","providerID":"p"}"#) -> [String] {
        [envelope(
            "session.step.started",
            at,
            #""agent":"build","model":\#(model),"assistantMessageID":"\#(id)""#
        )]
    }

    private func agentSelected(_ agent: String, at: Int64) -> [String] {
        [envelope("session.agent.selected", at, #""agent":"\#(agent)""#)]
    }

    private func modelSelected(_ model: String, at: Int64) -> [String] {
        [envelope("session.model.selected", at, #""model":\#(model)"#)]
    }

    private func metaLine(_ message: Message) -> String? {
        guard message.mode != nil || message.model != nil else { return nil }
        return "\(message.mode ?? "nil")|\(message.model ?? "nil")|\(message.variant ?? "nil")"
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

    private func inputDelta(_ fields: String, _ delta: String, at: Int64) -> String {
        envelope("session.tool.input.delta", at, "\(fields),\"delta\":\(jsonString(delta))")
    }

    private func liveTool(_ messageID: String) -> ToolPart? {
        store.messages(sessionID: Self.session).first { $0.id == messageID }?.parts.compactMap { part -> ToolPart? in
            if case .tool(let tool) = part { return tool }
            return nil
        }.first
    }

    private func liveToolCard(_ messageID: String, callID: String? = nil) -> SessionChatToolCardBlock? {
        guard let message = store.messages(sessionID: Self.session).first(where: { $0.id == messageID }) else { return nil }
        let result = SessionChatTimelineBlocks.build(role: .assistant, parts: message.parts)
        guard case .ok(let blocks) = result else { return nil }
        return blocks.compactMap { block -> SessionChatToolCardBlock? in
            guard case .toolCard(let blockID, let card) = block else { return nil }
            return callID == nil || blockID == PresentationBlockId.direct(callID!) ? card : nil
        }.first
    }

    private func toolCards(_ parts: [MessagePart]) -> [SessionChatToolCardBlock] {
        guard case .ok(let blocks) = SessionChatTimelineBlocks.build(role: .assistant, parts: parts) else { return [] }
        return blocks.compactMap { block in
            guard case .toolCard(_, let card) = block else { return nil }
            return card
        }
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
            case EventDomainAdapter.messageContextEvent:
                guard let patch = MessageContextPatch.from(properties) else { continue }
                _ = store.apply(sessionID: Self.session, patch: patch)
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

    private func toolCard(_ blocks: [SessionChatTimelineBlock]) -> SessionChatToolCardBlock? {
        guard blocks.count == 1, case .toolCard(_, let card) = blocks[0] else { return nil }
        return card
    }

    /// Tail reload the way `MessageSync` applies it: REST page plus inbox, merged over local rows.
    private func reload(_ body: String, inbox: String) throws {
        let items = try JSONDecoder().decode([V2InboxItemDTO].self, from: Data(inbox.utf8))
        let tail = adapter.mergeInbox(messages: try restMessages(body), inbox: items)
        let merged = MessageHistoryMerge.applyTail(local: store.messages(sessionID: Self.session), tail: tail)
        #expect(store.mergeAuthoritativeIfRevision(
            sessionID: Self.session,
            messages: merged,
            expectedRevision: store.revision(sessionID: Self.session)
        ))
    }

    private func restMessages(_ body: String) throws -> [Message] {
        let dtos = try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(body.utf8))
        return adapter.messages(fromV2: dtos)
    }
}
