package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.model.ModelCatalogStore
import javax.inject.Inject
import javax.inject.Singleton

interface ProjectionCaches {
    /** Everything, including persisted Ready records and the message cache. */
    fun clearAll()

    /**
     * Profile switch: drop live projections but keep parked Ready records for [bindProfile]
     * and persisted session lists / activity.
     */
    fun clearLiveProjections()

    fun bindProfile(profileId: String?)
}

@Singleton
class DefaultProjectionCaches
    @Inject
    constructor(
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val permissionLiveStore: PermissionLiveStore,
        private val questionLiveStore: QuestionLiveStore,
        private val sessionTodoLiveStore: SessionTodoLiveStore,
        private val sessionReadyStore: SessionReadyStore,
        private val sessionViewReporter: SessionViewReporter,
        private val messageSync: MessageSync,
        private val modelCatalogStore: ModelCatalogStore,
    ) : ProjectionCaches {
        override fun clearAll() {
            clearLiveProjections()
            sessionReadyStore.clearCache()
        }

        override fun clearLiveProjections() {
            sessionLiveStore.clearCache()
            sessionStatusLiveStore.clearCache()
            permissionLiveStore.clearCache()
            questionLiveStore.clearCache()
            sessionTodoLiveStore.clearCache()
            sessionViewReporter.clearCache()
            messageSync.clearCache()
            modelCatalogStore.clear()
        }

        override fun bindProfile(profileId: String?) {
            sessionReadyStore.bindProfile(profileId)
        }
    }
