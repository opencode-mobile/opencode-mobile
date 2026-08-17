import Foundation

enum ConnectionProfileListMeta {
    static func caption(_ profile: ConnectionProfile, isActive: Bool) -> String? {
        guard isActive else { return nil }
        return profile.serverProtocol?.apiCaption
    }

    static func status(_ profile: ConnectionProfile, isActive: Bool) -> OpenCodeListItemStatus? {
        if isActive {
            return OpenCodeListItemStatus(text: "Active", tone: .interactive)
        }
        if let serverProtocol = profile.serverProtocol {
            return OpenCodeListItemStatus(text: serverProtocol.shortLabel, tone: .info)
        }
        return nil
    }
}
