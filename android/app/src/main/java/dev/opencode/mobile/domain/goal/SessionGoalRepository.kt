package dev.opencode.mobile.domain.goal

import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A session's goal: read once, then followed from [changes]. Only v2 servers with the goal
 * plugin have goals; elsewhere [isAvailable] is false and nothing is fetched.
 */
interface SessionGoalRepository {
    /** The server speaks v2, so a goal plugin may answer. */
    suspend fun isAvailable(): Boolean

    /** The session's goal, or null without one. Throws when the RPC fails (no plugin, offline). */
    suspend fun goal(
        sessionId: String,
        directory: String?,
    ): SessionGoal?

    /** Sends `/goal pause|resume|clear`. Throws with the command's error. */
    suspend fun send(
        sessionId: String,
        action: SessionGoalAction,
    )

    /** Live goal changes, for every session, while the event stream runs. */
    fun changes(): Flow<SessionGoalChange>
}

/** No goals (previews and tests that do not pass one). */
object EmptySessionGoalRepository : SessionGoalRepository {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun goal(
        sessionId: String,
        directory: String?,
    ): SessionGoal? = null

    override suspend fun send(
        sessionId: String,
        action: SessionGoalAction,
    ) = Unit

    override fun changes(): Flow<SessionGoalChange> = emptyFlow()
}

@Singleton
class DefaultSessionGoalRepository
    @Inject
    constructor(
        private val api: SessionsApi,
        private val eventStream: ForegroundEventStream,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : SessionGoalRepository {
        override suspend fun isAvailable(): Boolean = activeConnectionManager.currentProtocolOrProbe() == ServerProtocol.V2

        override suspend fun goal(
            sessionId: String,
            directory: String?,
        ): SessionGoal? {
            check(isAvailable()) { "Goals need a v2 server" }
            return SessionGoalDomainAdapter.fromState(api.goalV2(sessionId, directory))
        }

        override suspend fun send(
            sessionId: String,
            action: SessionGoalAction,
        ) {
            check(isAvailable()) { "Goals need a v2 server" }
            api.runSessionCommandV2(sessionId, SessionGoalLimits.COMMAND, action.commandText)
        }

        override fun changes(): Flow<SessionGoalChange> = eventStream.events.mapNotNull(SessionGoalDomainAdapter::change)
    }
