import Factory
import Foundation

extension Container {
    var activeConnectionManager: Factory<ActiveConnectionManager> {
        self { ActiveConnectionManager() }.singleton
    }

    var connectionProfileStore: Factory<any ConnectionProfileStore> {
        self { UserDefaultsConnectionProfileStore() }.singleton
    }

    var connectionSessionCoordinator: Factory<ConnectionSessionCoordinator> {
        self { @MainActor in
            ConnectionSessionCoordinator(
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore()
            )
        }.singleton
    }

    var connectionProbeService: Factory<ConnectionProbeService> {
        self { ConnectionProbeService() }.singleton
    }

    var connectionProbing: Factory<any ConnectionProbing> {
        self { self.connectionProbeService() }.singleton
    }

    private var connectionProvider: Factory<@Sendable () -> ServerConnection?> {
        self {
            let manager = self.activeConnectionManager()
            return { manager.connection }
        }.singleton
    }

    var sessionsAPIClient: Factory<SessionsAPIClient> {
        self { SessionsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var sessionDomainAdapter: Factory<SessionDomainAdapter> {
        self { SessionDomainAdapter() }.singleton
    }

    var projectsAPIClient: Factory<ProjectsAPIClient> {
        self { ProjectsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var projectDomainAdapter: Factory<ProjectDomainAdapter> {
        self { ProjectDomainAdapter() }.singleton
    }

    var projectRepository: Factory<any ProjectRepository> {
        self {
            DefaultProjectRepository(
                api: self.projectsAPIClient(),
                adapter: self.projectDomainAdapter()
            )
        }.singleton
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
        self { MessagesAPIClient(connectionProvider: self.connectionProvider()) }.singleton
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

    var eventsAPIClient: Factory<EventsAPIClient> {
        self { EventsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var eventDomainAdapter: Factory<EventDomainAdapter> {
        self { EventDomainAdapter() }.singleton
    }

    var foregroundEventStream: Factory<DefaultForegroundEventStream> {
        self { @MainActor in
            DefaultForegroundEventStream(
                api: self.eventsAPIClient(),
                adapter: self.eventDomainAdapter()
            )
        }.singleton
    }

    var sessionStatusLiveStore: Factory<DefaultSessionStatusLiveStore> {
        self { @MainActor in
            DefaultSessionStatusLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var sessionLiveStore: Factory<DefaultSessionLiveStore> {
        self { @MainActor in
            DefaultSessionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var messageLiveStore: Factory<DefaultMessageLiveStore> {
        self { @MainActor in
            DefaultMessageLiveStore(
                messageRepository: self.messageRepository(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var appRootViewModel: Factory<AppRootViewModel> {
        self { @MainActor in
            AppRootViewModel(
                connectionProfileStore: self.connectionProfileStore(),
                activeConnectionManager: self.activeConnectionManager(),
                connectionSessionCoordinator: self.connectionSessionCoordinator(),
                foregroundEventStream: self.foregroundEventStream()
            )
        }
    }

    var connectionSetupViewModel: Factory<ConnectionSetupViewModel> {
        self { @MainActor in
            ConnectionSetupViewModel(
                connectionProbeService: self.connectionProbing(),
                connectionSessionCoordinator: self.connectionSessionCoordinator()
            )
        }
    }

    var projectListHomeViewModel: Factory<ProjectListHomeViewModel> {
        self { @MainActor in
            ProjectListHomeViewModel(
                projectRepository: self.projectRepository(),
                sessionRepository: self.sessionRepository(),
                connectionProfileStore: self.connectionProfileStore(),
                activeConnectionManager: self.activeConnectionManager(),
                sessionLiveStore: self.sessionLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                foregroundEventStream: self.foregroundEventStream(),
                connectionSessionCoordinator: self.connectionSessionCoordinator()
            )
        }
    }

    var sessionChatViewModel: ParameterFactory<(sessionID: String, sessionTitle: String, worktree: String), SessionChatViewModel> {
        self { @MainActor in
            SessionChatViewModel(
                sessionID: $0.sessionID,
                sessionTitle: $0.sessionTitle,
                worktree: $0.worktree,
                messageRepository: self.messageRepository(),
                sessionRepository: self.sessionRepository(),
                messageLiveStore: self.messageLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore()
            )
        }
    }
}

extension Container {
    @MainActor
    static func makeAppRootViewModel() -> AppRootViewModel {
        shared.appRootViewModel()
    }

    @MainActor
    static func makeConnectionSetupViewModel(initialProfile: ConnectionProfile?) -> ConnectionSetupViewModel {
        ConnectionSetupViewModel(
            connectionProbeService: shared.connectionProbing(),
            connectionSessionCoordinator: shared.connectionSessionCoordinator(),
            initialProfile: initialProfile
        )
    }

    @MainActor
    static func makeProjectListHomeViewModel() -> ProjectListHomeViewModel {
        shared.projectListHomeViewModel()
    }

    @MainActor
    static func makeSessionChatViewModel(sessionID: String, sessionTitle: String, worktree: String) -> SessionChatViewModel {
        shared.sessionChatViewModel((sessionID: sessionID, sessionTitle: sessionTitle, worktree: worktree))
    }
}
