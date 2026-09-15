package dev.opencode.mobile.storescreenshots

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import dagger.hilt.android.EntryPointAccessors
import dev.opencode.mobile.MainActivity
import dev.opencode.mobile.di.StoreScreenshotEntryPoint
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val serverUrl = arguments.getString(ARG_SERVER_URL, DEFAULT_SERVER_URL)
    private val serverPassword = arguments.getString(ARG_SERVER_PASSWORD, DEFAULT_SERVER_PASSWORD)
    private val outDir =
        File(
            arguments.getString(ARG_OUT_DIR)
                ?: File(targetContext.getExternalFilesDir(null), "store-screenshots").absolutePath,
        )

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seedAndLaunch() {
        closeLaunchedActivity()
        outDir.mkdirs()
        runBlocking { seedFixtureProfile() }
        AppOnboardingPreferences.markCompleted()
        AnalyticsConsentPreferences.updateProductAnalyticsConsented(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    @After
    fun closeActivity() {
        closeLaunchedActivity()
    }

    @Test
    fun home() {
        waitForHome()
        capture("01-home.png")
    }

    @Test
    fun chatCode() {
        waitForHome()
        tapText(SESSION_CODE)
        waitForText(COMPOSER_HINT)
        revealCodeBlock()
        settle()
        capture("02-chat-code.png")
    }

    @Test
    fun chatPermission() {
        waitForHome()
        tapText(SESSION_PERMISSION)
        waitForText(PERMISSION_TITLE)
        settle()
        capture("03-chat-permission.png")
    }

    @Test
    fun changes() {
        waitForHome()
        tapText(SESSION_CODE)
        waitForText(COMPOSER_HINT)
        tapDesc(MORE_OPTIONS)
        tapText(REVIEW_CHANGES)
        waitForText(CHANGES_TITLE)
        waitForText(CHANGED_FILE)
        settle()
        capture("04-changes.png")
    }

    @Test
    fun settings() {
        waitForHome()
        tapDesc(SETTINGS)
        waitForText(SETTINGS)
        waitForText(SERVERS)
        settle()
        capture("05-settings.png")
    }

    private suspend fun seedFixtureProfile() {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                targetContext.applicationContext,
                StoreScreenshotEntryPoint::class.java,
            )
        val coordinator = entryPoint.connectionSessionCoordinator()
        val profileStore = entryPoint.connectionProfileStore()
        if (!isFixtureReady(profileStore)) {
            coordinator.clearConnectionData()
            coordinator.saveProfile(
                ConnectionProfile(
                    baseUrl = serverUrl,
                    username = USERNAME,
                    password = serverPassword,
                    serverProtocol = ServerProtocol.V2,
                    name = PROFILE_NAME,
                ),
                ServerProtocol.V2,
            )
            val savedUrl = profileStore.activeProfile()?.baseUrl ?: serverUrl
            for (worktree in FIXTURE_WORKTREES) {
                profileStore.addWorktree(savedUrl, worktree)
            }
        }
        assertTrue(
            "Fixture profile/worktrees were not ready before launch",
            isFixtureReady(profileStore),
        )
    }

    private suspend fun isFixtureReady(profileStore: ConnectionProfileStore): Boolean {
        val active = profileStore.activeProfile() ?: return false
        val expected = serverUrl.trimEnd('/')
        val actual = active.baseUrl.trimEnd('/')
        if (!actual.equals(expected, ignoreCase = true)) {
            return false
        }
        val worktrees = profileStore.addedWorktrees(active.baseUrl)
        return FIXTURE_WORKTREES.all { it in worktrees }
    }

    private fun waitForHome() {
        val sessionReady = device.wait(Until.hasObject(By.text(SESSION_CODE)), HOME_READY_MS)
        if (!sessionReady) {
            device.wait(Until.hasObject(By.text(PROFILE_NAME)), 1_000)
            if (hasEmptyHome() || !device.hasObject(By.text(SESSION_CODE))) {
                pullToRefresh()
            }
        }
        waitForText(SESSION_CODE)
        waitForText(RUNNING)
        waitForText(LIVE)
        settle()
    }

    private fun hasEmptyHome(): Boolean =
        device.hasObject(By.text(EMPTY_CONNECT)) ||
            device.hasObject(By.text(EMPTY_PROJECTS)) ||
            device.hasObject(By.text(LOADING_SESSIONS))

    private fun pullToRefresh() {
        val midX = device.displayWidth / 2
        val fromY = (device.displayHeight * 0.28).toInt()
        val toY = (device.displayHeight * 0.62).toInt()
        device.swipe(midX, fromY, midX, toY, 28)
        SystemClock.sleep(1_000)
    }

    private fun waitForText(text: String) {
        if (device.wait(Until.hasObject(By.text(text)), TIMEOUT_MS)) {
            return
        }
        scrollToText(text)
        assertTrue("Timed out waiting for text: $text", device.wait(Until.hasObject(By.text(text)), TIMEOUT_MS))
    }

    private fun revealCodeBlock() {
        scrollTranscriptToBottom()
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        scrollable.maxSearchSwipes = 16
        runCatching { scrollable.scrollTextIntoView(CODE_BLOCK_BEFORE) }
        runCatching { scrollable.scrollTextIntoView(CODE_BLOCK_AFTER) }
        SystemClock.sleep(300)
        repeat(15) {
            if (codeBlockNeighborsFramed()) {
                revealIntroIfClipped()
                waitForText(COMPOSER_HINT)
                return
            }
            val before = neighborBounds(CODE_BLOCK_BEFORE)
            val after = neighborBounds(CODE_BLOCK_AFTER)
            val bar = topBarBottom()
            val composer = composerTop()
            when {
                before == null || before.top < bar -> swipeVertical(0.10f)
                after == null || after.bottom > composer -> swipeVertical(-0.15f)
                else -> swipeVertical(0.10f)
            }
            SystemClock.sleep(300)
        }
        failWithHierarchy("Code block neighbors not framed: $CODE_BLOCK_BEFORE … $CODE_BLOCK_AFTER")
    }

    private fun scrollTranscriptToBottom() {
        repeat(4) {
            swipeVertical(-0.45f)
            SystemClock.sleep(300)
        }
    }

    private fun codeBlockNeighborsFramed(): Boolean {
        val before = neighborBounds(CODE_BLOCK_BEFORE) ?: return false
        val after = neighborBounds(CODE_BLOCK_AFTER) ?: return false
        val bar = topBarBottom()
        val composer = composerTop()
        return before.top >= bar && after.top >= bar && after.top < composer
    }

    private fun revealIntroIfClipped() {
        val before = neighborBounds(CODE_BLOCK_BEFORE) ?: return
        val minHeight = (44f * targetContext.resources.displayMetrics.density).toInt()
        if (before.height() < minHeight) {
            swipeVertical(0.10f)
            SystemClock.sleep(300)
        }
    }

    private fun neighborBounds(text: String): Rect? {
        val node = device.findObject(By.textContains(text)) ?: return null
        val bounds = runCatching { Rect(node.visibleBounds) }.getOrNull() ?: return null
        return bounds.takeIf { it.height() > 0 }
    }

    private fun topBarBottom(): Int {
        val title = device.findObject(By.text(SESSION_CODE))?.visibleBounds?.bottom
        val margin = (4f * targetContext.resources.displayMetrics.density).toInt()
        return (title ?: (device.displayHeight * 0.12).toInt()) + margin
    }

    private fun composerTop(): Int =
        device.findObject(By.text(COMPOSER_HINT))?.visibleBounds?.top
            ?: (device.displayHeight * 0.74).toInt()

    private fun failWithHierarchy(message: String): Nothing {
        val dump = File.createTempFile("store-screenshot-ui", ".xml")
        val texts =
            runCatching {
                device.dumpWindowHierarchy(dump)
                Regex("""text="([^"]+)"""")
                    .findAll(dump.readText())
                    .map { it.groupValues[1] }
                    .filter { it.isNotBlank() }
                    .take(20)
                    .toList()
            }.getOrDefault(emptyList())
        throw AssertionError("$message. hierarchy=${dump.absolutePath} visible texts=$texts")
    }

    private fun swipeVertical(fraction: Float) {
        val midX = device.displayWidth / 2
        val midY = (device.displayHeight * 0.36).toInt()
        val delta = (device.displayHeight * fraction).toInt()
        device.swipe(midX, midY, midX, midY + delta, 18)
    }

    private fun closeLaunchedActivity() {
        val open = scenario ?: return
        scenario = null
        open.close()
        // Do not waitForIdleSync: live SSE / Compose can keep the main
        // thread busy so instrumentation never goes idle and the runner
        // is killed ("Process crashed", empty <failure>).
        device.wait(Until.gone(By.text(COMPOSER_HINT)), 1_500)
        device.wait(Until.gone(By.desc("Back")), 1_500)
    }

    private fun waitForDesc(description: String) {
        assertTrue(
            "Timed out waiting for description: $description",
            device.wait(Until.hasObject(By.desc(description)), TIMEOUT_MS),
        )
    }

    private fun tapText(text: String) {
        waitForText(text)
        device.findObject(By.text(text)).click()
    }

    private fun tapDesc(description: String) {
        waitForDesc(description)
        device.findObject(By.desc(description)).click()
    }

    private fun scrollToText(text: String) {
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        scrollable.maxSearchSwipes = 8
        runCatching { scrollable.scrollTextIntoView(text) }
    }

    private fun settle() {
        SystemClock.sleep(SETTLE_MS)
    }

    private fun capture(fileName: String) {
        val file = File(outDir, fileName)
        assertTrue("Failed to write $fileName", device.takeScreenshot(file))
    }

    private companion object {
        const val ARG_SERVER_URL = "storeServerUrl"
        const val ARG_SERVER_PASSWORD = "storeServerPassword"
        const val ARG_OUT_DIR = "storeOutDir"
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:4399"
        const val DEFAULT_SERVER_PASSWORD = "store-screenshots"
        const val USERNAME = "opencode"
        const val PROFILE_NAME = "Fixture server"
        val FIXTURE_WORKTREES =
            listOf(
                "/Users/ivan/Projects/opencode-mobile",
                "/Users/ivan/Projects/artifact-publisher",
            )
        const val SESSION_CODE = "Code samples request"
        const val SESSION_PERMISSION = "Showing app permission dialog"
        const val RUNNING = "Running"
        const val LIVE = "live"
        const val COMPOSER_HINT = "Ask anything..."
        const val CODE_BLOCK_BEFORE = "Here is a variant-first button primitive"
        const val CODE_BLOCK_AFTER = "Should this also expose an IconCircle variant"
        const val PERMISSION_TITLE = "Permission requested"
        const val MORE_OPTIONS = "More options"
        const val REVIEW_CHANGES = "Review changes"
        const val CHANGES_TITLE = "Changes"
        const val CHANGED_FILE = "OpenCodeButton.kt"
        const val SETTINGS = "Settings"
        const val SERVERS = "Servers"
        const val TIMEOUT_MS = 30_000L
        const val HOME_READY_MS = 10_000L
        const val SETTLE_MS = 500L
        const val EMPTY_CONNECT = "Connect to your server"
        const val EMPTY_PROJECTS = "No projects yet"
        const val LOADING_SESSIONS = "Loading sessions"
    }
}
