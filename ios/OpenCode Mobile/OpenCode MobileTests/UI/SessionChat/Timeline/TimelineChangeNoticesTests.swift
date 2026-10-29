import Testing
@testable import OpenCode_Mobile

struct TimelineChangeNoticesTests {
    private func user(_ id: String, agent: String?, model: String?, provider: String? = "opencode", variant: String? = nil, at: Int64) -> DisplayMessage {
        DisplayMessage(message: Message(
            id: id,
            role: .user,
            text: id,
            parts: [.text(TextPart(id: "\(id):text", text: id, isStreaming: false))],
            model: model,
            modelProviderID: provider,
            modelID: model,
            variant: variant,
            mode: agent,
            sentAtEpochMs: at
        ))
    }

    private func assistant(_ id: String, at: Int64) -> DisplayMessage {
        DisplayMessage(message: Message(id: id, role: .assistant, text: "ok", model: "m", mode: "build", sentAtEpochMs: at))
    }

    private func kinds(_ messages: [DisplayMessage]) -> [String] {
        messages.map { display in
            guard case .notice(let notice)? = display.message.parts.first else { return display.message.id }
            switch notice.kind {
            case let .agentChanged(agent, previous): return "agent:\(previous ?? "-")>\(agent)"
            case let .modelChanged(model, previous):
                return "model:\(previous.map { "\($0.modelID)/\($0.variant ?? "-")" } ?? "-")>\(model.modelID)/\(model.variant ?? "-")"
            default: return "notice"
            }
        }
    }

    @Test func derivesEachChangeButNotTheFirstAgentAndModel() {
        let result = TimelineChangeNotices.withDerivedNotices([
            user("u1", agent: "build", model: "fast", at: 1),
            assistant("a1", at: 2),
            user("u2", agent: "build", model: "fast", at: 3),
            assistant("a2", at: 4),
            user("u3", agent: "plan", model: "fast", at: 5),
            user("u4", agent: "plan", model: "smart", at: 6),
            user("u5", agent: "plan", model: "smart", variant: "high", at: 7),
        ])
        #expect(kinds(result) == [
            "u1", "a1",
            "u2", "a2",
            "agent:build>plan", "u3",
            "model:fast/->smart/-", "u4",
            "model:smart/->smart/high", "u5",
        ])
    }

    @Test func derivedNoticesSitBeforeTheirMessageWithItsTime() {
        let result = TimelineChangeNotices.withDerivedNotices([
            user("u1", agent: "build", model: nil, at: 1),
            user("u2", agent: "plan", model: nil, at: 42),
        ])
        #expect(result.count == 3)
        #expect(result[1].message.role == .notice)
        #expect(result[1].message.sentAtEpochMs == 42)
        #expect(result[1].message.id.hasPrefix(TimelineChangeNotices.derivedIDPrefix))
    }

    @Test func serverNoticesOwnTheSession() {
        let serverNotice = DisplayMessage(message: Message(
            id: "n1",
            role: .notice,
            text: "",
            parts: [.notice(TimelineNotice(id: "n1", kind: .agentChanged(agent: "build", previous: nil)))],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1
        ))
        let input = [serverNotice, user("u1", agent: "build", model: "fast", at: 2), user("u2", agent: "plan", model: "fast", at: 3)]
        #expect(kinds(TimelineChangeNotices.withDerivedNotices(input)) == ["agent:->build", "u1", "u2"])
    }

    @Test func messagesWithoutAgentOrModelDeriveNothing() {
        let result = TimelineChangeNotices.withDerivedNotices([user("u1", agent: nil, model: nil, at: 1), assistant("a1", at: 2)])
        #expect(kinds(result) == ["u1", "a1"])
    }
}
