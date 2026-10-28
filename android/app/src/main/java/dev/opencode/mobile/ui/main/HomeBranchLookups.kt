package dev.opencode.mobile.ui.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Current branch per worktree directory for Home rows, loaded behind the session list.
 *
 * Only the newest lookup of a directory may write it, so a lookup that started before a Home
 * reload (and answers late) never overwrites a newer answer. An unknown answer (the server is
 * still detecting the VCS, or the request failed) keeps the last known branch and asks again
 * after each of [retryDelaysMs]; then the directory waits for the next [lookUp] after a
 * [refresh] or an inventory change, so a folder without git stops asking.
 */
internal class HomeBranchLookups(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> Outcome,
    private val onChange: (Map<String, String>) -> Unit,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    sealed interface Outcome {
        /** The server decided: the branch, or null for a detached HEAD or no branch. */
        data class Branch(
            val name: String?,
        ) : Outcome

        /** Not known yet: VCS detection still warming up, or the request failed. */
        data object Unknown : Outcome
    }

    var branches: Map<String, String> = emptyMap()
        private set

    private val requested = mutableSetOf<String>()
    private val generations = mutableMapOf<String, Int>()
    private val jobs = mutableMapOf<String, Job>()

    /** Asks for every directory not already answered or in flight since the last [refresh]. */
    fun lookUp(directories: Collection<String>) {
        for (directory in directories) {
            if (directory !in requested) start(directory)
        }
    }

    /** Home refresh: the next [lookUp] asks every directory again; known branches stay until answered. */
    fun refresh() {
        requested.clear()
    }

    /** Disconnect or profile switch: forgets everything. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        requested.clear()
        generations.clear()
        if (branches.isNotEmpty()) {
            branches = emptyMap()
            onChange(branches)
        }
    }

    private fun start(directory: String) {
        requested += directory
        val generation = (generations[directory] ?: 0) + 1
        generations[directory] = generation
        jobs.remove(directory)?.cancel()
        jobs[directory] =
            scope.launch {
                var attempt = 0
                while (true) {
                    val outcome = fetch(directory)
                    if (generations[directory] != generation) return@launch
                    if (outcome is Outcome.Branch) {
                        store(directory, outcome.name)
                        jobs.remove(directory)
                        return@launch
                    }
                    val wait = retryDelaysMs.getOrNull(attempt)
                    if (wait == null) {
                        // Unknown for now; the next lookUp after a refresh or inventory change asks again.
                        requested -= directory
                        jobs.remove(directory)
                        return@launch
                    }
                    attempt += 1
                    if (wait > 0) delay(wait) else yield()
                }
            }
    }

    private fun store(
        directory: String,
        branch: String?,
    ) {
        if (branches[directory] == branch) return
        branches = if (branch == null) branches - directory else branches + (directory to branch)
        onChange(branches)
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)
    }
}
