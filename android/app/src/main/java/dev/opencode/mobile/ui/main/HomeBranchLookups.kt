package dev.opencode.mobile.ui.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Current branch per worktree directory, looked up only for directories something on screen
 * shows (#1124).
 *
 * Every directory the server is asked about makes it open a location (and start its MCP
 * servers), so nothing here sweeps an inventory. Each [Source] reports the directories it shows
 * with [show]; the lookups serve the union of those, at most [maxInFlight] at once, main
 * checkouts first. A directory nothing shows any more leaves the queue before its request
 * starts; a request already running finishes and its answer is kept.
 *
 * A decided answer (a branch, no branch, or a 4xx) stays until [refresh]. An unknown answer (VCS
 * detection still warming up, a failed request) keeps the last known branch and asks again after
 * each of [retryDelaysMs] while the directory is still shown; then it stays unknown until
 * [refresh]. Only the newest lookup of a directory may write it.
 */
internal class HomeBranchLookups(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> Outcome,
    private val onChange: (Map<String, String>) -> Unit,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
) {
    sealed interface Outcome {
        /** The server decided: the branch, or null for a detached HEAD or no branch. */
        data class Branch(
            val name: String?,
        ) : Outcome

        /** Not known yet: VCS detection still warming up, or the request failed. */
        data object Unknown : Outcome
    }

    /** Who shows a directory; a lower ordinal is asked first. */
    enum class Source {
        /** Each project's main checkout: v2 only knows a project is a repository from its branch. */
        MainCheckouts,

        /** Home session rows on screen. */
        HomeRows,

        /** The scope picker's listed worktrees, the dock, and the worktree tab. */
        Picker,

        /** Session Chat's worktree lists (Move, Run in, New session) while open. */
        SessionChat,
    }

    var branches: Map<String, String> = emptyMap()
        private set

    private val shownBy = mutableMapOf<Source, Set<String>>()

    /** Best (lowest) source per shown directory. */
    private var shown: Map<String, Source> = emptyMap()

    /** Directories asked since the last [refresh]: queued, running, waiting to retry, or decided. */
    private val requested = mutableSetOf<String>()

    /** Waiting for a free slot, with the attempt they make next; insertion order breaks ties. */
    private val queue = LinkedHashMap<String, Int>()

    /** Running requests and retry waits, by directory. */
    private val jobs = mutableMapOf<String, Job>()
    private val running = mutableSetOf<String>()
    private val generations = mutableMapOf<String, Int>()

    /** Directories the server answered since the last [clear]. */
    private val answered = mutableSetOf<String>()

    /**
     * Cached branches (a cold launch's Session Cache) for directories with no server answer yet,
     * so rows paint the branch the live answer will most likely repeat. Lookups still run when the
     * directory is shown; their answers overwrite these.
     */
    fun seed(cached: Map<String, String>) {
        val filled = cached.filterKeys { it !in answered && it !in branches }
        if (filled.isEmpty()) return
        branches = branches + filled
        onChange(branches)
    }

    /** [directories] are what [source] shows now, replacing what it showed before; empty withdraws it. */
    fun show(
        source: Source,
        directories: Collection<String>,
    ) {
        val next = directories.filterTo(LinkedHashSet()) { it.isNotBlank() }
        if (shownBy[source].orEmpty() == next) return
        if (next.isEmpty()) shownBy -= source else shownBy[source] = next
        shown =
            buildMap {
                for (each in Source.entries) {
                    shownBy[each].orEmpty().forEach { directory -> if (directory !in this) put(directory, each) }
                }
            }
        // Waiting directories nothing shows any more leave; a running request finishes.
        for (directory in requested.toList()) {
            if (directory in shown || directory in running) continue
            if (queue.remove(directory) != null || jobs.containsKey(directory)) {
                jobs.remove(directory)?.cancel()
                requested -= directory
            }
        }
        askShown()
    }

    /** Home refresh: every shown directory is asked again; known branches stay until answered. */
    fun refresh() {
        val pending = jobs.values.toList()
        jobs.clear()
        queue.clear()
        running.clear()
        requested.clear()
        pending.forEach { it.cancel() }
        askShown()
    }

    /**
     * Disconnect or profile switch: forgets everything. State resets before the jobs cancel,
     * because on an immediate dispatcher a cancelled job can resume inside [Job.cancel].
     */
    fun clear() {
        val pending = jobs.values.toList()
        jobs.clear()
        queue.clear()
        running.clear()
        requested.clear()
        generations.clear()
        answered.clear()
        shownBy.clear()
        shown = emptyMap()
        pending.forEach { it.cancel() }
        if (branches.isNotEmpty()) {
            branches = emptyMap()
            onChange(branches)
        }
    }

    private fun askShown() {
        for (directory in shown.keys) {
            if (directory in requested) continue
            requested += directory
            generations[directory] = (generations[directory] ?: 0) + 1
            queue[directory] = 0
        }
        pump()
    }

    /** Starts queued lookups while a slot is free, best source first. */
    private fun pump() {
        while (running.size < maxInFlight) {
            val next = queue.keys.minByOrNull { shown[it]?.ordinal ?: Int.MAX_VALUE } ?: return
            val attempt = queue.remove(next) ?: return
            run(next, attempt)
        }
    }

    private fun run(
        directory: String,
        attempt: Int,
    ) {
        val generation = generations[directory] ?: 0
        running += directory
        // Lazy, so the job is registered before it runs (an immediate dispatcher would finish it first).
        launchRegistered(directory) {
            val outcome =
                try {
                    fetch(directory)
                } finally {
                    if (generations[directory] == generation && running.remove(directory)) {
                        jobs.remove(directory)
                        pump()
                    }
                }
            if (generations[directory] != generation || directory !in requested) return@launchRegistered
            settle(directory, attempt, outcome)
        }
    }

    private fun launchRegistered(
        directory: String,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        jobs[directory] = job
        job.start()
    }

    private fun settle(
        directory: String,
        attempt: Int,
        outcome: Outcome,
    ) {
        if (outcome is Outcome.Branch) {
            store(directory, outcome.name)
            return
        }
        // Unknown: stays asked (so nothing re-asks it) until a retry or the next refresh.
        val wait = retryDelaysMs.getOrNull(attempt) ?: return
        val generation = generations[directory] ?: 0
        launchRegistered(directory) {
            delay(wait)
            if (generations[directory] != generation) return@launchRegistered
            jobs.remove(directory)
            if (directory in shown) {
                queue[directory] = attempt + 1
                pump()
            } else {
                requested -= directory
            }
        }
    }

    private fun store(
        directory: String,
        branch: String?,
    ) {
        answered += directory
        if (branches[directory] == branch) return
        branches = if (branch == null) branches - directory else branches + (directory to branch)
        onChange(branches)
    }

    companion object {
        /** About 45 s of retries, so a cold v2 location that is still detecting git settles. */
        val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 10_000L, 30_000L)

        /** Each lookup can make the server open a location, so only a few run together. */
        const val MAX_IN_FLIGHT = 3
    }
}
