import Foundation

@MainActor
protocol ProjectionCaching: AnyObject {
    func clearAll()
    func clearLiveProjections()
}

/// One place to drop live projections on profile switch or Settings "Clear cache".
@MainActor
final class ProjectionCaches: ProjectionCaching {
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let permissionLiveStore: any PermissionLiveStore
    private let questionLiveStore: any QuestionLiveStore
    private let sessionTodoLiveStore: any SessionTodoLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let messageRepository: any MessageRepository
    private let modelCatalogStore: ModelCatalogStore
    private let sessionViewReporter: (any SessionViewReporter)?

    init(
        sessionLiveStore: any SessionLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        permissionLiveStore: any PermissionLiveStore,
        questionLiveStore: any QuestionLiveStore,
        sessionTodoLiveStore: any SessionTodoLiveStore,
        sessionReadyStore: any SessionReadyStore,
        messageRepository: any MessageRepository,
        modelCatalogStore: ModelCatalogStore,
        sessionViewReporter: (any SessionViewReporter)? = nil
    ) {
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.permissionLiveStore = permissionLiveStore
        self.questionLiveStore = questionLiveStore
        self.sessionTodoLiveStore = sessionTodoLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.messageRepository = messageRepository
        self.modelCatalogStore = modelCatalogStore
        self.sessionViewReporter = sessionViewReporter
    }

    func clearAll() {
        sessionLiveStore.clearCache()
        sessionStatusLiveStore.clearCache()
        permissionLiveStore.clearCache()
        questionLiveStore.clearCache()
        sessionTodoLiveStore.clearCache()
        sessionReadyStore.clearCache()
        sessionViewReporter?.clearCache()
        messageRepository.clearMessageCache()
        modelCatalogStore.clearCache()
    }

    /// Profile switch: keep parked Ready records for bindProfile to reload.
    func clearLiveProjections() {
        sessionLiveStore.clearCache()
        sessionStatusLiveStore.clearCache()
        permissionLiveStore.clearCache()
        questionLiveStore.clearCache()
        sessionTodoLiveStore.clearCache()
        sessionViewReporter?.clearCache()
        messageRepository.clearMessageCache()
        modelCatalogStore.clearCache()
    }
}
