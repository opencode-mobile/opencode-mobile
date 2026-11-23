package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.message.AttachmentPreviewCache
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.session.NewSessionDraftStore
import dev.opencode.mobile.domain.session.SideQuestionStore
import dev.opencode.mobile.ui.sessionchat.LastTurnReviewChangesStore
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

    /** A removed profile: its Ready records and New session draft, with the draft's attachments. */
    fun forgetProfile(profileId: String) = Unit
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
        private val sideQuestionStore: SideQuestionStore,
        private val newSessionDraftStore: NewSessionDraftStore,
        private val lastTurnReviewChangesStore: LastTurnReviewChangesStore,
        private val eventDomainAdapter: EventDomainAdapter,
    ) : ProjectionCaches {
        @Volatile
        private var boundProfileId: String? = null

        override fun clearAll() {
            clearLiveProjections()
            sessionReadyStore.clearCache()
            newSessionDraftStore.clear()
            lastTurnReviewChangesStore.clear()
            AttachmentPreviewCache.shared.clear()
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
            sideQuestionStore.clear()
        }

        override fun bindProfile(profileId: String?) {
            // Not on a save of the same profile: its running compaction still reports its end.
            if (boundProfileId != null && boundProfileId != profileId) eventDomainAdapter.forgetRunningIds()
            boundProfileId = profileId
            sessionReadyStore.bindProfile(profileId)
        }

        override fun forgetProfile(profileId: String) {
            sessionReadyStore.forgetProfile(profileId)
            newSessionDraftStore.forgetProfile(profileId)
        }
    }
