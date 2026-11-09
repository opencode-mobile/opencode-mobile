import Foundation
import Testing
@testable import OpenCode_Mobile

/// The `/goal` kickoff in the transcript: a described synthetic message is a Goal set notice
/// (REST and live), continuations stay hidden, and a kickoff with attachments stays the
/// user's prompt.
struct GoalKickoffProjectionTests {
    private static let session = "ses_goal"
    private static let kickoffText = "A goal is now active for this session.\n\n<untrusted_objective>\nMake the tests pass\n</untrusted_objective>\n\nStart now."
    private let events = EventDomainAdapter()
    private let adapter = MessageDomainAdapter()
    private let store = InMemoryMessageStore()

    // MARK: REST

    @Test func rest_describedKickoffIsAGoalSetNotice() throws {
        let messages = try project(
            """
            [
              {"id":"u1","type":"user","text":"Hi","time":{"created":1}},
              {"id":"msg_kick","type":"synthetic","text":\(json(Self.kickoffText)),"description":"Goal: Make the tests pass",
               "metadata":{"source":"goal","goalID":"goal_1"},"time":{"created":2}}
            ]
            """
        )
        let kickoff = try #require(messages.last)
        #expect(kickoff.role == .notice)
        #expect(kickoff.parts == [.notice(TimelineNotice(id: "msg_kick", kind: .goalSet(goalID: "goal_1", description: "Goal: Make the tests pass")))])
        #expect(kickoff.text.isEmpty)
    }

    @Test func rest_continuationWithoutDescriptionStaysHidden() throws {
        let messages = try project(
            """
            [
              {"id":"msg_cont","type":"synthetic","text":"Keep going on the goal.",
               "metadata":{"source":"goal","goalID":"goal_1"},"time":{"created":3}}
            ]
            """
        )
        let continuation = try #require(messages.first)
        #expect(continuation.role == .user)
        #expect(!continuation.parts.contains { if case .notice = $0 { true } else { false } })
        #expect(continuation.parts.allSatisfy { part in
            if case .text(let text) = part { return !text.isDisplayable }
            return true
        })
    }

    @Test func rest_kickoffWithAttachmentsStaysTheUsersPrompt() throws {
        let messages = try project(
            """
            [
              {"id":"u_kick","type":"user","text":\(json(Self.kickoffText)),
               "files":[{"uri":"data:image/png;base64,AAAA","mime":"image/png","name":"screen.png"}],
               "metadata":{"source":"goal","goalID":"goal_1"},"time":{"created":2}}
            ]
            """
        )
        let prompt = try #require(messages.first)
        #expect(prompt.role == .user)
        #expect(prompt.parts.contains { if case .file = $0 { true } else { false } })
        // The bubble shows the objective inside the instruction, never the instruction itself.
        #expect(SessionGoalKickoff.objective(fromKickoffText: prompt.text) == "Make the tests pass")
    }

    @Test func copy_goalSetDropsTheDescriptionLead() {
        let presentation = TimelineNoticeCopy.presentation(
            for: TimelineNotice(id: "n", kind: .goalSet(goalID: "g", description: "Goal: Make the tests pass"))
        )
        #expect(presentation.label.resolved == "Goal set")
        #expect(presentation.value?.resolved == "Make the tests pass")
        #expect(TimelineNoticeCopy.goalSetValue("No prefix") == "No prefix")
    }

    @Test func cache_goalSetSurvivesARoundTrip() throws {
        let notice = Message(
            id: "msg_kick",
            role: .notice,
            text: "",
            parts: [.notice(TimelineNotice(id: "msg_kick", kind: .goalSet(goalID: "goal_1", description: "Goal: Ship it")))],
            model: nil,
            mode: nil,
            sentAtEpochMs: 2
        )
        let decoded = try #require(SessionCacheCodec.decodeMessages(try SessionCacheCodec.encodeMessages([notice])))
        #expect(decoded.first?.parts == notice.parts)
    }

    // MARK: Live

    @Test func live_enqueuedKickoffIsANoticeAndDeliveryKeepsIt() throws {
        apply([
            envelope("session.inbox.enqueued", 1_000, #""inboxID":"u1","item":{"id":"u1","type":"user","payload":{"text":"Hi"},"delivery":"steer"}"#),
            envelope("session.inbox.delivered", 1_010, #""inboxID":"u1""#),
            envelope(
                "session.inbox.enqueued",
                1_100,
                #""inboxID":"msg_kick","item":{"id":"msg_kick","type":"synthetic","payload":{"text":\#(json(Self.kickoffText)),"description":"Goal: Make the tests pass","metadata":{"source":"goal","goalID":"goal_1"}},"delivery":"steer"}"#
            ),
            envelope("session.inbox.delivered", 1_110, #""inboxID":"msg_kick""#),
        ])

        let messages = store.messages(sessionID: Self.session)
        #expect(messages.map(\.id) == ["u1", "msg_kick"])
        let kickoff = try #require(messages.last)
        #expect(kickoff.role == .notice)
        #expect(kickoff.parts == [.notice(TimelineNotice(id: "msg_kick", kind: .goalSet(goalID: "goal_1", description: "Goal: Make the tests pass")))])
    }

    @Test func live_otherSyntheticItemsStayHiddenPrompts() {
        apply([
            envelope(
                "session.inbox.enqueued",
                1_100,
                #""inboxID":"msg_cont","item":{"id":"msg_cont","type":"synthetic","payload":{"text":"Keep going.","metadata":{"source":"goal","goalID":"goal_1"}},"delivery":"steer"}"#
            ),
        ])
        let messages = store.messages(sessionID: Self.session)
        #expect(messages.first?.role == .user)
        #expect(!messages.flatMap(\.parts).contains { if case .notice = $0 { true } else { false } })
    }

    @Test func live_directSyntheticWithGoalMetadataIsANotice() {
        let parsed = events.parseEvents(dataLine: envelope(
            "session.synthetic",
            1_500,
            #""text":"kickoff","description":"Goal: Ship it","metadata":{"source":"goal","goalID":"goal_2"}"#
        ))
        #expect(parsed.contains { $0.type == "message.updated" && $0.rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "notice" })
    }

    // MARK: Helpers

    private func project(_ json: String) throws -> [Message] {
        adapter.messages(fromV2: try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(json.utf8)))
    }

    private func json(_ value: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [value])
        return String(String(decoding: data, as: UTF8.self).dropFirst().dropLast())
    }

    private func envelope(_ type: String, _ created: Int64, _ fields: String) -> String {
        let session = #""sessionID":"\#(Self.session)""#
        let data = fields.isEmpty ? session : "\(session),\(fields)"
        return #"{"id":"evt_\#(type)\#(created)","created":\#(created),"type":"\#(type)","data":{\#(data)}}"#
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
            default:
                continue
            }
        }
    }
}
