import Foundation

struct AgentDTO: Codable, Sendable {
    let name: String
    let description: String?
    let mode: String
    let hidden: Bool?
}
