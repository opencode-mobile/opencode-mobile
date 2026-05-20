import Factory
import Foundation

extension Container {
    var serverConnection: Factory<ServerConnection> {
        self { ServerConnection.fromAppConfig() }.singleton
    }

    var sessionsAPIClient: Factory<SessionsAPIClient> {
        self { SessionsAPIClient(connection: self.serverConnection()) }.singleton
    }

    var sessionDomainAdapter: Factory<SessionDomainAdapter> {
        self { SessionDomainAdapter() }.singleton
    }

    var sessionRepository: Factory<any SessionRepository> {
        self {
            DefaultSessionRepository(
                api: self.sessionsAPIClient(),
                adapter: self.sessionDomainAdapter()
            )
        }.singleton
    }

    var messagesAPIClient: Factory<MessagesAPIClient> {
        self { MessagesAPIClient(connection: self.serverConnection()) }.singleton
    }

    var messageDomainAdapter: Factory<MessageDomainAdapter> {
        self { MessageDomainAdapter() }.singleton
    }

    var messageRepository: Factory<any MessageRepository> {
        self {
            DefaultMessageRepository(
                api: self.messagesAPIClient(),
                adapter: self.messageDomainAdapter()
            )
        }.singleton
    }

    var projectListHomeViewModel: Factory<ProjectListHomeViewModel> {
        self { @MainActor in
            ProjectListHomeViewModel(sessionRepository: self.sessionRepository())
        }
    }

    var sessionChatViewModel: ParameterFactory<(sessionID: String, sessionTitle: String), SessionChatViewModel> {
        self { @MainActor in
            SessionChatViewModel(
                sessionID: $0.sessionID,
                sessionTitle: $0.sessionTitle,
                messageRepository: self.messageRepository()
            )
        }
    }
}

extension Container {
    @MainActor
    static func makeProjectListHomeViewModel() -> ProjectListHomeViewModel {
        shared.projectListHomeViewModel()
    }

    @MainActor
    static func makeSessionChatViewModel(sessionID: String, sessionTitle: String) -> SessionChatViewModel {
        shared.sessionChatViewModel((sessionID: sessionID, sessionTitle: sessionTitle))
    }
}
