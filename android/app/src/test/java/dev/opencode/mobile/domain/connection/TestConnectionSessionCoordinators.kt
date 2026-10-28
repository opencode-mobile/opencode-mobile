package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import dev.opencode.mobile.domain.live.NoOpProjectionCaches
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.NoOpSyncCoordinator
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient

fun testConnectionSessionCoordinator(
    activeConnectionManager: ActiveConnectionManager,
    connectionProfileStore: ConnectionProfileStore,
    secureCredentialStore: SecureCredentialStore,
    sessionModelSelectionStore: SessionModelSelectionStore,
    composerDraftStore: ComposerDraftStore,
    httpClient: OpencodeHttpClient,
    sessionCache: SessionCache = NoOpSessionCache,
    feedbackIdentityStore: FeedbackIdentityStore = InMemoryFeedbackIdentityStore(),
    sseHttpClient: OpencodeSseHttpClient,
    eventStream: ForegroundEventStream = FakeForegroundEventStream(),
    syncCoordinator: SyncCoordinator = NoOpSyncCoordinator,
    worldSignals: WorldSignals = FakeWorldSignals(),
    projectionCaches: ProjectionCaches = NoOpProjectionCaches,
): ConnectionSessionCoordinator =
    ConnectionSessionCoordinator(
        activeConnectionManager = activeConnectionManager,
        connectionProfileStore = connectionProfileStore,
        secureCredentialStore = secureCredentialStore,
        sessionModelSelectionStore = sessionModelSelectionStore,
        composerDraftStore = composerDraftStore,
        sessionCache = sessionCache,
        feedbackIdentityStore = feedbackIdentityStore,
        httpClient = httpClient,
        sseHttpClient = sseHttpClient,
        eventStream = eventStream,
        syncCoordinator = syncCoordinator,
        worldSignals = worldSignals,
        projectionCaches = projectionCaches,
    )

class InMemoryFeedbackIdentityStore : FeedbackIdentityStore {
    var token: String? = null

    override fun readRefreshToken(): String? = token

    override fun writeRefreshToken(token: String?) {
        this.token = token
    }
}
