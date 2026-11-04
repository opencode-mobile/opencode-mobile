package dev.opencode.mobile.domain.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** When a session was archived, as the Archived sessions list groups it. */
enum class ArchivedSessionPeriod {
    /** Archived in the last seven days. */
    ThisWeek,
    Earlier,
}

/**
 * Archived root sessions across Home's directories. Each directory is listed on its own, the way
 * Home lists its paths; Search's "Include archived" can reuse this.
 */
object ArchivedSessionList {
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Archived root sessions of [directories], one per id, most recently archived first. A
     * directory that fails is skipped while another answers; when none answers, the first error
     * is thrown.
     */
    suspend fun load(
        repository: SessionRepository,
        directories: Collection<String>,
    ): List<Session> {
        val results =
            coroutineScope {
                directories.distinct().map { directory ->
                    async {
                        try {
                            Result.success(repository.listArchivedSessions(directory))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                }.awaitAll()
            }
        if (results.isNotEmpty() && results.all { it.isFailure }) {
            throw results.first().exceptionOrNull()!!
        }
        return sorted(results.flatMap { it.getOrDefault(emptyList()) })
    }

    /** Root sessions that are archived, one per id, most recently archived first. */
    fun sorted(sessions: List<Session>): List<Session> =
        sessions
            .filter { it.isArchived && it.parentId == null }
            .sortedWith(compareByDescending<Session> { it.archivedAtEpochMs ?: 0L }.thenBy { it.id })
            // After sorting, so a session listed twice keeps its newest copy.
            .distinctBy { it.id }

    fun period(
        archivedAtEpochMs: Long,
        nowEpochMs: Long,
    ): ArchivedSessionPeriod = if (nowEpochMs - archivedAtEpochMs < WEEK_MS) ArchivedSessionPeriod.ThisWeek else ArchivedSessionPeriod.Earlier
}
