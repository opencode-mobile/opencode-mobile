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
        let sessionID = sessionID(from: properties)

        return OpencodeEvent(type: type, sessionID: sessionID)
    }

    private func sessionID(from properties: [String: Any]?) -> String? {
        guard let properties else { return nil }

        if let id = string(in: properties, key: "sessionID") ?? string(in: properties, key: "sessionId") {
            return id
        }
        if let info = properties["info"] as? [String: Any],
           let id = string(in: info, key: "sessionID") ?? string(in: info, key: "sessionId")
        {
            return id
        }
        if let part = properties["part"] as? [String: Any],
           let id = string(in: part, key: "sessionID") ?? string(in: part, key: "sessionId")
        {
            return id
        }
        return nil
    }

    private func string(in object: [String: Any], key: String) -> String? {
        object[key] as? String
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
