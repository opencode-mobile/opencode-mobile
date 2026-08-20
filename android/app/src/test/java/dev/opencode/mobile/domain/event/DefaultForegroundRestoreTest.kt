package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptSendOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultForegroundRestoreTest {
    @Test
    fun restore_startsStreamAndRefreshesOpenSession() =
        runBlocking {
            val stream = FakeForegroundEventStream()
            val messages = FakeMessageRepository()
            val home = RecordingHomeCatchUp()
            val restore =
                DefaultForegroundRestore(
                    stream = stream,
                    messageRepository = messages,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    homeCatchUp = home,
                )
            restore.setOpenSession("ses-1", "/tmp")
            restore.setHomeVisible(true)

            restore.restore()
            withTimeout(2_000) {
                while (restore.phase != ForegroundRestorePhase.Live) {
                    delay(10)
                }
            }

            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertEquals(listOf("ses-1"), messages.refreshedSessions)
            assertEquals(1, home.catchUpCount)
            assertEquals(ForegroundRestorePhase.Live, restore.phase)
        }

    @Test
    fun noteBackgroundStop_stopsStream() {
        val stream = FakeForegroundEventStream()
        val restore =
            DefaultForegroundRestore(
                stream = stream,
                messageRepository = FakeMessageRepository(),
                diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                homeCatchUp = RecordingHomeCatchUp(),
            )
        stream.start()
        restore.noteBackgroundStop()
        assertEquals(EventStreamConnectionState.Idle, stream.connectionState.value)
        assertEquals(ForegroundRestorePhase.Idle, restore.phase)
    }

    @Test
    fun restore_withoutScopes_stillStartsStream() =
        runBlocking {
            val stream = FakeForegroundEventStream()
            val restore =
                DefaultForegroundRestore(
                    stream = stream,
                    messageRepository = FakeMessageRepository(),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    homeCatchUp = RecordingHomeCatchUp(),
                )
            restore.restore()
            withTimeout(2_000) {
                while (restore.phase != ForegroundRestorePhase.Live) {
                    delay(10)
                }
            }
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertTrue(restore.phase == ForegroundRestorePhase.Live)
        }

    @Test
    fun restore_skipsRestWhenStreamStillLiveAndNoBackgroundStop() {
        val stream = FakeForegroundEventStream()
        val messages = FakeMessageRepository()
        val restore =
            DefaultForegroundRestore(
                stream = stream,
                messageRepository = messages,
                diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                homeCatchUp = RecordingHomeCatchUp(),
            )
        restore.setOpenSession("ses-1", "/tmp")
        // First restore (idle) catches up and starts stream.
        restore.restore()
        runBlocking {
            withTimeout(2_000) {
                while (restore.phase != ForegroundRestorePhase.Live) {
                    delay(10)
                }
            }
        }
        messages.refreshedSessions.clear()
        // Active again without background stop — start only, no second REST.
        restore.restore()
        assertEquals(ForegroundRestorePhase.Live, restore.phase)
        assertTrue(messages.refreshedSessions.isEmpty())
    }

    @Test
    fun noteBackground_thenRestore_refreshesEvenIfStreamStillLive() =
        runBlocking {
            val stream = FakeForegroundEventStream()
            val messages = FakeMessageRepository()
            val restore =
                DefaultForegroundRestore(
                    stream = stream,
                    messageRepository = messages,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    homeCatchUp = RecordingHomeCatchUp(),
                )
            restore.setOpenSession("ses-1", "/tmp")
            restore.restore()
            withTimeout(2_000) {
                while (restore.phase != ForegroundRestorePhase.Live) {
                    delay(10)
                }
            }
            messages.refreshedSessions.clear()
            restore.noteBackground()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)

            restore.restore()
            withTimeout(2_000) {
                while (messages.refreshedSessions.isEmpty()) {
                    delay(10)
                }
            }
            assertEquals(listOf("ses-1"), messages.refreshedSessions)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
        }

    @Test
    fun restore_startOnlyDoesNotCancelInFlightCatchUp() =
        runBlocking {
            val stream = FakeForegroundEventStream()
            val messages = FakeMessageRepository(refreshDelayMs = 150)
            val restore =
                DefaultForegroundRestore(
                    stream = stream,
                    messageRepository = messages,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    homeCatchUp = RecordingHomeCatchUp(),
                )
            restore.setOpenSession("ses-1", "/tmp")
            restore.restore()
            delay(20)
            restore.restore()
            withTimeout(2_000) {
                while (messages.completedSessions.isEmpty() || restore.phase != ForegroundRestorePhase.Live) {
                    delay(10)
                }
            }
            assertEquals(listOf("ses-1"), messages.completedSessions)
        }

    @Test
    fun setOpenSession_afterRestoreWithoutSession_refreshesThatSession() =
        runBlocking {
            val stream = FakeForegroundEventStream()
            val messages = FakeMessageRepository()
            val restore =
                DefaultForegroundRestore(
                    stream = stream,
                    messageRepository = messages,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    homeCatchUp = RecordingHomeCatchUp(),
                )
            restore.restore()
            withTimeout(2_000) {
                while (restore.phase != ForegroundRestorePhase.Live) {
                    delay(10)
                }
            }
            assertTrue(messages.refreshedSessions.isEmpty())

            restore.setOpenSession("ses-1", "/tmp")
            withTimeout(2_000) {
                while (messages.refreshedSessions.isEmpty()) {
                    delay(10)
                }
            }
            assertEquals(listOf("ses-1"), messages.refreshedSessions)
        }

    private class RecordingHomeCatchUp : ForegroundHomeCatchUp {
        var catchUpCount = 0
            private set

        override suspend fun catchUp() {
            catchUpCount += 1
        }
    }

    private class FakeMessageRepository(
        private val refreshDelayMs: Long = 0,
    ) : MessageRepository {
        val refreshedSessions = mutableListOf<String>()
        val completedSessions = mutableListOf<String>()

        override fun observeMessages(
            sessionId: String,
            directory: String?,
        ): Flow<MessageListUpdate> = emptyFlow()

        override fun observeDisplayMessages(
            sessionId: String,
            directory: String?,
        ): Flow<DisplayMessageListUpdate> = emptyFlow()

        override suspend fun refreshMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = emptyList()

        override suspend fun refreshDisplayMessages(
            sessionId: String,
            directory: String?,
        ): List<DisplayMessage> {
            refreshedSessions += sessionId
            if (refreshDelayMs > 0) {
                delay(refreshDelayMs)
            }
            completedSessions += sessionId
            return emptyList()
        }

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
        ): DisplayMessage = error("unused")

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
            attachments: List<Attachment>,
        ): DisplayMessage = error("unused")

        override fun clearMessageCache() = Unit

        override suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = emptyList()

        override suspend fun getMessage(
            sessionId: String,
            messageId: String,
            directory: String?,
        ): Message = error("unused")

        override suspend fun sendMessage(
            sessionId: String,
            text: String,
            directory: String?,
        ): Message = error("unused")

        override suspend fun sendPromptAsync(
            sessionId: String,
            options: PromptSendOptions,
            directory: String?,
        ) = Unit
    }
}
