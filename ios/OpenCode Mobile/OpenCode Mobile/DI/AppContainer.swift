import Factory
import Foundation

extension Container {
    var activeConnectionManager: Factory<ActiveConnectionManager> {
        self {
            ActiveConnectionManager(
                connectionProbing: self.connectionProbing(),
                profileStore: self.connectionProfileStore()
            )
        }.singleton
    }

    var connectionProfileStore: Factory<any ConnectionProfileStore> {
        self { UserDefaultsConnectionProfileStore() }.singleton
    }

    var sessionModelSelectionStore: Factory<any SessionModelSelectionStore> {
        self { UserDefaultsSessionModelSelectionStore() }.singleton
    }

    var composerDraftStore: Factory<any ComposerDraftStore> {
        self { UserDefaultsComposerDraftStore() }.singleton
    }

    var connectionSessionCoordinator: Factory<ConnectionSessionCoordinator> {
        self { @MainActor in
            ConnectionSessionCoordinator(
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore(),
                secureCredentialStore: self.secureCredentialStore(),
                sessionModelSelectionStore: self.sessionModelSelectionStore(),
                composerDraftStore: self.composerDraftStore(),
                eventStream: { Container.shared.foregroundEventStream() },
                syncCoordinator: { Container.shared.syncCoordinator() },
                projectionCaches: { Container.shared.projectionCaches() },
                bindReadyProfile: { profileID in
                    await MainActor.run {
                        Container.shared.sessionReadyStore().bindProfile(profileID: profileID)
                    }
                },
                occupancy: { Container.shared.worldSignals().occupancy },
                emitProfileSwitched: { Container.shared.worldSignals().emitProfileSwitched() },
                hasActiveConnection: {
                    let manager = Container.shared.activeConnectionManager()
                    return manager.connection != nil || manager.currentProfile() != nil
                }
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
                adapter: self.projectDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var filesystemRepository: Factory<any FilesystemRepository> {
        self {
            DefaultFilesystemRepository(
                api: self.filesystemAPIClient(),
                adapter: FilesystemDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var sessionRepository: Factory<any SessionRepository> {
        self {
            DefaultSessionRepository(
                api: self.sessionsAPIClient(),
                adapter: self.sessionDomainAdapter(),
                sessionModelSelectionStore: self.sessionModelSelectionStore(),
                composerDraftStore: self.composerDraftStore(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var messagesAPIClient: Factory<MessagesAPIClient> {
        self {
            MessagesAPIClient(
                connectionProvider: self.connectionProvider(),
                bodyUploader: DurableJSONUploader.shared
            )
        }.singleton
    }

    var messageDomainAdapter: Factory<MessageDomainAdapter> {
        self { MessageDomainAdapter() }.singleton
    }

    var messageRemote: Factory<any MessageRemote> {
        self {
            ProtocolSelectedMessageRemote(
                api: self.messagesAPIClient(),
                adapter: self.messageDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
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
                    if let id = manager.currentProfile()?.id, !id.isEmpty {
                        return id
                    }
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

    var syncCoordinator: Factory<DefaultSyncCoordinator> {
        self { @MainActor in
            DefaultSyncCoordinator(
                connection: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var messageSync: Factory<MessageSync> {
        self { @MainActor in
            let router = self.messageRemote()
            let adapter = self.messageDomainAdapter()
            let manager = self.activeConnectionManager()
            return MessageSync(
                listMessages: { sessionID, directory in
                    do {
                        return try await router.listMessages(sessionID: sessionID, directory: directory)
                    } catch {
                        throw adapter.mapError(error)
                    }
                },
                listMessagePage: { sessionID, directory, query in
                    do {
                        return try await router.listMessagePage(
                            sessionID: sessionID,
                            directory: directory,
                            query: query
                        )
                    } catch {
                        throw adapter.mapError(error)
                    }
                },
                store: self.sessionMessageEngine(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                connectionProfileIDProvider: {
                    if let id = manager.currentProfile()?.id, !id.isEmpty {
                        return id
                    }
                    guard let connection = manager.connection else { return "local" }
                    return PreparedHistoryCacheKey.profileID(
                        baseURL: connection.baseURL.absoluteString,
                        username: connection.username
                    )
                },
                sessionCache: self.sessionCache(),
                syncCoordinator: self.syncCoordinator()
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
                    if let id = manager.currentProfile()?.id, !id.isEmpty {
                        return id
                    }
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
                contentPreparer: self.messageContentPreparer(),
                messageRemote: self.messageRemote()
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
                adapter: self.modelDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var commandsAPIClient: Factory<CommandsAPIClient> {
        self {
            CommandsAPIClient(
                connectionProvider: self.connectionProvider(),
                bodyUploader: DurableJSONUploader.shared
            )
        }.singleton
    }

    var commandDomainAdapter: Factory<CommandDomainAdapter> {
        self { CommandDomainAdapter() }.singleton
    }

    var commandRepository: Factory<any CommandRepository> {
        self {
            DefaultCommandRepository(
                api: self.commandsAPIClient(),
                adapter: self.commandDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var ptyAPIClient: Factory<PtyAPIClient> {
        self { PtyAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var terminalDomainAdapter: Factory<TerminalDomainAdapter> {
        self { TerminalDomainAdapter() }.singleton
    }

    var terminalRepository: Factory<any TerminalRepository> {
        self {
            DefaultTerminalRepository(
                api: self.ptyAPIClient(),
                adapter: self.terminalDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
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
                adapter: self.agentDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var eventsAPIClient: Factory<EventsAPIClient> {
        self {
            EventsAPIClient(
                connectionProvider: self.connectionProvider()
            )
        }.singleton
    }

    var eventDomainAdapter: Factory<EventDomainAdapter> {
        self { EventDomainAdapter() }.singleton
    }

    var deviceNetworkPath: Factory<any DeviceNetworkPath> {
        self { SystemDeviceNetworkPath() }.singleton
    }

    var foregroundEventStream: Factory<DefaultForegroundEventStream> {
        self { @MainActor in
            let store = self.connectionProfileStore()
            let manager = self.activeConnectionManager()
            return DefaultForegroundEventStream(
                api: self.eventsAPIClient(),
                adapter: self.eventDomainAdapter(),
                deviceNetworkPath: self.deviceNetworkPath(),
                instanceDirectories: {
                    guard let url = manager.connection?.baseURL.absoluteString else { return [] }
                    return await store.addedWorktrees(serverBaseURL: url)
                },
                configurationRevisions: { store.configurationRevisions() },
                profileID: {
                    if let id = manager.currentProfile()?.id, !id.isEmpty {
                        return id
                    }
                    guard let connection = manager.connection else { return "" }
                    return "\(ConnectionProfileIdentity.normalizeBaseURL(connection.baseURL.absoluteString))|\(ConnectionProfileIdentity.normalizeUsername(connection.username))"
                },
                connectionIdentity: {
                    guard let connection = manager.connection else { return "" }
                    return "\(ConnectionProfileIdentity.normalizeBaseURL(connection.baseURL.absoluteString))|\(ConnectionProfileIdentity.normalizeUsername(connection.username))"
                },
                credentialFingerprint: {
                    guard let connection = manager.connection else { return "" }
                    return ConnectionProfileIdentity.credentialFingerprint(
                        username: connection.username,
                        password: connection.password ?? ""
                    )
                },
                protocolHint: { manager.protocolHint() },
                cachedProtocol: { manager.serverProtocol },
                protocolProbe: { await manager.currentProtocolOrProbe() }
            )
        }.singleton
    }

    var worldSignals: Factory<SceneWorldSignals> {
        self { @MainActor in
            let manager = self.activeConnectionManager()
            return SceneWorldSignals(
                stream: self.foregroundEventStream(),
                deviceNetworkPath: self.deviceNetworkPath(),
                hasActiveConnection: {
                    manager.connection != nil || manager.currentProfile() != nil
                },
                activeConnectionManager: manager
            )
        }.singleton
    }

    var liveUpdates: Factory<any LiveUpdates> {
        self { @MainActor in
            let manager = self.activeConnectionManager()
            return DefaultLiveUpdates(
                worldSignals: self.worldSignals(),
                stream: self.foregroundEventStream(),
                path: self.deviceNetworkPath(),
                hasActiveConnection: {
                    manager.connection != nil || manager.currentProfile() != nil
                }
            )
        }.singleton
    }

    var sessionStatusLiveStore: Factory<DefaultSessionStatusLiveStore> {
        self { @MainActor in
            DefaultSessionStatusLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                eventAdapter: self.eventDomainAdapter(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator(),
                sessionCache: self.sessionCache()
            )
        }.singleton
    }

    var permissionLiveStore: Factory<DefaultPermissionLiveStore> {
        self { @MainActor in
            DefaultPermissionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
            )
        }.singleton
    }

    var questionLiveStore: Factory<DefaultQuestionLiveStore> {
        self { @MainActor in
            DefaultQuestionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
            )
        }.singleton
    }

    var sessionTodoLiveStore: Factory<DefaultSessionTodoLiveStore> {
        self { @MainActor in
            DefaultSessionTodoLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
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
                eventStream: self.foregroundEventStream(),
                sessionCache: self.sessionCache(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
            )
        }.singleton
    }

    var projectionCaches: Factory<ProjectionCaches> {
        self { @MainActor in
            ProjectionCaches(
                sessionLiveStore: self.sessionLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                permissionLiveStore: self.permissionLiveStore(),
                questionLiveStore: self.questionLiveStore(),
                sessionTodoLiveStore: self.sessionTodoLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                messageRepository: self.messageRepository()
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
                worldSignals: self.worldSignals(),
                appOnboardingPreferences: AppOnboardingPreferences.shared
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
                messageRepository: self.messageRepository(),
                connectionProfileStore: self.connectionProfileStore(),
                activeConnectionManager: self.activeConnectionManager(),
                sessionLiveStore: self.sessionLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                permissionLiveStore: self.permissionLiveStore(),
                questionLiveStore: self.questionLiveStore(),
                sessionTodoLiveStore: self.sessionTodoLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                foregroundEventStream: self.foregroundEventStream(),
                connectionSessionCoordinator: self.connectionSessionCoordinator(),
                newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                    store: self.sessionModelSelectionStore(),
                    modelRepository: self.modelRepository()
                ),
                sessionCache: self.sessionCache(),
                liveUpdates: self.liveUpdates()
            )
        }
    }

    var lastTurnReviewChangesStore: Factory<LastTurnReviewChangesStore> {
        self { @MainActor in LastTurnReviewChangesStore() }.singleton
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
                composerDraftStore: self.composerDraftStore(),
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore(),
                newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                    store: self.sessionModelSelectionStore(),
                    modelRepository: self.modelRepository()
                ),
                liveUpdates: self.liveUpdates(),
                lastTurnReviewChangesStore: self.lastTurnReviewChangesStore(),
                syncCoordinator: self.syncCoordinator()
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

    var terminalViewModel: ParameterFactory<(worktree: String, ptyId: String?), TerminalViewModel> {
        self { @MainActor in
            TerminalViewModel(
                worktree: $0.worktree,
                ptyId: $0.ptyId,
                repository: self.terminalRepository(),
                activeConnectionManager: self.activeConnectionManager(),
                socketClient: URLSessionTerminalSocketClient()
            )
        }
    }

    var terminalsCatalogViewModel: Factory<TerminalsCatalogViewModel> {
        self { @MainActor in
            TerminalsCatalogViewModel(
                repository: self.terminalRepository(),
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore()
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
    static func makeConnectionSetupViewModel(
        initialProfile: ConnectionProfile?,
        mode: ConnectionSetupMode = .first
    ) -> ConnectionSetupViewModel {
        ConnectionSetupViewModel(
            connectionProbeService: shared.connectionProbing(),
            connectionSessionCoordinator: shared.connectionSessionCoordinator(),
            initialProfile: initialProfile,
            mode: mode
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
    static func makeTerminalViewModel(worktree: String, ptyId: String? = nil) -> TerminalViewModel {
        shared.terminalViewModel((worktree: worktree, ptyId: ptyId))
    }

    @MainActor
    static func makeTerminalsCatalogViewModel() -> TerminalsCatalogViewModel {
        shared.terminalsCatalogViewModel()
    }

    @MainActor
    static func clearAllData() {
        Task { @MainActor in
            await shared.connectionSessionCoordinator().clearConnectionData()
            await shared.sessionCache().clearAll()
            ThemeManager.shared.clearAll()
            HomeDisplayPreferences.shared.clear()
            MarkdownHighlightPreferences.shared.clear()
            AnalyticsConsentPreferences.shared.clear()
            AppOnboardingPreferences.shared.clear()
            DeveloperToolsPreferences.shared.clear()
            SessionChatDisplayPreferences.shared.clear()
            MessageContentPreparer.shared.clearDocumentCache()
            BlockDecorationCache.shared.clear()
            await TextMateLogbook.shared.clear()
            DiagnosticLogBuffer.shared.clear()
        }
    }

    @MainActor
    static func refreshSessionCacheSizeLabel() async -> String {
        let bytes = await shared.sessionCache().estimatedSizeBytes()
        return SessionCacheSizeFormatter.format(bytes: bytes)
    }

    @MainActor
    static func clearSessionCache() async {
        shared.projectionCaches().clearAll()
        await shared.sessionCache().clearAll()
    }
}
