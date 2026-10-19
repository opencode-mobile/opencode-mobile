package dev.opencode.mobile.ui.sessionchat.dictation

import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictationModelDownloaderTest {
    private class FakeRequest(
        private val accept: Boolean = true,
    ) : DictationModelDownloadRequest {
        var events: DictationModelDownloadEvents? = null
        var sends = 0
        var closed = false

        override fun send(events: DictationModelDownloadEvents): Boolean {
            sends++
            this.events = events
            return accept
        }

        override fun close() {
            closed = true
        }
    }

    private val requestedTags = mutableListOf<String?>()

    private fun TestScope.downloader(
        request: FakeRequest?,
        probe: () -> DictationSupport = { DictationSupport.ModelNotInstalled("en-US") },
    ) = DictationModelDownloader(
        scope = backgroundScope,
        probe = { probe() },
        makeRequest = { tag ->
            requestedTags += tag
            request
        },
        now = { testScheduler.currentTime },
        pollIntervalMs = 1_000,
        quietTimeoutMs = 10_000,
    )

    @Test
    fun listenerProgress_reportsPercentThenInstalled() =
        runTest {
            val request = FakeRequest()
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(request).start(languageTag = "en-US", alreadyPending = false) { updates += it }

            assertEquals(listOf<String?>("en-US"), requestedTags)
            request.events!!.onProgress(42)
            request.events!!.onInstalled()

            assertEquals(
                listOf(
                    DictationModelDownloadUpdate.Downloading(null),
                    DictationModelDownloadUpdate.Downloading(0.42f),
                    DictationModelDownloadUpdate.Installed,
                ),
                updates,
            )
            assertTrue(request.closed)
        }

    @Test
    fun scheduledAndError_mapToQueuedAndFailed() =
        runTest {
            val scheduled = FakeRequest()
            val scheduledUpdates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(scheduled).start(languageTag = "en-US", alreadyPending = false) { scheduledUpdates += it }
            scheduled.events!!.onScheduled()
            assertEquals(DictationModelDownloadUpdate.Queued, scheduledUpdates.last())

            val failed = FakeRequest()
            val failedUpdates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(failed).start(languageTag = "en-US", alreadyPending = false) { failedUpdates += it }
            failed.events!!.onFailed()
            assertEquals(DictationModelDownloadUpdate.Failed, failedUpdates.last())
        }

    @Test
    fun refusedOrMissingRecognizer_fails() =
        runTest {
            val refused = mutableListOf<DictationModelDownloadUpdate>()
            downloader(FakeRequest(accept = false)).start(languageTag = "en-US", alreadyPending = false) { refused += it }
            assertEquals(DictationModelDownloadUpdate.Failed, refused.last())

            val missing = mutableListOf<DictationModelDownloadUpdate>()
            downloader(request = null).start(languageTag = "en-US", alreadyPending = false) { missing += it }
            assertEquals(DictationModelDownloadUpdate.Failed, missing.last())
        }

    @Test
    fun silentRecognizer_installedByPolling() =
        runTest {
            var installed = false
            val request = FakeRequest()
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(request) {
                if (installed) DictationSupport.Ready("English") else DictationSupport.ModelDownloading("en-US")
            }.start(languageTag = "en-US", alreadyPending = false) { updates += it }
            request.events!!.onEventsUnavailable()

            advanceTimeBy(3_500)
            assertEquals(listOf(DictationModelDownloadUpdate.Downloading(null)), updates)

            installed = true
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DictationModelDownloadUpdate.Installed, updates.last())
        }

    @Test
    fun quietRequest_becomesQueuedAfterTimeout() =
        runTest {
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(FakeRequest()).start(languageTag = "en-US", alreadyPending = false) { updates += it }

            advanceTimeBy(9_500)
            assertEquals(1, updates.size)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DictationModelDownloadUpdate.Queued, updates.last())
        }

    @Test
    fun systemDialogOverTheApp_holdsTheQuietTimeout() =
        runTest {
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            val downloader = downloader(FakeRequest())
            downloader.start(languageTag = "en-US", alreadyPending = false) { updates += it }

            downloader.setHostPaused(true)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(DictationModelDownloadUpdate.Downloading(null)), updates)

            downloader.setHostPaused(false)
            advanceTimeBy(9_500)
            assertEquals(1, updates.size)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DictationModelDownloadUpdate.Queued, updates.last())
        }

    @Test
    fun progressResetsTheQuietTimeout() =
        runTest {
            val request = FakeRequest()
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(request).start(languageTag = "en-US", alreadyPending = false) { updates += it }

            advanceTimeBy(8_000)
            request.events!!.onProgress(10)
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals(DictationModelDownloadUpdate.Downloading(0.1f), updates.last())
        }

    @Test
    fun alreadyPending_watchesWithoutANewRequest() =
        runTest {
            val request = FakeRequest()
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            downloader(request) { DictationSupport.Ready("English") }
                .start(languageTag = "en-US", alreadyPending = true) { updates += it }

            advanceTimeBy(1_500)
            assertEquals(0, request.sends)
            assertEquals(DictationModelDownloadUpdate.Installed, updates.last())
        }

    @Test
    fun close_dropsLateEvents() =
        runTest {
            val request = FakeRequest()
            val updates = mutableListOf<DictationModelDownloadUpdate>()
            val downloader = downloader(request)
            downloader.start(languageTag = "en-US", alreadyPending = false) { updates += it }

            downloader.close()
            request.events!!.onInstalled()
            advanceTimeBy(20_000)

            assertEquals(listOf(DictationModelDownloadUpdate.Downloading(null)), updates)
            assertTrue(request.closed)
        }

    @Test
    fun cardPhase_mapsUpdates() {
        assertEquals(
            ComposerDictationPhase.ModelDownloading to 0.5f,
            DictationModelDownloadUpdate.Downloading(0.5f).cardPhase(),
        )
        assertEquals(ComposerDictationPhase.ModelDownloadQueued to null, DictationModelDownloadUpdate.Queued.cardPhase())
        assertEquals(ComposerDictationPhase.ModelDownloadFailed to null, DictationModelDownloadUpdate.Failed.cardPhase())
        assertNull(DictationModelDownloadUpdate.Installed.cardPhase())
    }
}
