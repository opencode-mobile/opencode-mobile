import Foundation

public nonisolated struct AgentDTO: Codable, Sendable {
    public let name: String
    public let description: String?
    public let mode: String
    public let hidden: Bool?

    public init(
        name: String,
        description: String?,
        mode: String,
        hidden: Bool?
    ) {
        self.name = name
        self.description = description
        self.mode = mode
        self.hidden = hidden
    }
}
