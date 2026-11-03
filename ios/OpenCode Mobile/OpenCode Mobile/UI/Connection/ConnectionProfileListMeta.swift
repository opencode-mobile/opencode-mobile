import Foundation

enum ConnectionProfileListMeta {
    static func caption(_ profile: ConnectionProfile, isActive: Bool) -> String? {
        guard isActive else { return nil }
        return profile.serverProtocol?.apiCaption
    }

    static func status(_ profile: ConnectionProfile, isActive: Bool) -> OpenCodeListItemStatus? {
        if isActive {
            return OpenCodeListItemStatus(text: "servers.status_active", tone: .interactive)
        }
        if let serverProtocol = profile.serverProtocol {
            return OpenCodeListItemStatus(text: .verbatim(serverProtocol.shortLabel), tone: .info)
        }
        return nil
    }
}
