import Foundation

struct DuplicateConnectionProfileException: Error, Equatable {
    let conflictingId: String
}

struct ConnectionProfileCatalog: Equatable, Sendable {
    var profiles: [ConnectionProfile] = []
    var activeId: String?

    func activeProfile() -> ConnectionProfile? {
        profiles.first { $0.id == activeId }
    }

    func save(_ profile: ConnectionProfile) throws -> ConnectionProfileCatalog {
        let normalizedURL = ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL)
        let username = ConnectionProfileIdentity.normalizeUsername(profile.username)
        let id = profile.id.isEmpty ? UUID().uuidString : profile.id
        var stored = profile
        stored.id = id
        stored.baseURL = normalizedURL
        stored.username = username
        stored.name = profile.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let conflict = profiles.first(where: { existing in
            existing.id != id
                && ConnectionProfileIdentity.sameConnectionIdentity(
                    leftURL: existing.baseURL,
                    leftUsername: existing.username,
                    rightURL: stored.baseURL,
                    rightUsername: stored.username
                )
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

    func apply(id: String) -> ConnectionProfileCatalog {
        guard profiles.contains(where: { $0.id == id }) else { return self }
        var next = self
        next.activeId = id
        return next
    }

    func remove(id: String) -> ConnectionProfileCatalog {
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
}
