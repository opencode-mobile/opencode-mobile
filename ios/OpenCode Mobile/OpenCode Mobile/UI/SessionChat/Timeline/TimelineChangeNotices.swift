import Foundation

/// Agent and model change notices for sessions whose server records none (v1).
///
/// v2 stores `agent-switched` / `model-switched` messages and the timeline draws
/// them. v1 only stamps each user message with its agent and model, so the same
/// notices are derived here from consecutive user messages: one before each user
/// message whose agent or model differs from the previous user message's. Like
/// v2, the first agent and model draw no notice. If the frame holds any server
/// agent or model notice the server owns them and nothing is derived.
nonisolated enum TimelineChangeNotices {
    static let derivedIDPrefix = "derived-notice"

    static func withDerivedNotices(_ ordered: [DisplayMessage]) -> [DisplayMessage] {
        guard !ordered.contains(where: { hasServerChangeNotice($0.message) }) else { return ordered }

        var result: [DisplayMessage] = []
        result.reserveCapacity(ordered.count)
        var agent: String?
        var model: TimelineNoticeModel?
        for display in ordered {
            let message = display.message
            if message.role == .user, !isCompaction(message) {
                if let next = nonBlank(message.mode), next != agent {
                    if let agent {
                        result.append(notice(
                            id: "\(derivedIDPrefix):agent:\(message.id)",
                            kind: .agentChanged(agent: next, previous: agent),
                            sentAtEpochMs: message.sentAtEpochMs
                        ))
                    }
                    agent = next
                }
                if let modelID = nonBlank(message.modelID ?? message.model) {
                    let next = TimelineNoticeModel(
                        providerID: nonBlank(message.modelProviderID),
                        modelID: modelID,
                        variant: nonBlank(message.variant)
                    )
                    if next != model {
                        if let model {
                            result.append(notice(
                                id: "\(derivedIDPrefix):model:\(message.id)",
                                kind: .modelChanged(model: next, previous: model),
                                sentAtEpochMs: message.sentAtEpochMs
                            ))
                        }
                        model = next
                    }
                }
            }
            result.append(display)
        }
        return result
    }

    private static func notice(id: String, kind: TimelineNoticeKind, sentAtEpochMs: Int64?) -> DisplayMessage {
        DisplayMessage(
            message: Message(
                id: id,
                role: .notice,
                text: "",
                parts: [.notice(TimelineNotice(id: id, kind: kind))],
                model: nil,
                mode: nil,
                sentAtEpochMs: sentAtEpochMs,
                completedAtEpochMs: sentAtEpochMs
            )
        )
    }

    private static func hasServerChangeNotice(_ message: Message) -> Bool {
        message.parts.contains { part in
            guard case .notice(let notice) = part else { return false }
            switch notice.kind {
            case .agentChanged, .modelChanged: return true
            default: return false
            }
        }
    }

    private static func isCompaction(_ message: Message) -> Bool {
        message.parts.contains { if case .compaction = $0 { return true }; return false }
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }
}
