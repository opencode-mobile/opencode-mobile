import Foundation

struct EventDomainAdapter: Sendable {
    func parseEvent(dataLine: String) -> OpencodeEvent? {
        guard let data = dataLine.data(using: .utf8) else { return nil }
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        let payload = json["payload"] as? [String: Any]
        let type = payload?["type"] as? String ?? json["type"] as? String
        guard let type else { return nil }

        let properties = payload?["properties"] as? [String: Any] ?? json["properties"] as? [String: Any]
        let sessionID =
            properties?["sessionID"] as? String
            ?? properties?["sessionId"] as? String

        return OpencodeEvent(type: type, sessionID: sessionID)
    }

    func statusLabel(from status: SessionStatus) -> String? {
        statusLabel(fromRawType: status.raw["type"])
    }

    private func statusLabel(fromRawType rawType: String?) -> String? {
        switch rawType {
        case "busy":
            "Working"
        case "retry":
            "Retrying"
        default:
            nil
        }
    }
}
