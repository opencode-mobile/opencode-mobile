import Foundation
import Testing
@testable import OpenCode_Mobile

/// v2 notice items (moved, agent, model, instructions, restart): REST projection,
/// the live event projection, and the row copy.
@MainActor
struct V2TimelineNoticeTests {
    private let adapter = MessageDomainAdapter()
    private let events = EventDomainAdapter()

    // MARK: REST projection

    @Test func rest_projectsEveryNoticeKindInOrder() throws {
        let messages = try project(
            """
            [
              {"id":"msg_moved","type":"location-switched","location":{"directory":"/repo/wt"},
               "previous":{"location":{"directory":"/repo"}},"time":{"created":1}},
              {"id":"msg_agent","type":"agent-switched","agent":"plan","previous":"build","time":{"created":2}},
              {"id":"msg_model","type":"model-switched","model":{"id":"gpt-5","providerID":"openai"},
               "previous":{"id":"sonnet","providerID":"anthropic"},"time":{"created":3}},
              {"id":"msg_instr","type":"system","text":"model-facing update","description":"Instructions updated: AGENTS.md",
               "metadata":{"notice":"instructions","instructionSources":["AGENTS.md","docs/AGENTS.md"]},"time":{"created":4}},
              {"id":"msg_restart","type":"synthetic","text":"continue","description":"Continuing after restart",
               "metadata":{"notice":"restart"},"time":{"created":5}}
            ]
            """
        )

        #expect(messages.map(\.id) == ["msg_moved", "msg_agent", "msg_model", "msg_instr", "msg_restart"])
        #expect(messages.allSatisfy { $0.role == .notice })
        #expect(messages.map(\.sentAtEpochMs) == [1, 2, 3, 4, 5])
        #expect(messages.map(notice) == [
            TimelineNotice(id: "msg_moved", kind: .moved(directory: "/repo/wt")),
            TimelineNotice(id: "msg_agent", kind: .agentChanged(agent: "plan", previous: "build")),
            TimelineNotice(
                id: "msg_model",
                kind: .modelChanged(
                    model: TimelineNoticeModel(providerID: "openai", modelID: "gpt-5"),
                    previous: TimelineNoticeModel(providerID: "anthropic", modelID: "sonnet")
                )
            ),
            TimelineNotice(id: "msg_instr", kind: .instructionsUpdated(sources: ["AGENTS.md", "docs/AGENTS.md"])),
            TimelineNotice(id: "msg_restart", kind: .restart),
        ])
    }

    @Test func rest_noticesCarryNoAgentModelTextOrContext() throws {
        let messages = try project(
            """
            [
              {"id":"n1","type":"agent-switched","agent":"build","time":{"created":1}},
              {"id":"n2","type":"model-switched","model":{"id":"gpt-5","providerID":"openai"},"time":{"created":2}},
              {"id":"n3","type":"system","text":"a very long model-facing text","time":{"created":3}}
            ]
            """
        )
        for message in messages {
            #expect(message.mode == nil)
            #expect(message.model == nil)
            #expect(message.text.isEmpty)
            #expect(message.contextEstimate == MessageContextEstimate())
            #expect(message.contextInputTokens == nil)
        }
    }

    @Test func rest_switchItemsStillCarryAgentAndModelToTheNextPrompt() throws {
        let messages = try project(
            """
            [
              {"id":"n1","type":"agent-switched","agent":"plan","time":{"created":1}},
              {"id":"n2","type":"model-switched","model":{"id":"gpt-5","providerID":"openai","variant":"high"},"time":{"created":2}},
              {"id":"u1","type":"user","text":"Hi","time":{"created":3}}
            ]
            """
        )
        let user = try #require(messages.last)
        #expect(user.role == .user)
        #expect(user.mode == "plan")
        #expect(user.model == "gpt-5")
        #expect(user.variant == "high")
    }

    @Test func rest_agentWithoutPreviousOrWithTheSamePrevious_hasNoPrevious() throws {
        let messages = try project(
            """
            [
              {"id":"a1","type":"agent-switched","agent":"build","time":{"created":1}},
              {"id":"a2","type":"agent-switched","agent":"build","previous":"build","time":{"created":2}}
            ]
            """
        )
        #expect(messages.map(notice).map(\.?.kind) == [
            .agentChanged(agent: "build", previous: nil),
            .agentChanged(agent: "build", previous: nil),
        ])
    }

    @Test func rest_instructionsFromDescriptionWhenMetadataHasNoSources() throws {
        let messages = try project(
            """
            [
              {"id":"s1","type":"system","text":"x","description":"Instructions updated: AGENTS.md, CLAUDE.md","time":{"created":1}},
              {"id":"s2","type":"system","text":"x","description":"Skills reloaded","time":{"created":2}},
              {"id":"s3","type":"system","text":"model-facing only","time":{"created":3}}
            ]
            """
        )
        #expect(messages.map(notice).map(\.?.kind) == [
            .instructionsUpdated(sources: ["AGENTS.md", "CLAUDE.md"]),
            .systemNote("Skills reloaded"),
            .instructionsUpdated(sources: []),
        ])
    }

    @Test func rest_restartSyntheticIsANotice_otherSyntheticStaysAsBefore() throws {
        let messages = try project(
            """
            [
              {"id":"r1","type":"synthetic","text":"continue","description":"Continuing after restart","time":{"created":1}},
              {"id":"r2","type":"synthetic","text":"continue","metadata":{"notice":"restart"},"time":{"created":2}},
              {"id":"x1","type":"synthetic","text":"Subagent finished","description":"Explore finished",
               "metadata":{"source":"subagent"},"time":{"created":3}}
            ]
            """
        )
        #expect(messages.map(\.role) == [.notice, .notice, .user])
        #expect(notice(messages[0])?.kind == .restart)
        #expect(notice(messages[1])?.kind == .restart)
        guard case .text(let text) = messages[2].parts.first else {
            Issue.record("expected the synthetic text part")
            return
        }
        #expect(text.isSynthetic)
        #expect(text.text == "Explore finished")
    }

    @Test func rest_switchWithoutItsValueDrawsNoRow() throws {
        let messages = try project(
            """
            [
              {"id":"n1","type":"location-switched","time":{"created":1}},
              {"id":"n2","type":"agent-switched","time":{"created":2}},
              {"id":"n3","type":"model-switched","time":{"created":3}}
            ]
            """
        )
        #expect(messages.isEmpty)
    }

    // MARK: Copy

    @Test func copy_movedLeadsWithTheGlyphVariantAndTheDirectory() {
        let row = present(.moved(directory: "/Users/me/repo"))
        #expect(row == TimelineNoticePresentation(isMove: true, label: "Moved to", value: "/Users/me/repo"))
    }

    @Test func copy_agentChangedTitleCasesAndShowsPreviousOnlyWhenKnown() {
        #expect(present(.agentChanged(agent: "plan", previous: "build")).value == "Build \u{2192} Plan")
        #expect(present(.agentChanged(agent: "plan", previous: nil)).value == "Plan")
        #expect(present(.agentChanged(agent: "plan", previous: nil)).label == "Agent changed")
    }

    @Test func copy_modelChangeUsesTheCatalogNameOrFallsBackToTheID() {
        let model = TimelineNoticeModel(providerID: "anthropic", modelID: "claude-opus-4-5")
        let names = TimelineNoticeModelNames(options: [
            ModelSelectionOption(
                providerID: "anthropic",
                providerName: "Anthropic",
                modelID: "claude-opus-4-5",
                modelName: "Claude Opus 4.5",
                variants: []
            ),
        ])
        let named = TimelineNoticeCopy.presentation(
            for: TimelineNotice(id: "n", kind: .modelChanged(model: model, previous: nil)),
            modelNames: names
        )
        #expect(named == TimelineNoticePresentation(isMove: false, label: "Switched to", value: "Claude Opus 4.5"))
        #expect(present(.modelChanged(model: model, previous: nil)).value == "claude-opus-4-5")
        let otherModel = TimelineNoticeModel(providerID: "openai", modelID: "gpt-5", variant: "high")
        #expect(present(.modelChanged(model: otherModel, previous: model)).label == "Switched to")
    }

    @Test func copy_variantOnlyChange() {
        let base = TimelineNoticeModel(providerID: "openai", modelID: "gpt-5", variant: "low")
        let high = TimelineNoticeModel(providerID: "openai", modelID: "gpt-5", variant: "high")
        let none = TimelineNoticeModel(providerID: "openai", modelID: "gpt-5")
        let explicitDefault = TimelineNoticeModel(providerID: "openai", modelID: "gpt-5", variant: "default")
        #expect(present(.modelChanged(model: high, previous: base))
            == TimelineNoticePresentation(isMove: false, label: "Switched variant to", value: "high"))
        #expect(present(.modelChanged(model: none, previous: base))
            == TimelineNoticePresentation(isMove: false, label: "Switched variant to Default", value: nil))
        #expect(present(.modelChanged(model: explicitDefault, previous: base)).label == "Switched variant to Default")
    }

    @Test func copy_instructionsSystemAndRestart() {
        #expect(present(.instructionsUpdated(sources: ["AGENTS.md", "CLAUDE.md"]))
            == TimelineNoticePresentation(isMove: false, label: "Instructions updated", value: "AGENTS.md, CLAUDE.md"))
        #expect(present(.instructionsUpdated(sources: []))
            == TimelineNoticePresentation(isMove: false, label: "Instructions updated", value: nil))
        #expect(present(.systemNote("Skills reloaded"))
            == TimelineNoticePresentation(isMove: false, label: "Skills reloaded", value: nil))
        #expect(present(.restart)
            == TimelineNoticePresentation(isMove: false, label: "Continuing after restart", value: nil))
    }

    // MARK: Live projection

    @Test func live_switchEventsKeepTheirSessionUpdateAndContextAndAddANotice() {
        let parsed = events.parseEvents(
            dataLine: #"{"id":"evt_abc","created":42,"type":"session.agent.selected","data":{"sessionID":"ses-1","agent":"plan","previous":"build"}}"#
        )
        #expect(parsed.map(\.type) == [
            "session.updated",
            EventDomainAdapter.messageContextEvent,
            "message.updated",
            "message.part.updated",
        ])
        let info = parsed[2].rawProperties?["info"]?.dictionaryValue
        #expect(info?["id"]?.stringValue == "msg_abc")
        #expect(info?["role"]?.stringValue == "notice")
        #expect(info?["time"]?.dictionaryValue?["created"]?.int64Value == 42)
        #expect(liveNotice(parsed) == TimelineNotice(id: "msg_abc", kind: .agentChanged(agent: "plan", previous: "build")))
    }

    @Test func live_everyNoticeEventProjectsWithTheEnvelopeDerivedID() {
        let cases: [(line: String, kind: TimelineNoticeKind)] = [
            (
                #"{"id":"evt_m1","type":"session.moved","data":{"sessionID":"ses-1","location":{"directory":"/repo/wt"},"projectID":"p"}}"#,
                .moved(directory: "/repo/wt")
            ),
            (
                #"{"id":"evt_m2","type":"session.next.agent.switched","data":{"sessionID":"ses-1","agent":"build"}}"#,
                .agentChanged(agent: "build", previous: nil)
            ),
            (
                #"{"id":"evt_m3","type":"session.model.selected","data":{"sessionID":"ses-1","model":{"id":"gpt-5","providerID":"openai","variant":"high"}}}"#,
                .modelChanged(model: TimelineNoticeModel(providerID: "openai", modelID: "gpt-5", variant: "high"), previous: nil)
            ),
            (
                #"{"id":"evt_m4","type":"session.instructions.updated","data":{"sessionID":"ses-1","text":"rendered","delta":{"CLAUDE.md":"h2","AGENTS.md":"h1"}}}"#,
                .instructionsUpdated(sources: ["AGENTS.md", "CLAUDE.md"])
            ),
            (
                #"{"id":"evt_m5","type":"session.synthetic","data":{"sessionID":"ses-1","text":"go on","description":"Continuing after restart","metadata":{"notice":"restart"}}}"#,
                .restart
            ),
        ]
        for (index, entry) in cases.enumerated() {
            let notice = liveNotice(events.parseEvents(dataLine: entry.line))
            #expect(notice == TimelineNotice(id: "msg_m\(index + 1)", kind: entry.kind))
        }
    }

    @Test func live_baselineInstructionsAndOtherSyntheticDrawNothing() {
        let baseline = events.parseEvents(
            dataLine: #"{"id":"evt_i0","type":"session.instructions.updated","data":{"sessionID":"ses-1","delta":{"AGENTS.md":"h1"}}}"#
        )
        #expect(liveNotice(baseline) == nil)
        let synthetic = events.parseEvents(
            dataLine: #"{"id":"evt_s0","type":"session.synthetic","data":{"sessionID":"ses-1","text":"x","description":"Explore finished","metadata":{"source":"subagent"}}}"#
        )
        #expect(liveNotice(synthetic) == nil)
    }

    @Test func live_noticeWithoutAnEnvelopeIDWaitsForREST() {
        let parsed = events.parseEvents(
            dataLine: #"{"type":"session.agent.selected","data":{"sessionID":"ses-1","agent":"plan"}}"#
        )
        #expect(liveNotice(parsed) == nil)
        #expect(parsed.map(\.type).contains("session.updated"))
    }

    // MARK: Helpers

    private func project(_ json: String) throws -> [Message] {
        adapter.messages(fromV2: try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(json.utf8)))
    }

    private func notice(_ message: Message) -> TimelineNotice? {
        guard message.parts.count == 1, case .notice(let notice) = message.parts[0] else { return nil }
        return notice
    }

    private func present(_ kind: TimelineNoticeKind) -> TimelineNoticePresentation {
        TimelineNoticeCopy.presentation(for: TimelineNotice(id: "n", kind: kind))
    }

    /// The notice the store would parse from the projected `message.part.updated`.
    private func liveNotice(_ parsed: [OpencodeEvent]) -> TimelineNotice? {
        guard let part = parsed.first(where: { $0.type == "message.part.updated" })?.rawProperties?["part"],
              let data = try? JSONSerialization.data(withJSONObject: json(part)),
              let dto = try? JSONDecoder().decode(MessagePartDTO.self, from: data),
              case .notice(let notice)? = adapter.messagePart(from: dto)
        else { return nil }
        return notice
    }

    private func json(_ value: AnySendable) -> Any {
        switch value {
        case .string(let v): v
        case .int(let v): v
        case .int64(let v): v
        case .double(let v): v
        case .bool(let v): v
        case .dictionary(let dict): dict.mapValues { json($0) }
        case .array(let array): array.map { json($0) }
        case .null: NSNull()
        }
    }
}
