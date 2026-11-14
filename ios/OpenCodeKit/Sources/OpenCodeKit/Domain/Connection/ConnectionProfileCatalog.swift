import Foundation

public struct DuplicateConnectionProfileException: Error, Equatable {
    public let conflictingId: String

    public init(
        conflictingId: String
    ) {
        self.conflictingId = conflictingId
    }
}

public struct ConnectionProfileCatalog: Equatable, Sendable {
    public var profiles: [ConnectionProfile] = []
    public var activeId: String?

    public func activeProfile() -> ConnectionProfile? {
        profiles.first { $0.id == activeId }
    }

    public func save(_ profile: ConnectionProfile) throws -> ConnectionProfileCatalog {
        let normalizedURL = ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL)
        let username = ConnectionProfileIdentity.normalizeUsername(profile.username)
        let id = profile.id.isEmpty ? UUID().uuidString : profile.id
        var stored = profile
        stored.id = id
        stored.baseURL = normalizedURL
        stored.serverURLs = ConnectionProfileIdentity.normalizedAddressList(profile.serverURLs)
        stored.username = username
        stored.name = profile.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let conflict = profiles.first(where: { existing in
            existing.id != id && ConnectionProfileIdentity.sameSavedServer(existing, stored)
        }) {
            throw DuplicateConnectionProfileException(conflictingId: conflict.id)
        }
        var next = self
        if let existingIndex = profiles.firstIndex(where: { $0.id == id }) {
            next.profiles[existingIndex] = stored
            if next.activeId == nil {
                next.activeId = id
            }
        } else {
            next.profiles.append(stored)
            next.activeId = id
        }
        return next
    }

    public func apply(id: String) -> ConnectionProfileCatalog {
        guard profiles.contains(where: { $0.id == id }) else { return self }
        var next = self
        next.activeId = id
        return next
    }

    public func remove(id: String) -> ConnectionProfileCatalog {
        guard let index = profiles.firstIndex(where: { $0.id == id }) else { return self }
        var next = self
        next.profiles.removeAll { $0.id == id }
        if next.profiles.isEmpty {
            next.activeId = nil
        } else if activeId == id {
            next.activeId = next.profiles[min(index, next.profiles.count - 1)].id
        }
        return next
    }

    public init(
        profiles: [ConnectionProfile] = [],
        activeId: String? = nil
    ) {
        self.profiles = profiles
        self.activeId = activeId
    }
}
