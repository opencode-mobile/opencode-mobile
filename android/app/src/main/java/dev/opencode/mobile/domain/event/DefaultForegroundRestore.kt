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
        private val lock = Any()
        private val generation = AtomicLong(0)
        private val restoreJob = AtomicReference<Job?>(null)
        private val openSession = AtomicReference<OpenSession?>(null)
        private val homeVisible = AtomicReference(false)
        private var needsCatchUp = true
        private var missedSessionCatchUp = false

        @Volatile
        var phase: ForegroundRestorePhase = ForegroundRestorePhase.Idle
            private set

        override fun setOpenSession(
            sessionId: String?,
            directory: String?,
        ) {
            val session =
                if (sessionId.isNullOrEmpty()) {
                    null
                } else {
                    OpenSession(sessionId, directory)
                }
            val shouldRestore =
                synchronized(lock) {
                    openSession.set(session)
                    if (session != null && missedSessionCatchUp) {
                        missedSessionCatchUp = false
                        needsCatchUp = true
                        true
                    } else {
                        false
                    }
                }
            if (shouldRestore) {
                restore()
            }
        }

        override fun setHomeVisible(visible: Boolean) {
            homeVisible.set(visible)
        }

        override fun noteBackground() {
            synchronized(lock) {
                needsCatchUp = true
            }
        }

        override fun noteBackgroundStop() {
            generation.incrementAndGet()
            restoreJob.getAndSet(null)?.cancel()
            synchronized(lock) {
                needsCatchUp = true
                missedSessionCatchUp = false
            }
            phase = ForegroundRestorePhase.Idle
            stream.stop()
        }

        override fun restore() {
            val started: RestoreStart? =
                synchronized(lock) {
                    val catchUp =
                        needsCatchUp ||
                            stream.connectionState.value == EventStreamConnectionState.Idle
                    if (!catchUp) {
                        null
                    } else {
                        val gen = generation.incrementAndGet()
                        restoreJob.getAndSet(null)?.cancel()
                        phase = ForegroundRestorePhase.Restoring
                        val session = openSession.get()
                        needsCatchUp = false
                        missedSessionCatchUp = session == null
                        RestoreStart(
                            generation = gen,
                            session = session,
                            refreshHome = homeVisible.get(),
                        )
                    }
                }
            if (started == null) {
                diagnosticLogger.info(
                    DiagnosticLogArea.Sse,
                    "foreground restore start-only catchUp=false",
                )
                stream.start()
                return
            }
            diagnosticLogger.info(
                DiagnosticLogArea.Sse,
                "foreground restore starting generation=${started.generation} catchUp=true",
            )
            stream.start()
            val job =
                scope.launch {
                    var failed = false
                    val session = started.session
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
                    if (started.refreshHome) {
                        homeCatchUp.catchUp()
                        diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground restore home catch-up")
                    }
                    if (started.generation != generation.get()) return@launch
                    phase = if (failed) ForegroundRestorePhase.Failed else ForegroundRestorePhase.Live
                    diagnosticLogger.info(
                        DiagnosticLogArea.Sse,
                        "foreground restore finished generation=${started.generation} phase=$phase",
                    )
                }
            restoreJob.set(job)
        }

        private data class OpenSession(
            val sessionId: String,
            val directory: String?,
        )

        private data class RestoreStart(
            val generation: Long,
            val session: OpenSession?,
            val refreshHome: Boolean,
        )
    }

/** Home list REST catch-up hook. No-op until home registers a real implementation. */
fun interface ForegroundHomeCatchUp {
    suspend fun catchUp()
}
