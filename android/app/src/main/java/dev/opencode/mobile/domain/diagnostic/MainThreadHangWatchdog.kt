package dev.opencode.mobile.domain.diagnostic

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background main-thread hang watchdog (OPE-134).
 * Pings the main looper from a background executor; when acknowledgment is
 * overdue past [DETECT_THRESHOLD_MILLISECONDS], writes a redacted `hang`
 * Diagnostic Log line. Logs recovery when the main looper responds again.
 * Does not block the main thread.
 */
@Singleton
class MainThreadHangWatchdog
    @Inject
    constructor(
        private val diagnosticLogger: DiagnosticLogger,
    ) {
        companion object {
            /** Overdue ping age that counts as a hang (2.5s; within the 2–3s product band). */
            const val DETECT_THRESHOLD_MILLISECONDS: Long = 2_500L

            /** How often the background loop checks / issues a ping. */
            const val PING_INTERVAL_MILLISECONDS: Long = 500L

            fun forTests(
                clockEpochMilliseconds: () -> Long,
                scheduleOnMain: (Runnable) -> Unit,
                onEvent: (MainThreadHangDetector.Event) -> Unit,
                scheduler: ScheduledExecutorService =
                    Executors.newSingleThreadScheduledExecutor { runnable ->
                        Thread(runnable, "opencode-hang-watchdog-test").apply { isDaemon = true }
                    },
            ): MainThreadHangWatchdog =
                MainThreadHangWatchdog(DiagnosticLogger()).apply {
                    this.clockEpochMilliseconds = clockEpochMilliseconds
                    this.scheduleOnMain = scheduleOnMain
                    this.scheduler = scheduler
                    this.onEvent = onEvent
                }
        }

        private val started = AtomicBoolean(false)
        private val lock = Any()
        private var detector = MainThreadHangDetector()

        @Volatile
        private var clockEpochMilliseconds: () -> Long = System::currentTimeMillis

        @Volatile
        private var scheduleOnMain: (Runnable) -> Unit = { runnable ->
            Handler(Looper.getMainLooper()).post(runnable)
        }

        @Volatile
        private var scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "opencode-hang-watchdog").apply { isDaemon = true }
            }

        @Volatile
        private var onEvent: ((MainThreadHangDetector.Event) -> Unit)? = null

        private var future: ScheduledFuture<*>? = null

        fun start() {
            if (!started.compareAndSet(false, true)) return
            future =
                scheduler.scheduleWithFixedDelay(
                    { tick() },
                    0L,
                    PING_INTERVAL_MILLISECONDS,
                    TimeUnit.MILLISECONDS,
                )
        }

        fun stop() {
            if (!started.compareAndSet(true, false)) return
            future?.cancel(false)
            future = null
            synchronized(lock) {
                detector = MainThreadHangDetector()
            }
        }

        /** Test seam: drive one watchdog tick without waiting on the scheduler. */
        fun tickForTesting() {
            tick()
        }

        private fun tick() {
            val eventToLog: MainThreadHangDetector.Event?
            val shouldPing: Boolean
            synchronized(lock) {
                val now = clockEpochMilliseconds()
                eventToLog =
                    detector.evaluate(
                        nowEpochMilliseconds = now,
                        thresholdMilliseconds = DETECT_THRESHOLD_MILLISECONDS,
                    )
                shouldPing = !detector.hasPendingPing
                if (shouldPing) {
                    detector.notePingSent(atEpochMilliseconds = now)
                }
            }
            eventToLog?.let(::emit)
            if (shouldPing) {
                scheduleOnMain {
                    acknowledgeOnMain()
                }
            }
        }

        private fun acknowledgeOnMain() {
            val event: MainThreadHangDetector.Event?
            synchronized(lock) {
                event = detector.notePingAcknowledged(atEpochMilliseconds = clockEpochMilliseconds())
            }
            event?.let(::emit)
        }

        private fun emit(event: MainThreadHangDetector.Event) {
            val sink = onEvent
            if (sink != null) {
                sink(event)
                return
            }
            when (event) {
                is MainThreadHangDetector.Event.HangDetected ->
                    diagnosticLogger.warning(
                        DiagnosticLogArea.Hang,
                        "hang detected overdueMs=${event.overdueMilliseconds}",
                    )
                is MainThreadHangDetector.Event.HangRecovered ->
                    diagnosticLogger.info(
                        DiagnosticLogArea.Hang,
                        "hang recovered durationMs=${event.durationMilliseconds}",
                    )
            }
        }
    }
