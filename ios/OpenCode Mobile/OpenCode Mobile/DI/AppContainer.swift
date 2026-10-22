import FactoryKit
import Foundation

extension ManagedContainer {
    /// Factory 3 registrations take a nonisolated `() -> T`. Factory 2.5 took
    /// `@isolated(any)` and invoked it on the resolving thread. This app's default
    /// isolation is MainActor, so keep that same call and do not hop.
    func onMainActor<T>(
        key: StaticString = #function,
        _ body: @MainActor @escaping () -> T
    ) -> Factory<T> {
        let make: () -> T = unsafeBitCast(body, to: (() -> T).self)
        return self(key: key, make)
    }

    func onMainActor<P, T>(
        key: StaticString = #function,
        _ body: @MainActor @escaping (P) -> T
    ) -> ParameterFactory<P, T> {
        let make: (P) -> T = unsafeBitCast(body, to: ((P) -> T).self)
        return self(key: key, make)
    }
}

extension Container {
    var activeConnectionManager: Factory<ActiveConnectionManager> {
        onMainActor {
            ActiveConnectionManager(
                connectionProbing: self.connectionProbing(),
                profileStore: self.connectionProfileStore()
            )
        }.singleton
    }

    var connectionProfileStore: Factory<any ConnectionProfileStore> {
        onMainActor { UserDefaultsConnectionProfileStore() }.singleton
    }

    var sessionModelSelectionStore: Factory<any SessionModelSelectionStore> {
        onMainActor { UserDefaultsSessionModelSelectionStore() }.singleton
    }

    var composerDraftStore: Factory<any ComposerDraftStore> {
        onMainActor { UserDefaultsComposerDraftStore() }.singleton
    }

    var connectionSessionCoordinator: Factory<ConnectionSessionCoordinator> {
        onMainActor {
            ConnectionSessionCoordinator(
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore(),
                secureCredentialStore: self.secureCredentialStore(),
                sessionModelSelectionStore: self.sessionModelSelectionStore(),
                composerDraftStore: self.composerDraftStore(),
                sessionCache: { Container.shared.sessionCache() },
                feedbackRefreshTokenStore: KeychainFeedbackRefreshTokenStore(),
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
        onMainActor { KeychainCredentialStore() }.singleton
    }

    var connectionProbeService: Factory<ConnectionProbeService> {
        onMainActor { ConnectionProbeService() }.singleton
    }

    var connectionProbing: Factory<any ConnectionProbing> {
        onMainActor { self.connectionProbeService() }.singleton
    }

    private var connectionProvider: Factory<@Sendable () -> ServerConnection?> {
        onMainActor {
            let manager = self.activeConnectionManager()
            return { manager.connection }
        }.singleton
    }

    var sessionsAPIClient: Factory<SessionsAPIClient> {
        onMainActor { SessionsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var sessionDomainAdapter: Factory<SessionDomainAdapter> {
        onMainActor { SessionDomainAdapter() }.singleton
    }

    var projectsAPIClient: Factory<ProjectsAPIClient> {
        onMainActor { ProjectsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var filesystemAPIClient: Factory<FilesystemAPIClient> {
        onMainActor { FilesystemAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var projectDomainAdapter: Factory<ProjectDomainAdapter> {
        onMainActor { ProjectDomainAdapter() }.singleton
    }

    var worktreesAPIClient: Factory<WorktreesAPIClient> {
        onMainActor { WorktreesAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var worktreeRepository: Factory<any WorktreeRepository> {
        onMainActor {
            DefaultWorktreeRepository(
                api: self.worktreesAPIClient(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    /// Home's worktree inventories, read by Session Chat for its first frame.
    var worktreeInventoryCache: Factory<WorktreeInventoryCache> {
        onMainActor { WorktreeInventoryCache() }.singleton
    }

    var projectRepository: Factory<any ProjectRepository> {
        onMainActor {
            DefaultProjectRepository(
                api: self.projectsAPIClient(),
                adapter: self.projectDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var filesystemRepository: Factory<any FilesystemRepository> {
        onMainActor {
            DefaultFilesystemRepository(
                api: self.filesystemAPIClient(),
                adapter: FilesystemDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var projectFilesRepository: Factory<any ProjectFilesRepository> {
        onMainActor {
            DefaultProjectFilesRepository(
                api: self.filesystemAPIClient(),
                adapter: FilesystemDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var sessionRepository: Factory<any SessionRepository> {
        onMainActor {
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
        onMainActor {
            MessagesAPIClient(
                connectionProvider: self.connectionProvider(),
                bodyUploader: DurableJSONUploader.shared
            )
        }.singleton
    }

    var messageDomainAdapter: Factory<MessageDomainAdapter> {
        onMainActor { MessageDomainAdapter() }.singleton
    }

    var messageRemote: Factory<any MessageRemote> {
        onMainActor {
            ProtocolSelectedMessageRemote(
                api: self.messagesAPIClient(),
                adapter: self.messageDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var messageStore: Factory<InMemoryMessageStore> {
        onMainActor { InMemoryMessageStore() }.singleton
    }

    var sessionCacheStore: Factory<any SessionCacheStore> {
        onMainActor {
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
        onMainActor {
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
        onMainActor { SessionMessageEngine(store: self.messageStore()) }.singleton
    }

    var syncCoordinator: Factory<DefaultSyncCoordinator> {
        onMainActor {
            DefaultSyncCoordinator(
                connection: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                eventStream: self.foregroundEventStream()
            )
        }.singleton
    }

    var messageSync: Factory<MessageSync> {
        onMainActor {
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
        onMainActor { MessageContentPreparer.shared }.singleton
    }

    var displayMessagePipeline: Factory<DisplayMessagePipeline> {
        onMainActor {
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
        onMainActor {
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
        onMainActor { ModelsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var modelDomainAdapter: Factory<ModelDomainAdapter> {
        onMainActor { ModelDomainAdapter() }.singleton
    }

    var modelRepository: Factory<any ModelRepository> {
        onMainActor {
            DefaultModelRepository(
                api: self.modelsAPIClient(),
                adapter: self.modelDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var modelCatalogStore: Factory<ModelCatalogStore> {
        onMainActor {
            ModelCatalogStore(
                modelRepository: self.modelRepository(),
                agentRepository: self.agentRepository(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var commandsAPIClient: Factory<CommandsAPIClient> {
        onMainActor {
            CommandsAPIClient(
                connectionProvider: self.connectionProvider(),
                bodyUploader: DurableJSONUploader.shared
            )
        }.singleton
    }

    var commandDomainAdapter: Factory<CommandDomainAdapter> {
        onMainActor { CommandDomainAdapter() }.singleton
    }

    var commandRepository: Factory<any CommandRepository> {
        onMainActor {
            DefaultCommandRepository(
                api: self.commandsAPIClient(),
                adapter: self.commandDomainAdapter(),
                messages: self.messagesAPIClient(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var ptyAPIClient: Factory<PtyAPIClient> {
        onMainActor { PtyAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var terminalDomainAdapter: Factory<TerminalDomainAdapter> {
        onMainActor { TerminalDomainAdapter() }.singleton
    }

    var terminalRepository: Factory<any TerminalRepository> {
        onMainActor {
            DefaultTerminalRepository(
                api: self.ptyAPIClient(),
                adapter: self.terminalDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var agentsAPIClient: Factory<AgentsAPIClient> {
        onMainActor { AgentsAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var agentDomainAdapter: Factory<AgentDomainAdapter> {
        onMainActor { AgentDomainAdapter() }.singleton
    }

    var agentRepository: Factory<any AgentRepository> {
        onMainActor {
            DefaultAgentRepository(
                api: self.agentsAPIClient(),
                adapter: self.agentDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var mcpAPIClient: Factory<McpAPIClient> {
        onMainActor { McpAPIClient(connectionProvider: self.connectionProvider()) }.singleton
    }

    var mcpDomainAdapter: Factory<McpDomainAdapter> {
        onMainActor { McpDomainAdapter() }.singleton
    }

    var mcpRepository: Factory<any McpRepository> {
        onMainActor {
            DefaultMcpRepository(
                api: self.mcpAPIClient(),
                adapter: self.mcpDomainAdapter(),
                activeConnectionManager: self.activeConnectionManager()
            )
        }.singleton
    }

    var eventsAPIClient: Factory<EventsAPIClient> {
        onMainActor {
            EventsAPIClient(
                connectionProvider: self.connectionProvider(),
                onConnectBudgetExceeded: { OpenCodeURLSessions.replaceRest() }
            )
        }.singleton
    }

    var eventDomainAdapter: Factory<EventDomainAdapter> {
        onMainActor { EventDomainAdapter() }.singleton
    }

    var deviceNetworkPath: Factory<any DeviceNetworkPath> {
        onMainActor { SystemDeviceNetworkPath() }.singleton
    }

    var foregroundEventStream: Factory<DefaultForegroundEventStream> {
        onMainActor {
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
        onMainActor {
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
        onMainActor {
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
        onMainActor {
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
        onMainActor {
            DefaultPermissionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
            )
        }.singleton
    }

    var questionLiveStore: Factory<DefaultQuestionLiveStore> {
        onMainActor {
            DefaultQuestionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
            )
        }.singleton
    }

    var sessionTodoLiveStore: Factory<DefaultSessionTodoLiveStore> {
        onMainActor {
            DefaultSessionTodoLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator()
            )
        }.singleton
    }

    var sessionReadyStore: Factory<DefaultSessionReadyStore> {
        onMainActor {
            DefaultSessionReadyStore(
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                eventStream: self.foregroundEventStream(),
                persistence: UserDefaultsSessionReadyPersistence()
            )
        }.singleton
    }

    var sessionLiveStore: Factory<DefaultSessionLiveStore> {
        onMainActor {
            let manager = self.activeConnectionManager()
            return DefaultSessionLiveStore(
                sessionRepository: self.sessionRepository(),
                eventStream: self.foregroundEventStream(),
                sessionCache: self.sessionCache(),
                worldSignals: self.worldSignals(),
                syncCoordinator: self.syncCoordinator(),
                capabilities: { manager.capabilities }
            )
        }.singleton
    }

    var sessionViewReporter: Factory<DefaultSessionViewReporter> {
        onMainActor {
            let manager = self.activeConnectionManager()
            return DefaultSessionViewReporter(
                sessionRepository: self.sessionRepository(),
                sessionLiveStore: self.sessionLiveStore(),
                eventStream: self.foregroundEventStream(),
                capabilities: { manager.capabilities }
            )
        }.singleton
    }

    var projectionCaches: Factory<ProjectionCaches> {
        onMainActor {
            ProjectionCaches(
                sessionLiveStore: self.sessionLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                permissionLiveStore: self.permissionLiveStore(),
                questionLiveStore: self.questionLiveStore(),
                sessionTodoLiveStore: self.sessionTodoLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                messageRepository: self.messageRepository(),
                modelCatalogStore: self.modelCatalogStore(),
                sessionViewReporter: self.sessionViewReporter()
            )
        }.singleton
    }

    var sessionContextRepository: Factory<any SessionContextRepository> {
        onMainActor {
            DefaultSessionContextRepository(
                sessionRepository: self.sessionRepository(),
                messageRepository: self.messageRepository(),
                modelRepository: self.modelRepository()
            )
        }.singleton
    }

    var appRootViewModel: Factory<AppRootViewModel> {
        onMainActor {
            AppRootViewModel(
                connectionProfileStore: self.connectionProfileStore(),
                activeConnectionManager: self.activeConnectionManager(),
                connectionSessionCoordinator: self.connectionSessionCoordinator(),
                worldSignals: self.worldSignals(),
                appOnboardingPreferences: AppOnboardingPreferences.shared,
                sessionChatDisplayPreferences: SessionChatDisplayPreferences.shared
            )
        }
    }

    var projectListHomeViewModel: Factory<ProjectListHomeViewModel> {
        onMainActor {
            ProjectListHomeViewModel(
                projectRepository: self.projectRepository(),
                filesystemRepository: self.filesystemRepository(),
                sessionRepository: self.sessionRepository(),
                messageRepository: self.messageRepository(),
                connectionProfileStore: self.connectionProfileStore(),
                activeConnectionManager: self.activeConnectionManager(),
                sessionLiveStore: self.sessionLiveStore(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                connectionSessionCoordinator: self.connectionSessionCoordinator(),
                sessionCache: self.sessionCache(),
                liveUpdates: self.liveUpdates(),
                worktreeRepository: self.worktreeRepository(),
                foregroundEventStream: self.foregroundEventStream(),
                worktreeInventoryCache: self.worktreeInventoryCache()
            )
        }
    }

    var lastTurnReviewChangesStore: Factory<LastTurnReviewChangesStore> {
        onMainActor { LastTurnReviewChangesStore() }.singleton
    }

    var sessionChatViewModel: ParameterFactory<(sessionID: String, sessionTitle: String, worktree: String), SessionChatViewModel> {
        onMainActor {
            SessionChatViewModel(
                sessionID: $0.sessionID,
                sessionTitle: $0.sessionTitle,
                worktree: $0.worktree,
                messageRepository: self.messageRepository(),
                modelCatalogStore: self.modelCatalogStore(),
                commandRepository: self.commandRepository(),
                agentRepository: self.agentRepository(),
                sessionRepository: self.sessionRepository(),
                permissionLiveStore: self.permissionLiveStore(),
                questionLiveStore: self.questionLiveStore(),
                sessionTodoLiveStore: self.sessionTodoLiveStore(),
                sessionReadyStore: self.sessionReadyStore(),
                sessionViewReporter: self.sessionViewReporter(),
                sessionStatusLiveStore: self.sessionStatusLiveStore(),
                foregroundEventStream: self.foregroundEventStream(),
                sessionModelSelectionStore: self.sessionModelSelectionStore(),
                composerDraftStore: self.composerDraftStore(),
                activeConnectionManager: self.activeConnectionManager(),
                connectionProfileStore: self.connectionProfileStore(),
                newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                    store: self.sessionModelSelectionStore(),
                    modelCatalogStore: self.modelCatalogStore()
                ),
                liveUpdates: self.liveUpdates(),
                lastTurnReviewChangesStore: self.lastTurnReviewChangesStore(),
                syncCoordinator: self.syncCoordinator(),
                mcpRepository: self.mcpRepository(),
                projectFilesRepository: self.projectFilesRepository(),
                worktreeRepository: self.worktreeRepository(),
                projectRepository: self.projectRepository(),
                worktreeInventoryCache: self.worktreeInventoryCache()
            )
        }
    }

    var sessionContextViewModel: ParameterFactory<(sessionID: String, worktree: String), SessionContextViewModel> {
        onMainActor {
            SessionContextViewModel(
                sessionID: $0.sessionID,
                worktree: $0.worktree,
                repository: self.sessionContextRepository()
            )
        }
    }

    var feedbackSubmitter: Factory<any FeedbackSubmitter> {
        onMainActor { OpenCodeReleaseObservability.makeFeedbackSubmitter() }.singleton
    }

    var feedbackViewModel: ParameterFactory<(screen: FeedbackScreen, screenshot: Data?), FeedbackViewModel> {
        onMainActor {
            FeedbackViewModel(
                screen: $0.screen,
                capturedScreenshot: $0.screenshot,
                submitter: self.feedbackSubmitter(),
                metadataProvider: DefaultFeedbackMetadataProvider(
                    activeConnectionManager: self.activeConnectionManager()
                ),
                cooldownStore: UserDefaultsFeedbackCooldownStore(),
                logRedactor: { [manager = self.activeConnectionManager()] in
                    FeedbackLogRedactor(profile: manager.currentProfile())
                }
            )
        }
    }

    var terminalViewModel: ParameterFactory<(worktree: String, ptyId: String?), TerminalViewModel> {
        onMainActor {
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
        onMainActor {
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
            pairingCodeRedeemer: PairingCodeRedeemer(),
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
    static func makeFeedbackViewModel(screen: FeedbackScreen, screenshot: Data?) -> FeedbackViewModel {
        shared.feedbackViewModel((screen: screen, screenshot: screenshot))
    }

    @MainActor
    static func makeTerminalViewModel(worktree: String, ptyId: String? = nil) -> TerminalViewModel {
        shared.terminalViewModel((worktree: worktree, ptyId: ptyId))
    }

    @MainActor
    static func makeFilesViewModel(worktree: String) -> FilesViewModel {
        FilesViewModel(worktree: worktree, repository: shared.projectFilesRepository())
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
            // Apply the reset default to Crashlytics and Analytics collection now, not at next launch.
            OpenCodeReleaseObservability.setProductAnalyticsConsented(
                AnalyticsConsentPreferences.shared.productAnalyticsConsented
            )
            AppLockPreferences.shared.clear()
            AppOnboardingPreferences.shared.clear()
            DeveloperToolsPreferences.shared.clear()
            PerformanceOverlayPreferences.shared.clear()
            SessionChatDisplayPreferences.shared.clear()
            SplitPanePreferences.shared.clear()
            FeatureFlags.shared.clearOverrides()
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
