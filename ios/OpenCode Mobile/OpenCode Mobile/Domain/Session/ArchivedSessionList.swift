import Foundation

/// When a session was archived, as the Archived sessions list groups it.
nonisolated enum ArchivedSessionPeriod: CaseIterable, Sendable {
    /// Archived in the last seven days.
    case thisWeek
    case earlier
}

/// Archived root sessions across Home's directories. Each directory is listed on its own, the way
/// Home lists its paths; Search's "Include archived" can reuse this.
nonisolated enum ArchivedSessionList {
    private static let weekMs: Int64 = 7 * 24 * 60 * 60 * 1000

    /// Archived root sessions of `directories`, one per id, most recently archived first. A
    /// directory that fails is skipped while another answers; when none answers, the first error
    /// is thrown.
    static func load(repository: any SessionRepository, directories: [String]) async throws -> [Session] {
        var seen = Set<String>()
        let unique = directories.filter { seen.insert($0).inserted }
        let results = await withTaskGroup(of: (Int, Result<[Session], Error>).self) { group in
            for (index, directory) in unique.enumerated() {
                group.addTask {
                    do {
                        return (index, .success(try await repository.listArchivedSessions(directory: directory)))
                    } catch {
                        return (index, .failure(error))
                    }
                }
            }
            var collected: [(Int, Result<[Session], Error>)] = []
            for await result in group { collected.append(result) }
            return collected.sorted { $0.0 < $1.0 }.map(\.1)
        }
        if !results.isEmpty, results.allSatisfy({ if case .failure = $0 { true } else { false } }),
           case let .failure(error) = results[0] {
            throw error
        }
        return sorted(results.flatMap { (try? $0.get()) ?? [] })
    }

    /// Root sessions that are archived, one per id, most recently archived first.
    static func sorted(_ sessions: [Session]) -> [Session] {
        var seen = Set<String>()
        return sessions
            .filter { $0.isArchived && $0.parentID == nil && seen.insert($0.id).inserted }
            .sorted { lhs, rhs in
                let left = lhs.archivedAtEpochMs ?? 0
                let right = rhs.archivedAtEpochMs ?? 0
                return left != right ? left > right : lhs.id < rhs.id
            }
    }

    static func period(archivedAtEpochMs: Int64, nowEpochMs: Int64) -> ArchivedSessionPeriod {
        nowEpochMs - archivedAtEpochMs < weekMs ? .thisWeek : .earlier
    }
}
