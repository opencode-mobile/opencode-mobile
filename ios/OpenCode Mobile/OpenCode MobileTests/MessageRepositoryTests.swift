import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct MessageRepositoryTests {
    @Test func sendPromptAsync_forwardsAttachmentRequestThroughRepository() async throws {
        let api = RecordingMessagesAPI()
        let events = RepositoryTestEventStream()
        let sync = MessageSync(listMessages: { _, _ in [] }, store: InMemoryMessageStore(), eventStream: events)
        let preparer = MessageContentPreparer()
        let repository: any MessageRepository = DefaultMessageRepository(
            api: api,
            adapter: MessageDomainAdapter(),
            messageSync: sync,
            displayMessagePipeline: DisplayMessagePipeline(messageSync: sync, contentPreparer: preparer),
            contentPreparer: preparer
        )
        let attachment = OpenCode_Mobile.Attachment(
            id: "attachment-1",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGVsbG8="
        )

        try await repository.sendPromptAsync(
            sessionID: "session-1",
            options: PromptSendOptions(text: "Review", attachments: [attachment]),
            directory: "/repo"
        )

        #expect(api.sentSessionID == "session-1")
        #expect(api.sentDirectory == "/repo")
        #expect(api.sentBody?.parts.map(\.type) == ["text", "file"])
        #expect(api.sentBody?.parts.last?.id == "attachment-1")
        #expect(api.sentBody?.parts.last?.filename == "notes.txt")
    }
}

@MainActor
private final class RecordingMessagesAPI: MessagesAPI {
    var sentSessionID: String?
    var sentDirectory: String?
    var sentBody: SendMessageRequestDTO?

    func listMessages(sessionID: String, directory: String?) async throws -> [MessageDTO] { [] }
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> MessageDTO { fatalError() }
    func sendMessage(sessionID: String, body: SendMessageRequestDTO, directory: String?) async throws -> MessageDTO { fatalError() }
    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, directory: String?) async throws {
        sentSessionID = sessionID
        sentDirectory = directory
        sentBody = body
    }
}

@MainActor
private final class RepositoryTestEventStream: ForegroundEventStream {
    var connectionState: EventStreamConnectionState = .connected
    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> { AsyncStream { _ in } }
    func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    func start() {}
    func stop() {}
}
