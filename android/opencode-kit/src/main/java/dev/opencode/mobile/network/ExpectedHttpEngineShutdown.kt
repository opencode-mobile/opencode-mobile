package dev.opencode.mobile.network

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import io.ktor.client.engine.ClientEngineClosedException
import io.ktor.utils.io.ClosedByteChannelException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.RejectedExecutionException

/**
 * OkHttp/Ktor can throw on a [kotlinx.coroutines.GlobalScope] writer or a
 * terminated dispatcher when an [io.ktor.client.HttpClient] is closed under
 * an in-flight request. Those are reconnectable network closes, not app bugs.
 */
internal fun Throwable.isExpectedHttpEngineShutdown(): Boolean {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque<Throwable>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!seen.add(current)) continue
        when (current) {
            is ClientEngineClosedException,
            is RejectedExecutionException,
            is ClosedByteChannelException,
            is SocketException,
            is InterruptedIOException,
            -> return true
        }
        current.cause?.let(queue::add)
        current.suppressed.forEach(queue::add)
    }
    return false
}

internal object HttpEngineShutdownGuard {
    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (throwable.isExpectedHttpEngineShutdown()) {
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Connection,
                        "HTTP engine closed under in-flight request",
                        throwable,
                    )
                    return@setDefaultUncaughtExceptionHandler
                }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }
}
