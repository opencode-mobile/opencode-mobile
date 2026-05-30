import Foundation

@MainActor
protocol MessageLiveStore: AnyObject, Sendable {
    func observeMessages(sessionID: String, directory: String) -> AsyncStream<[Message]>
}
