import Foundation

/// Picks one address out of a profile's saved list without spraying the password.
///
/// 1. Every candidate gets one health request **without credentials**.
/// 2. One candidate is chosen among those that answered (Tailscale first, never an
///    `http` address when the current base URL is `https`).
/// 3. Only the chosen host receives the password. A `401` there is an authentication
///    failure, not a reachable server; no other host is tried afterwards.
///
/// Both server protocols keep their health endpoints behind Basic auth, so a live
/// opencode answers the unauthenticated request with `401`. That makes the host a
/// *candidate*; it never makes it *reachable*.
enum ServerAddressProbe {
    /// How a candidate answered the unauthenticated health request.
    enum Answer: Equatable, Sendable {
        /// Health answered without credentials (server running without a password).
        case open(ServerProtocol)
        /// The host demanded credentials.
        case challenged
        /// Transport failure, non-auth HTTP error, or an unhealthy server.
        case silent

        var answered: Bool { self != .silent }
    }

    struct Resolution: Sendable {
        /// Unauthenticated answer per normalized candidate, in candidate order.
        let answers: [(url: String, answer: Answer)]
        /// The single host that received credentials. `nil` when nothing answered.
        let chosen: String?
        /// Authenticated outcome for `chosen`. With nothing chosen it is `nil`, except for a
        /// single silent candidate, whose failure is kept for the error message.
        let outcome: Result<ServerProtocol, Error>?

        var answered: [String] {
            answers.compactMap { $0.answer.answered ? $0.url : nil }
        }

        /// Normalized candidates that did not answer at all.
        var unreachable: Set<String> {
            Set(answers.compactMap { $0.answer.answered ? nil : ConnectionProfileIdentity.normalizeBaseURL($0.url) })
        }

        var reachableProtocol: ServerProtocol? {
            if case let .success(detected)? = outcome { return detected }
            return nil
        }

        var failure: ServerProbeError? {
            guard case let .failure(error)? = outcome else { return nil }
            return (error as? ServerProbeError) ?? .transport("Unable to reach the server")
        }

        var nothingAnswered: Bool { chosen == nil }
    }

    /// Full resolution: unauthenticated sweep, one choice, credentials to that host only.
    static func resolve(
        urls: [String],
        username: String,
        password: String,
        currentBaseURL: String?,
        probing: any ConnectionProbing
    ) async -> Resolution {
        let unique = ConnectionProfileIdentity.normalizedAddressList(urls)
        if unique.count == 1, let only = unique.first {
            // One candidate is the chosen host by definition: a single request, with credentials.
            // The sweep exists to pick among several hosts, not to add a round trip here.
            guard PairingQRPayload.chooseAnsweredURL(
                candidates: unique,
                answered: unique,
                currentBaseURL: currentBaseURL
            ) != nil else {
                return Resolution(answers: [], chosen: nil, outcome: nil)
            }
            let outcome = await probeAuthenticated(
                url: only,
                username: username,
                password: password,
                probing: probing
            )
            let answer = answer(from: outcome)
            // A host that never answered is not chosen, exactly as in the multi-candidate sweep;
            // its failure still travels so the caller can show why.
            return Resolution(
                answers: [(url: only, answer: answer)],
                chosen: answer.answered ? only : nil,
                outcome: outcome
            )
        }
        let answers = await probeUnauthenticated(urls: urls, username: username, probing: probing)
        let answered = answers.compactMap { $0.answer.answered ? $0.url : nil }
        guard let chosen = PairingQRPayload.chooseAnsweredURL(
            candidates: answers.map(\.url),
            answered: answered,
            currentBaseURL: currentBaseURL
        ) else {
            return Resolution(answers: answers, chosen: nil, outcome: nil)
        }
        let unauthenticated = answers.first { $0.url == chosen }?.answer ?? .silent
        if password.isEmpty {
            // Nothing more to send: the unauthenticated request already was the real one.
            switch unauthenticated {
            case let .open(detected):
                return Resolution(answers: answers, chosen: chosen, outcome: .success(detected))
            case .challenged, .silent:
                return Resolution(
                    answers: answers,
                    chosen: chosen,
                    outcome: .failure(ServerProbeError.http(statusCode: 401, message: "Unauthorized"))
                )
            }
        }
        let outcome = await probeAuthenticated(
            url: chosen,
            username: username,
            password: password,
            probing: probing
        )
        return Resolution(answers: answers, chosen: chosen, outcome: outcome)
    }

    /// Health request to every candidate with no `Authorization` header.
    static func probeUnauthenticated(
        urls: [String],
        username: String,
        probing: any ConnectionProbing
    ) async -> [(url: String, answer: Answer)] {
        let unique = ConnectionProfileIdentity.normalizedAddressList(urls)
        let collected: [(String, Answer)] = await withTaskGroup(of: (String, Answer).self) { group in
            for url in unique {
                group.addTask {
                    let connection = ServerConnection(
                        profile: ConnectionProfile(baseURL: url, username: username, password: "")
                    )
                    do {
                        return (url, .open(try await probing.probe(connection: connection)))
                    } catch let error as ServerProbeError where error.probeStatus == .authFailed {
                        return (url, .challenged)
                    } catch {
                        return (url, .silent)
                    }
                }
            }
            var collected: [(String, Answer)] = []
            for await outcome in group {
                collected.append(outcome)
            }
            return collected
        }
        // Candidate order matters for the tie-break in `chooseAnsweredURL`.
        return unique.compactMap { url in
            collected.first { $0.0 == url }.map { (url: url, answer: $0.1) }
        }
    }

    private static func answer(from outcome: Result<ServerProtocol, Error>) -> Answer {
        switch outcome {
        case let .success(detected):
            return .open(detected)
        case let .failure(error as ServerProbeError) where error.probeStatus == .authFailed:
            return .challenged
        case .failure:
            return .silent
        }
    }

    /// Authenticated health request to exactly one host.
    static func probeAuthenticated(
        url: String,
        username: String,
        password: String,
        probing: any ConnectionProbing
    ) async -> Result<ServerProtocol, Error> {
        let connection = ServerConnection(
            profile: ConnectionProfile(baseURL: url, username: username, password: password)
        )
        do {
            return .success(try await probing.probe(connection: connection))
        } catch {
            return .failure(error)
        }
    }
}
