package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.live.SessionListSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** First server load of a project added on Home in this session, while it is still unfinished. */
sealed interface ProjectFirstLoad {
    data class Loading(
        val isSlow: Boolean = false,
    ) : ProjectFirstLoad

    data class Failed(
        val message: String,
    ) : ProjectFirstLoad

    companion object {
        const val FAILED_MESSAGE = "Couldn't load sessions"
    }
}

/**
 * Anti-flicker and give-up timing for [ProjectFirstLoad]. Loading shows only after [graceMs] and
 * then stays at least [minimumVisibleMs]; it turns slow after [slowAfterMs] and fails after
 * [timeoutMs] with no outcome.
 */
data class ProjectFirstLoadTiming(
    val graceMs: Long,
    val minimumVisibleMs: Long,
    val slowAfterMs: Long,
    val timeoutMs: Long,
) {
    @Inject
    constructor() : this(
        graceMs = DEFAULT_GRACE_MS,
        minimumVisibleMs = DEFAULT_MINIMUM_VISIBLE_MS,
        slowAfterMs = DEFAULT_SLOW_AFTER_MS,
        timeoutMs = DEFAULT_TIMEOUT_MS,
    )

    companion object {
        const val DEFAULT_GRACE_MS = 300L
        const val DEFAULT_MINIMUM_VISIBLE_MS = 500L
        const val DEFAULT_SLOW_AFTER_MS = 4_000L
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * Tracks the first server session-list load of newly added projects. Reads the session-list
 * stream directly (not the combined sessions + status + ready stream, which waits for all
 * three), so a server snapshot, including an empty one, always ends loading.
 */
internal class HomeProjectFirstLoads(
    private val scope: CoroutineScope,
    private val timing: ProjectFirstLoadTiming,
    private val observeSessions: (String) -> Flow<SessionListSnapshot>,
    private val refetchSessions: suspend (String) -> Unit,
    /** First server snapshot of a tracked project, delivered before its load completes. */
    private val onServerSnapshot: (String, SessionListSnapshot) -> Unit,
    private val onChange: (Map<String, ProjectFirstLoad>) -> Unit,
) {
    private val trackers = mutableMapOf<String, Tracker>()

    /** What Home shows: only loads past the grace delay, and failures. */
    var visible: Map<String, ProjectFirstLoad> = emptyMap()
        private set

    fun start(worktree: String) {
        // A project already loading from [awaitServer] keeps its skeleton, with no grace delay.
        val showing = visible[worktree] is ProjectFirstLoad.Loading && worktree !in trackers
        trackers.remove(worktree)?.cancel()
        if (!showing) publish(visible - worktree)
        val tracker = Tracker(worktree)
        trackers[worktree] = tracker
        tracker.begin(showImmediately = showing)
        tracker.observe()
    }

    /**
     * Shows loading for [worktrees] at once, before their server is bound (a server switch).
     * Nothing observes them yet; [startAwaiting] does once the switch has bound the server.
     */
    fun awaitServer(worktrees: Collection<String>) {
        publish(visible + worktrees.associateWith { ProjectFirstLoad.Loading() })
    }

    /** Starts tracking every project [awaitServer] put in loading. */
    fun startAwaiting() {
        visible
            .filter { (worktree, load) -> load is ProjectFirstLoad.Loading && worktree !in trackers }
            .keys
            .forEach(::start)
    }

    fun retry(worktree: String) {
        val tracker = trackers[worktree] ?: return
        tracker.begin(showImmediately = true)
        tracker.requestRefetch()
    }

    fun clear(worktree: String) {
        trackers.remove(worktree)?.cancel()
        publish(visible - worktree)
    }

    fun clearAll() {
        trackers.values.forEach { it.cancel() }
        trackers.clear()
        publish(emptyMap())
    }

    private fun publish(next: Map<String, ProjectFirstLoad>) {
        if (next == visible) return
        visible = next
        onChange(next)
    }

    private inner class Tracker(
        private val worktree: String,
    ) {
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val trackerScope = CoroutineScope(scope.coroutineContext + job)
        private var timers: Job? = null
        private var minimumVisible: Job? = null
        private var finishing: Job? = null
        private var isSlow = false

        fun begin(showImmediately: Boolean) {
            finishing?.cancel()
            finishing = null
            timers?.cancel()
            isSlow = false
            if (showImmediately) showLoading()
            timers =
                trackerScope.launch {
                    launch {
                        delay(timing.slowAfterMs)
                        isSlow = true
                        if (visible[worktree] is ProjectFirstLoad.Loading) {
                            publish(visible + (worktree to ProjectFirstLoad.Loading(isSlow = true)))
                        }
                    }
                    launch {
                        delay(timing.timeoutMs)
                        fail()
                    }
                    if (!showImmediately) {
                        delay(timing.graceMs)
                        showLoading()
                    }
                }
        }

        fun observe() {
            trackerScope.launch {
                observeSessions(worktree).collect { snapshot ->
                    when {
                        snapshot.loadFailed -> fail()
                        !snapshot.isFromCache -> {
                            if (finishing == null) onServerSnapshot(worktree, snapshot)
                            loaded()
                        }
                    }
                }
            }
        }

        fun requestRefetch() {
            trackerScope.launch { refetchSessions(worktree) }
        }

        fun cancel() {
            job.cancel()
        }

        private fun showLoading() {
            publish(visible + (worktree to ProjectFirstLoad.Loading(isSlow = isSlow)))
            minimumVisible?.cancel()
            minimumVisible = trackerScope.launch { delay(timing.minimumVisibleMs) }
        }

        private fun fail() {
            timers?.cancel()
            finishing?.cancel()
            finishing = null
            minimumVisible?.cancel()
            publish(visible + (worktree to ProjectFirstLoad.Failed(ProjectFirstLoad.FAILED_MESSAGE)))
        }

        private fun loaded() {
            timers?.cancel()
            if (finishing != null) return
            val hold = minimumVisible
            if (visible[worktree] is ProjectFirstLoad.Loading && hold != null && hold.isActive) {
                finishing =
                    trackerScope.launch {
                        hold.join()
                        finish()
                    }
            } else {
                finish()
            }
        }

        private fun finish() {
            if (trackers[worktree] === this) trackers.remove(worktree)
            publish(visible - worktree)
            job.cancel()
        }
    }
}
