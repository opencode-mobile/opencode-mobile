import Foundation

nonisolated struct EventDomainAdapter: Sendable {
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
        let rawProperties = properties.map { $0.mapValues { AnySendable.from($0) ?? .null } }

        return OpencodeEvent(type: type, sessionID: sessionID, rawProperties: rawProperties)
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

    /// Title change carried by a `session.updated` event for a concrete session id.
    /// Returns nil when the event is not a usable title update.
    func sessionTitleUpdate(from event: OpencodeEvent) -> SessionTitleUpdate? {
        guard event.type == "session.updated" else { return nil }
        let info = event.rawProperties?["info"]?.dictionaryValue
        let sessionID =
            event.sessionID
            ?? info?["id"]?.stringValue
            ?? info?["sessionID"]?.stringValue
            ?? info?["sessionId"]?.stringValue
        guard let sessionID else { return nil }
        guard let title = info?["title"]?.stringValue else { return nil }
        return SessionTitleUpdate(sessionID: sessionID, title: title)
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

nonisolated struct SessionTitleUpdate: Equatable, Sendable {
    let sessionID: String
    let title: String
}
