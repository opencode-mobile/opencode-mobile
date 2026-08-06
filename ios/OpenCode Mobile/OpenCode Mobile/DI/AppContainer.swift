import Factory
import Foundation

extension Container {
    var activeConnectionManager: Factory<ActiveConnectionManager> {
        self { ActiveConnectionManager() }.singleton
    }

    var connectionProfileStore: Factory<any ConnectionProfileStore> {
        self { UserDefaultsConnectionProfileStore() }.singleton
    }

    var sessionModelSelectionStore: Factory<any SessionModelSelectionStore> {
        self { UserDefaultsSessionModelSelectionStore() }.singleton
    }

    var connectionSessionCoordinator: Factory<ConnectionSessionCoordinator> {
        self { @MainActor in
            ConnectionSessionCoordinator(
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore(),
                secureCredentialStore: self.secureCredentialStore(),
                sessionModelSelectionStore: self.sessionModelSelectionStore()
            )
        }.singleton
    }

    var secureCredentialStore: Factory<any SecureCredentialStore> {
        self { KeychainCredentialStore() }.singleton
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

    var filesystemAPIClient: Factory<FilesystemAPIClient> {
        self { FilesystemAPIClient(connectionProvider: self.connectionProvider()) }.singleton
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

    var filesystemRepository: Factory<any FilesystemRepository> {
        self {
            DefaultFilesystemRepository(api: self.filesystemAPIClient())
        }.singleton
    }

    var sessionRepository: Factory<any SessionRepository> {
        self {
            DefaultSessionRepository(
                api: self.sessionsAPIClient(),
                adapter: self.sessionDomainAdapter(),
                sessionModelSelectionStore: self.sessionModelSelectionStore(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var messagesAPIClient: Factory<MessagesAPIClient> {
        self { MessagesAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var messageDomainAdapter: Factory<MessageDomainAdapter> {
        self { MessageDomainAdapter() }.singleton
    }

    var messageStore: Factory<InMemoryMessageStore> {
        self { InMemoryMessageStore() }.singleton
    }

    var sessionCacheStore: Factory<any SessionCacheStore> {
        self {
            do {
                let container = try SwiftDataSessionCacheStore.makePersistentContainer()
                return SwiftDataSessionCacheStore(container: container)
            } catch {
                print("SessionCache: persistent SwiftData store failed (\(error)); using in-memory fallback")
                do {
                    let container = try SwiftDataSessionCacheStore.makeInMemoryContainer()
                    return SwiftDataSessionCacheStore(container: container)
                } catch {
                    print("SessionCache: in-memory SwiftData store failed (\(error)); using InMemorySessionCacheStore")
                    return InMemorySessionCacheStore()
                }
            }
        }.singleton
    }

    var sessionCache: Factory<any SessionCache> {
        self {
            let manager = self.activeConnectionManager()
            return DefaultSessionCache(
                store: self.sessionCacheStore(),
                connectionIdProvider: {
                    guard let connection = manager.connection else { return nil }
                    return CacheUrlNormalizer.connectionId(
                        baseURL: connection.baseURL.absoluteString,
                        username: connection.username
                    )
                }
            )
        }.singleton
    }

    var sessionMessageEngine: Factory<SessionMessageEngine> {
        self { SessionMessageEngine(store: self.messageStore()) }.singleton
    }

    var messageSync: Factory<MessageSync> {
        self {
            let api = self.messagesAPIClient()
            let adapter = self.messageDomainAdapter()
            let manager = self.activeConnectionManager()
            return MessageSync(
                listMessages: { sessionID, directory in
                    do {
                        return try await api.listMessages(sessionID: sessionID, directory: directory).map(adapter.message)
                    } catch {
                        throw adapter.mapError(error)
                    }
                },
                store: self.sessionMessageEngine(),
                eventStream: self.foregroundEventStream(),
                connectionProfileIDProvider: {
                    guard let connection = manager.connection else { return "local" }
                    return PreparedHistoryCacheKey.profileID(
                        baseURL: connection.baseURL.absoluteString,
                        username: connection.username
                    )
                }
            )
        }.singleton
    }

    var messageContentPreparer: Factory<MessageContentPreparer> {
        self { MessageContentPreparer.shared }.singleton
    }

    var displayMessagePipeline: Factory<DisplayMessagePipeline> {
        self { @MainActor in
            let manager = self.activeConnectionManager()
            return DisplayMessagePipeline(
                messageSync: self.messageSync(),
                contentPreparer: self.messageContentPreparer(),
                connectionProfileIDProvider: {
                    guard let connection = manager.connection else { return "local" }
                    return PreparedHistoryCacheKey.profileID(
                        baseURL: connection.baseURL.absoluteString,
                        username: connection.username
                    )
                }
            )
        }.singleton
    }

    var messageRepository: Factory<any MessageRepository> {
        self { @MainActor in
            DefaultMessageRepository(
                api: self.messagesAPIClient(),
                adapter: self.messageDomainAdapter(),
                messageSync: self.messageSync(),
                displayMessagePipeline: self.displayMessagePipeline(),
                contentPreparer: self.messageContentPreparer()
            )
        }.singleton
    }

    var modelsAPIClient: Factory<ModelsAPIClient> {
        self { ModelsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var modelDomainAdapter: Factory<ModelDomainAdapter> {
        self { ModelDomainAdapter() }.singleton
    }

    var modelRepository: Factory<any ModelRepository> {
        self {
            DefaultModelRepository(
                api: self.modelsAPIClient(),
                adapter: self.modelDomainAdapter()
            )
        }.singleton
    }

    var commandsAPIClient: Factory<CommandsAPIClient> {
        self { CommandsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var commandDomainAdapter: Factory<CommandDomainAdapter> {
        self { CommandDomainAdapter() }.singleton
    }

    var commandRepository: Factory<any CommandRepository> {
        self {
            DefaultCommandRepository(
                api: self.commandsAPIClient(),
                adapter: self.commandDomainAdapter()
            )
        }.singleton
    }

    var agentsAPIClient: Factory<AgentsAPIClient> {
        self { AgentsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var agentDomainAdapter: Factory<AgentDomainAdapter> {
        self { AgentDomainAdapter() }.singleton
    }

    var agentRepository: Factory<any AgentRepository> {
        self {
            DefaultAgentRepository(
                api: self.agentsAPIClient(),
                adapter: self.agentDomainAdapter()
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

    var permissionLiveStore: Factory<DefaultPermissionLiveStore> {
        self { @MainActor in
            DefaultPermissionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var questionLiveStore: Factory<DefaultQuestionLiveStore> {
        self { @MainActor in
            DefaultQuestionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var sessionTodoLiveStore: Factory<DefaultSessionTodoLiveStore> {
        self { @MainActor in
            DefaultSessionTodoLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var sessionReadyStore: Factory<DefaultSessionReadyStore> {
        self { @MainActor in
            DefaultSessionReadyStore(
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                eventStream: self.foregroundEventStream(),
                persistence: UserDefaultsSessionReadyPersistence()
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

    var sessionContextRepository: Factory<any SessionContextRepository> {
        self {
            DefaultSessionContextRepository(
                sessionRepository: self.sessionRepository(),
                messageRepository: self.messageRepository(),
                modelRepository: self.modelRepository()
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
                filesystemRepository: self.filesystemRepository(),
                sessionRepository: self.sessionRepository(),
                connectionProfileStore: self.connectionProfileStore(),
                activeConnectionManager: self.activeConnectionManager(),
                sessionLiveStore: self.sessionLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                foregroundEventStream: self.foregroundEventStream(),
                connectionSessionCoordinator: self.connectionSessionCoordinator(),
                newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                    store: self.sessionModelSelectionStore(),
                    modelRepository: self.modelRepository()
                )
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
                modelRepository: self.modelRepository(),
                commandRepository: self.commandRepository(),
                agentRepository: self.agentRepository(),
                sessionRepository: self.sessionRepository(),
                permissionLiveStore: self.permissionLiveStore(),
                questionLiveStore: self.questionLiveStore(),
                sessionTodoLiveStore: self.sessionTodoLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                foregroundEventStream: self.foregroundEventStream(),
                sessionModelSelectionStore: self.sessionModelSelectionStore(),
                activeConnectionManager: self.activeConnectionManager(),
                newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                    store: self.sessionModelSelectionStore(),
                    modelRepository: self.modelRepository()
                )
            )
        }
    }

    var sessionContextViewModel: ParameterFactory<(sessionID: String, worktree: String), SessionContextViewModel> {
        self { @MainActor in
            SessionContextViewModel(
                sessionID: $0.sessionID,
                worktree: $0.worktree,
                repository: self.sessionContextRepository()
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

    @MainActor
    static func makeSessionContextViewModel(sessionID: String, worktree: String) -> SessionContextViewModel {
        shared.sessionContextViewModel((sessionID: sessionID, worktree: worktree))
    }

    @MainActor
    static func clearAllData() {
        Task { @MainActor in
            shared.foregroundEventStream().stop()
            await shared.connectionSessionCoordinator().clearConnectionData()
            shared.sessionLiveStore().clearCache()
            shared.sessionStatusLiveStore().clearCache()
            shared.permissionLiveStore().clearCache()
            shared.questionLiveStore().clearCache()
            shared.sessionTodoLiveStore().clearCache()
            shared.sessionReadyStore().clearCache()
            shared.messageRepository().clearMessageCache()
            ThemeManager.shared.clearAll()
            HomeDisplayPreferences.shared.clear()
            MarkdownHighlightPreferences.shared.clear()
            MessageContentPreparer.shared.clearDocumentCache()
            BlockDecorationCache.shared.clear()
            await TextMateLogbook.shared.clear()
            DiagnosticLogBuffer.shared.clear()
        }
    }
}
