import Foundation

enum ServerAddressProbe {
    /// Probes every address and waits until each has succeeded or failed.
    static func probe(
        urls: [String],
        username: String,
        password: String,
        probing: any ConnectionProbing
    ) async -> [(String, Result<ServerProtocol, Error>)] {
        let unique = ConnectionProfileIdentity.normalizedAddressList(urls)
        return await withTaskGroup(of: (String, Result<ServerProtocol, Error>).self) { group in
            for url in unique {
                group.addTask {
                    let connection = ServerConnection(
                        profile: ConnectionProfile(
                            baseURL: url,
                            username: username,
                            password: password
                        )
                    )
                    do {
                        let detected = try await probing.probe(connection: connection)
                        return (url, .success(detected))
                    } catch {
                        return (url, .failure(error))
                    }
                }
            }
            var collected: [(String, Result<ServerProtocol, Error>)] = []
            for await outcome in group {
                collected.append(outcome)
            }
            return collected
        }
    }

    static func hostAnswered(_ result: Result<ServerProtocol, Error>) -> Bool {
        switch result {
        case .success:
            return true
        case let .failure(error as ServerProbeError):
            return error.probeStatus == .authFailed
        case .failure:
            return false
        }
    }
}
