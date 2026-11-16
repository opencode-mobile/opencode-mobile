package dev.opencode.mobile.network

import io.ktor.client.engine.ClientEngineClosedException
import io.ktor.utils.io.ClosedByteChannelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException

class ExpectedHttpEngineShutdownTest {
    private val engineThread = Thread({}, "OkHttp Dispatcher")
    private val mainThread = Thread({}, "main")
    private val appThread = Thread({}, "DefaultDispatcher-worker-3")

    private class Recorder : Thread.UncaughtExceptionHandler {
        val received = mutableListOf<Pair<Thread, Throwable>>()

        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable,
        ) {
            received += thread to throwable
        }
    }

    private fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        logged: MutableList<Throwable> = mutableListOf(),
    ) = HttpEngineShutdownGuard.handler(previous, isMainThread = { it === mainThread }, log = { logged += it })

    private fun swallows(
        threadName: String,
        throwable: Throwable,
        isMainThread: Boolean = false,
    ) = shouldSwallowUncaught(threadName, isMainThread, throwable)

    @Test
    fun classifiesKtorEngineClose() {
        assertTrue(ClientEngineClosedException().isKtorEngineClosed())
        assertTrue(ClosedByteChannelException().isKtorEngineClosed())
        assertTrue(RuntimeException(ClientEngineClosedException()).isKtorEngineClosed())
        assertTrue(IllegalStateException("outer").apply { addSuppressed(ClosedByteChannelException()) }.isKtorEngineClosed())
        assertFalse(RejectedExecutionException("terminated").isKtorEngineClosed())
        assertFalse(SocketException("Software caused connection abort").isKtorEngineClosed())
        assertFalse(IllegalStateException("bug").isKtorEngineClosed())
    }

    @Test
    fun mainThreadAlwaysForwards() {
        listOf(
            ClientEngineClosedException(),
            ClosedByteChannelException(),
            RejectedExecutionException("terminated"),
            SocketException("abort"),
        ).forEach { throwable ->
            assertFalse(swallows("main", throwable, isMainThread = true))
            assertFalse(swallows("OkHttp Dispatcher", throwable, isMainThread = true))
        }
    }

    @Test
    fun ktorEngineCloseIsSwallowedOnAnyOtherThread() {
        listOf("DefaultDispatcher-worker-1", "OkHttp Dispatcher", "OkHttp https://example.com/...").forEach { name ->
            assertTrue(swallows(name, ClientEngineClosedException()))
            assertTrue(swallows(name, ClosedByteChannelException()))
            assertTrue(swallows(name, RuntimeException(ClientEngineClosedException())))
        }
    }

    @Test
    fun rejectedExecutionIsSwallowedOnlyOnOkHttpThreads() {
        assertTrue(swallows("OkHttp Dispatcher", RejectedExecutionException("terminated")))
        assertTrue(swallows("OkHttp https://example.com/...", RejectedExecutionException("terminated")))
        // OkHttp's shape for an enqueue on a shut-down dispatcher.
        assertTrue(
            swallows(
                "OkHttp Dispatcher",
                InterruptedIOException("executor rejected").apply { initCause(RejectedExecutionException("terminated")) },
            ),
        )
        assertFalse(swallows("DefaultDispatcher-worker-1", RejectedExecutionException("app pool")))
        assertFalse(swallows("app-pool-1", RejectedExecutionException("app pool")))
    }

    @Test
    fun rejectedExecutionCarryingAnEngineCloseIsSwallowedOnAWorker() {
        val rejected = RejectedExecutionException("terminated", ClientEngineClosedException())
        assertTrue(swallows("DefaultDispatcher-worker-1", rejected))
    }

    @Test
    fun plainSocketFailuresAreAlwaysForwarded() {
        listOf(
            SocketException("Software caused connection abort"),
            SocketTimeoutException("timeout"),
            InterruptedIOException("timeout"),
            RuntimeException(SocketException("abort")),
        ).forEach { throwable ->
            assertFalse(swallows("DefaultDispatcher-worker-1", throwable))
            assertFalse(swallows("OkHttp Dispatcher", throwable))
            assertFalse(swallows("OkHttp https://example.com/...", throwable))
        }
    }

    @Test
    fun otherFailuresAreForwardedEvenOnOkHttpThreads() {
        assertFalse(swallows("OkHttp Dispatcher", IllegalStateException("bug")))
    }

    @Test
    fun engineCloseOnAWorkerThreadIsLoggedNotForwarded() {
        val previous = Recorder()
        val logged = mutableListOf<Throwable>()
        val closed = ClientEngineClosedException()
        handler(previous, logged).uncaughtException(appThread, closed)
        assertEquals(listOf<Throwable>(closed), logged)
        assertTrue(previous.received.isEmpty())
    }

    @Test
    fun engineCloseOnAnEngineThreadIsLoggedNotForwarded() {
        val previous = Recorder()
        val logged = mutableListOf<Throwable>()
        val closed = ClientEngineClosedException()
        handler(previous, logged).uncaughtException(engineThread, closed)
        assertEquals(listOf<Throwable>(closed), logged)
        assertTrue(previous.received.isEmpty())
    }

    @Test
    fun engineCloseOnTheMainThreadIsForwarded() {
        val previous = Recorder()
        val logged = mutableListOf<Throwable>()
        val closed = ClientEngineClosedException()
        handler(previous, logged).uncaughtException(mainThread, closed)
        assertEquals(listOf(mainThread to closed), previous.received)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun socketTimeoutFromAnAppThreadIsForwarded() {
        val previous = Recorder()
        val timeout = SocketTimeoutException("timeout")
        handler(previous).uncaughtException(appThread, timeout)
        assertEquals(listOf<Pair<Thread, Throwable>>(appThread to timeout), previous.received)
    }

    @Test
    fun rejectedExecutionFromANonEngineThreadIsForwarded() {
        val previous = Recorder()
        val rejected = RejectedExecutionException("app pool")
        handler(previous).uncaughtException(appThread, rejected)
        assertEquals(listOf<Pair<Thread, Throwable>>(appThread to rejected), previous.received)
    }

    @Test
    fun installWrapsThePreviousHandlerOnce() {
        val previous = Recorder()
        var installed: Thread.UncaughtExceptionHandler? = previous
        var replacements = 0
        val installer =
            HttpEngineShutdownGuard.Installer(
                current = { installed },
                replace = {
                    installed = it
                    replacements += 1
                },
                isMainThread = { it === mainThread },
                log = {},
            )
        installer.install()
        val guard = installed
        installer.install()
        assertEquals(1, replacements)
        assertSame(guard, installed)

        val bug = IllegalStateException("bug")
        guard!!.uncaughtException(appThread, bug)
        assertEquals(listOf<Pair<Thread, Throwable>>(appThread to bug), previous.received)
    }
}
