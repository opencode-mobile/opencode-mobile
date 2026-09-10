import Foundation

struct SseFaultTrace: Decodable, Sendable {
    let id: String
    let title: String?
    let platforms: [String]
    let surface: String
    let serverProtocol: String
    let seed: SseFaultSeed
    let steps: [SseFaultStep]
    let expected: SseFaultExpected

    enum CodingKeys: String, CodingKey {
        case id, title, platforms, surface, seed, steps, expected
        case serverProtocol = "protocol"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        platforms = try container.decodeIfPresent([String].self, forKey: .platforms) ?? []
        surface = try container.decode(String.self, forKey: .surface)
        serverProtocol = try container.decode(String.self, forKey: .serverProtocol)
        seed = try container.decode(SseFaultSeed.self, forKey: .seed)
        steps = try container.decode([SseFaultStep].self, forKey: .steps)
        expected = try container.decode(SseFaultExpected.self, forKey: .expected)
    }
}

struct SseFaultSeed: Decodable, Sendable {
    let profileId: String
    let directory: String
    let sessionId: String
    let directories: [String]
    let messages: [SseFaultTranscriptMessage]
    let connectionState: String?
    let occupancy: String?

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        profileId = try container.decode(String.self, forKey: .profileId)
        directory = try container.decode(String.self, forKey: .directory)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        directories = try container.decodeIfPresent([String].self, forKey: .directories) ?? []
        messages = try container.decodeIfPresent([SseFaultTranscriptMessage].self, forKey: .messages) ?? []
        connectionState = try container.decodeIfPresent(String.self, forKey: .connectionState)
        occupancy = try container.decodeIfPresent(String.self, forKey: .occupancy)
    }

    private enum CodingKeys: String, CodingKey {
        case profileId, directory, sessionId, directories, messages, connectionState, occupancy
    }
}

struct SseFaultStep: Decodable, Sendable {
    let t: Int
    let op: String
    let name: String?
    let state: String?
    let target: String?
    let profileId: String?
    let sessionId: String?
    let directory: String?
    let directories: [String]?
    let messages: [SseFaultTranscriptMessage]?
    let event: SseFaultEvent?
}

struct SseFaultEvent: Decodable, Sendable {
    let type: String
    let sessionId: String?
    let text: String?
    let messageId: String?
    let partId: String?
    let field: String?
}

struct SseFaultExpected: Decodable, Sendable {
    let snapshots: [String: SseFaultSnapshot]
}

struct SseFaultSnapshot: Decodable, Sendable {
    let syncHealth: String?
    let syncHealthReason: String?
    let catchUpChrome: Bool?
    let chrome: String?
    let connectionState: String?
    let occupancy: String?
    let generationUnchanged: Bool?
    let gapReason: String?
    let restFetches: [SseFaultRestFetch]?
    let publishedProfileId: String?
    let transcript: [SseFaultTranscriptMessage]?
    let streamOps: [String]?
    let requiredTargets: [String]?
    let liveTargets: [String]?
    let openedPaths: [String]?
    let openedPathCounts: [String: Int]?
    let publishedEventTypes: [String]?
}

struct SseFaultRestFetch: Decodable, Equatable, Sendable {
    let reason: String
    let count: Int
}

struct SseFaultTranscriptMessage: Decodable, Equatable, Sendable {
    let id: String
    let text: String
}
