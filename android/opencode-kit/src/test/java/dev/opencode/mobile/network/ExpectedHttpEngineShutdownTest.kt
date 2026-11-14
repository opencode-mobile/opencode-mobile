package dev.opencode.mobile.network

import io.ktor.client.engine.ClientEngineClosedException
import io.ktor.utils.io.ClosedByteChannelException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.RejectedExecutionException

class ExpectedHttpEngineShutdownTest {
    @Test
    fun classifiesEngineCloseAndSocketAbort() {
        assertTrue(ClientEngineClosedException().isExpectedHttpEngineShutdown())
        assertTrue(RejectedExecutionException("terminated").isExpectedHttpEngineShutdown())
        assertTrue(ClosedByteChannelException().isExpectedHttpEngineShutdown())
        assertTrue(SocketException("Software caused connection abort").isExpectedHttpEngineShutdown())
        assertTrue(RuntimeException(SocketException("abort")).isExpectedHttpEngineShutdown())
        assertFalse(IllegalStateException("bug").isExpectedHttpEngineShutdown())
    }
}
