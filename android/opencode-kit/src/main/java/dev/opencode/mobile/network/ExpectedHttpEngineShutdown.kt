package dev.opencode.mobile.network

import android.os.Looper
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import io.ktor.client.engine.ClientEngineClosedException
import io.ktor.utils.io.ClosedByteChannelException
import java.util.concurrent.RejectedExecutionException

/** Whether [this] or any cause or suppressed throwable matches. */
private fun Throwable.anyInChain(match: (Throwable) -> Boolean): Boolean {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque<Throwable>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!seen.add(current)) continue
        if (match(current)) return true
        current.cause?.let(queue::add)
        current.suppressed.forEach(queue::add)
    }
    return false
}

/** Ktor's own types for an [io.ktor.client.HttpClient] closed under a request: a closed engine or body channel. */
internal fun Throwable.isKtorEngineClosed(): Boolean = anyInChain { it is ClientEngineClosedException || it is ClosedByteChannelException }

/**
 * Whether the process-wide uncaught handler drops [throwable] instead of passing it on:
 * - never on the main thread, whose looper has already unwound;
 * - [isKtorEngineClosed] on any other thread, since Ktor's engine and writers run on the shared
 *   `Dispatchers.IO` pool;
 * - [RejectedExecutionException] only on a thread OkHttp owns (`OkHttp Dispatcher`,
 *   `OkHttp <url>`), so an app pool's rejection still crashes.
 *
 * Plain socket errors and timeouts are never dropped: OkHttp hands them to the caller's
 * coroutine, which owns retrying them.
 */
internal fun shouldSwallowUncaught(
    threadName: String,
    isMainThread: Boolean,
    throwable: Throwable,
): Boolean =
    when {
        isMainThread -> false
        throwable.isKtorEngineClosed() -> true
        else -> threadName.startsWith(OKHTTP_THREAD_PREFIX) && throwable.anyInChain { it is RejectedExecutionException }
    }

private const val OKHTTP_THREAD_PREFIX = "OkHttp"

internal object HttpEngineShutdownGuard {
    private val installer =
        Installer(
            current = Thread::getDefaultUncaughtExceptionHandler,
            replace = Thread::setDefaultUncaughtExceptionHandler,
        )

    fun install() = installer.install()

    /** Wraps the default uncaught handler once; [current] and [replace] are the JVM's in production. */
    internal class Installer(
        private val current: () -> Thread.UncaughtExceptionHandler?,
        private val replace: (Thread.UncaughtExceptionHandler) -> Unit,
        private val isMainThread: (Thread) -> Boolean = ::isAndroidMainThread,
        private val log: (Throwable) -> Unit = ::logSwallowed,
    ) {
        @Volatile
        private var installed = false

        fun install() {
            if (installed) return
            synchronized(this) {
                if (installed) return
                replace(handler(current(), isMainThread, log))
                installed = true
            }
        }
    }

    internal fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        isMainThread: (Thread) -> Boolean,
        log: (Throwable) -> Unit,
    ): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, throwable ->
            if (shouldSwallowUncaught(thread.name, isMainThread(thread), throwable)) {
                log(throwable)
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }

    private fun isAndroidMainThread(thread: Thread): Boolean =
        runCatching { Looper.getMainLooper() }.getOrNull()?.let { it.thread === thread } ?: (thread.name == "main")

    private fun logSwallowed(throwable: Throwable) {
        DiagnosticLog.warning(
            DiagnosticLogArea.Connection,
            "HTTP engine closed under in-flight request",
            throwable,
        )
    }
}
