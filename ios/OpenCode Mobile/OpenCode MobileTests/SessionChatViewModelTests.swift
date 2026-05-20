import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatViewModelTests {
    @Test func loadMessages_success() async {
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            messageRepository: FakeMessageRepository()
        )
        await viewModel.loadMessages()
        guard case let .loaded(_, _, messages, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(messages.count == 1)
        #expect(messages[0].text == "Hello")
    }

    @Test func loadMessages_empty() async {
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            messageRepository: FakeMessageRepository(messages: [])
        )
        await viewModel.loadMessages()
        guard case .empty = viewModel.state else {
            Issue.record("Expected empty state")
            return
        }
    }

    @Test func loadMessages_error() async {
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            messageRepository: FakeMessageRepository(shouldFailLoad: true)
        )
        await viewModel.loadMessages()
        guard case .error = viewModel.state else {
            Issue.record("Expected error state")
            return
        }
    }

    @Test func sendPrompt_success() async {
        let repository = FakeMessageRepository()
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            messageRepository: repository
        )
        await viewModel.loadMessages()
        viewModel.updateComposerText("New prompt")
        await viewModel.sendPrompt()
        guard case let .loaded(_, _, messages, _, isSending, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(isSending == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(messages.contains { $0.text == "Hello" })
    }

    @Test func sendPrompt_failure() async {
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            messageRepository: FakeMessageRepository(shouldFailSend: true)
        )
        await viewModel.loadMessages()
        viewModel.updateComposerText("Fail me")
        await viewModel.sendPrompt()
        guard case let .loaded(_, _, messages, _, _, _, errorBanner) = viewModel.state else {
            Issue.record("Expected loaded state with banner")
            return
        }
        #expect(!messages.contains { $0.text == "Fail me" })
        #expect(errorBanner != nil)
    }
}

private final class FakeMessageRepository: MessageRepository, @unchecked Sendable {
    let messages: [Message]
    let shouldFailLoad: Bool
    let shouldFailSend: Bool
    var sendPromptAsyncCalled = false

    init(
        messages: [Message] = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
        ],
        shouldFailLoad: Bool = false,
        shouldFailSend: Bool = false
    ) {
        self.messages = messages
        self.shouldFailLoad = shouldFailLoad
        self.shouldFailSend = shouldFailSend
    }

    func listMessages(sessionID: String) async throws -> [Message] {
        if shouldFailLoad {
            throw MessageError.transport("load failed")
        }
        return messages
    }

    func getMessage(sessionID: String, messageID: String) async throws -> Message {
        fatalError()
    }

    func sendMessage(sessionID: String, text: String) async throws -> Message {
        fatalError()
    }

    func sendPromptAsync(sessionID: String, text: String) async throws {
        sendPromptAsyncCalled = true
        if shouldFailSend {
            throw MessageError.transport("send failed")
        }
    }
}
