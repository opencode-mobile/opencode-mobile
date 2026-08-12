import Foundation

nonisolated struct V2LocationDTO: Codable, Sendable {
    let directory: String
    let workspaceID: String?

    init(directory: String, workspaceID: String? = nil) {
        self.directory = directory
        self.workspaceID = workspaceID
    }
}

nonisolated struct V2SessionDTO: Decodable, Sendable {
    let id: String
    let title: String
    let projectID: String?
    let parentID: String?
    let cost: Double?
    let tokens: MessageTokensDTO?
    let time: SessionTimeDTO?
    let location: V2LocationDTO?
    let revert: SessionRevertDTO?
}

nonisolated struct V2CreateSessionRequestDTO: Encodable, Sendable {
    let location: V2LocationDTO?
}

nonisolated struct V2RenameSessionRequestDTO: Encodable, Sendable {
    let title: String
}

nonisolated struct V2ForkSessionRequestDTO: Encodable, Sendable {
    let messageID: String?
}
