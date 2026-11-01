import Foundation
import Testing
@testable import OpenCode_Mobile

/// A v2 prompt's skills must survive every copy the store makes of the message,
/// or Session Chat shows raw `/name` text instead of `[/name]` spans.
@Suite struct PromptSkillCarryOverTests {
    private let text = "fix /tdd-check the login flow /security-check"
    private let skills = [
        PromptSkill(id: "tdd-check", name: "tdd-check", mentionText: "/tdd-check", mentionStart: 4, mentionEnd: 14),
        PromptSkill(
            id: "security-check",
            name: "security-check",
            mentionText: "/security-check",
            mentionStart: 30,
            mentionEnd: 45
        ),
    ]

    private func prompt(parts: [MessagePart] = [], model: String? = nil, mode: String? = nil) -> Message {
        Message(
            id: "msg-1",
            role: .user,
            text: text,
            parts: parts,
            model: model,
            mode: mode,
            sentAtEpochMs: 1,
            promptSkills: skills
        )
    }

    @Test func partUpdate_keepsPromptSkills() {
        let engine = SessionMessageEngine(store: InMemoryMessageStore())
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [prompt(parts: [.text(TextPart(id: "prt-1", text: text, isStreaming: false))])]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-1"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string(text),
                ],
                delta: nil
            )
        )

        let rebuilt = engine.message(sessionID: "ses-1", messageID: "msg-1")
        #expect(rebuilt?.promptSkills == skills)
    }

    @Test func withMetadata_fillsPromptSkillsFromBase() {
        let live = Message(id: "msg-1", role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 1)

        #expect(live.withMetadata(from: prompt(), failureOmitted: true).promptSkills == skills)
        #expect(
            live.withMetadata(from: prompt(model: "gpt", mode: "build"), failureOmitted: true).promptSkills == skills
        )
    }

    @Test func withMetadata_keepsOwnPromptSkills() {
        let own = [PromptSkill(id: "tdd-check", name: "tdd-check")]
        let live = Message(
            id: "msg-1",
            role: .user,
            text: text,
            model: "gpt",
            mode: "build",
            sentAtEpochMs: 1,
            promptSkills: own
        )

        #expect(live.withMetadata(from: prompt(), failureOmitted: true).promptSkills == own)
    }

    @Test func stamp_keepsPromptSkills() {
        let stamped = SessionRunningContext().switched(agent: "build", model: nil, atEpochMs: 0).stamp(prompt())

        #expect(stamped.mode == "build")
        #expect(stamped.promptSkills == skills)
    }

    @Test func present_collapsesBothSkillsAfterPartUpdate() {
        let engine = SessionMessageEngine(store: InMemoryMessageStore())
        engine.replaceAll(
            sessionID: "ses-1",
            messages: [prompt(parts: [.text(TextPart(id: "prt-1", text: text, isStreaming: false))])]
        )
        _ = engine.apply(
            sessionID: "ses-1",
            patch: .partUpdated(
                part: [
                    "id": .string("prt-1"),
                    "messageID": .string("msg-1"),
                    "sessionID": .string("ses-1"),
                    "type": .string("text"),
                    "text": .string(text),
                ],
                delta: nil
            )
        )
        guard let rebuilt = engine.message(sessionID: "ses-1", messageID: "msg-1") else {
            Issue.record("message missing")
            return
        }

        let blocks = SessionChatTimelineBlocks.present(message: rebuilt, commands: [])
        #expect(blocks.compactMap { $0.textContent?.text } == ["fix [/tdd-check] the login flow [/security-check]"])
    }

    /// A live v2 prompt carries its skills only on `session.inbox.enqueued`; the later
    /// `session.inbox.delivered` info has none and must not drop them.
    @Test func liveV2Prompt_keepsSkillsThroughDelivery() {
        let engine = SessionMessageEngine(store: InMemoryMessageStore())
        applyProjected(
            #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-1","sessionID":"ses-1","item":{"type":"user","payload":{"text":"fix /tdd-check the login flow /security-check","skills":[{"id":"tdd-check","name":"tdd-check","text":"<skill_content>body</skill_content>","mention":{"start":4,"end":14,"text":"/tdd-check"}},{"id":"security-check","name":"security-check","text":"<skill_content>body</skill_content>","mention":{"start":30,"end":45,"text":"/security-check"}}]}}}}"#,
            engine: engine
        )
        applyProjected(
            #"{"type":"session.inbox.delivered","data":{"inboxID":"msg-1","sessionID":"ses-1"}}"#,
            engine: engine
        )
        guard let message = engine.message(sessionID: "ses-1", messageID: "msg-1") else {
            Issue.record("message missing")
            return
        }

        #expect(message.promptSkills == skills)
        let blocks = SessionChatTimelineBlocks.present(message: message, commands: [])
        #expect(blocks.compactMap { $0.textContent?.text } == ["fix [/tdd-check] the login flow [/security-check]"])
    }

    @Test func enqueuedProjection_keepsSkillMentionsWithoutBodies() {
        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-1","sessionID":"ses-1","item":{"type":"user","payload":{"text":"/tdd-check go","skills":[{"id":"tdd-check","name":"tdd-check","text":"<skill_content>body</skill_content>","mention":{"start":0,"end":10,"text":"/tdd-check"}}]}}}}"#
        )
        let info = events.first?.rawProperties?["info"]?.dictionaryValue
        guard case .array(let projected) = info?["skills"] else {
            Issue.record("info has no skills")
            return
        }
        let skill = projected.first?.dictionaryValue

        #expect(skill?["id"]?.stringValue == "tdd-check")
        #expect(skill?["name"]?.stringValue == "tdd-check")
        #expect(skill?["mention"]?.dictionaryValue?["end"] != nil)
        #expect(skill?["text"] == nil)
    }

    private func applyProjected(_ line: String, engine: SessionMessageEngine) {
        for event in EventDomainAdapter().parseEvents(dataLine: line) {
            guard let properties = event.rawProperties else { continue }
            switch event.type {
            case "message.updated":
                guard let info = properties["info"]?.dictionaryValue else { continue }
                _ = engine.apply(sessionID: "ses-1", patch: .infoUpdated(info))
            case "message.part.updated":
                guard let part = properties["part"]?.dictionaryValue else { continue }
                _ = engine.apply(sessionID: "ses-1", patch: .partUpdated(part: part, delta: nil))
            default:
                break
            }
        }
    }
}
