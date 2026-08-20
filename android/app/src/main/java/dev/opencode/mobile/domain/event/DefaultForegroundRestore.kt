package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.message.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultForegroundRestore
    @Inject
    constructor(
        private val stream: ForegroundEventStream,
        private val messageRepository: MessageRepository,
        private val diagnosticLogger: DiagnosticLogger,
        private val homeCatchUp: ForegroundHomeCatchUp,
    ) : ForegroundRestore {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val generation = AtomicLong(0)
        private val restoreJob = AtomicReference<Job?>(null)
        private val openSession = AtomicReference<OpenSession?>(null)
        private val homeVisible = AtomicReference(false)

        @Volatile
        private var needsCatchUp = true

        @Volatile
        var phase: ForegroundRestorePhase = ForegroundRestorePhase.Idle
            private set

        override fun setOpenSession(
            sessionId: String?,
            directory: String?,
        ) {
            openSession.set(
                if (sessionId.isNullOrEmpty()) {
                    null
                } else {
                    OpenSession(sessionId, directory)
                },
            )
        }

        override fun setHomeVisible(visible: Boolean) {
            homeVisible.set(visible)
        }

        override fun noteBackgroundStop() {
            generation.incrementAndGet()
            restoreJob.getAndSet(null)?.cancel()
            needsCatchUp = true
            phase = ForegroundRestorePhase.Idle
            stream.stop()
        }

        override fun restore() {
            val gen = generation.incrementAndGet()
            restoreJob.getAndSet(null)?.cancel()
            phase = ForegroundRestorePhase.Restoring
            val session = openSession.get()
            val refreshHome = homeVisible.get()
            val catchUp =
                needsCatchUp || stream.connectionState.value == EventStreamConnectionState.Idle
            needsCatchUp = false
            diagnosticLogger.info(
                DiagnosticLogArea.Sse,
                "foreground restore starting generation=$gen catchUp=$catchUp",
            )
            stream.start()
            if (!catchUp) {
                phase = ForegroundRestorePhase.Live
                return
            }
            val job =
                scope.launch {
                    var failed = false
                    if (session != null) {
                        try {
                            messageRepository.refreshDisplayMessages(session.sessionId, session.directory)
                            diagnosticLogger.info(
                                DiagnosticLogArea.Sse,
                                "foreground restore messages session=${session.sessionId}",
                            )
                        } catch (error: Throwable) {
                            failed = true
                            diagnosticLogger.warning(
                                DiagnosticLogArea.Sse,
                                "foreground restore messages failed session=${session.sessionId}",
                                error,
                            )
                        }
                    }
                    if (refreshHome) {
                        homeCatchUp.catchUp()
                        diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground restore home catch-up")
                    }
                    if (gen != generation.get()) return@launch
                    phase = if (failed) ForegroundRestorePhase.Failed else ForegroundRestorePhase.Live
                    diagnosticLogger.info(
                        DiagnosticLogArea.Sse,
                        "foreground restore finished generation=$gen phase=$phase",
                    )
                }
            restoreJob.set(job)
        }

        private data class OpenSession(
            val sessionId: String,
            val directory: String?,
        )
    }

/** Home list REST catch-up hook. No-op until home registers a real implementation. */
fun interface ForegroundHomeCatchUp {
    suspend fun catchUp()
}
