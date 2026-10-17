package dev.opencode.mobile.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.opencode.mobile.di.StoreScreenshotEntryPoint
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

// Throwaway: seeds a Connection Profile for the scenario harness, then exits.
@RunWith(AndroidJUnit4::class)
class HarnessSeedTest {
    @Test
    fun seed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("harnessUrl", "http://10.0.2.2:4096")
        val password = args.getString("harnessPassword", "")
        val protocol = if (args.getString("harnessProtocol") == "v1") ServerProtocol.V1 else ServerProtocol.V2
        val entryPoint =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                StoreScreenshotEntryPoint::class.java,
            )
        runBlocking {
            val coordinator = entryPoint.connectionSessionCoordinator()
            coordinator.clearConnectionData()
            coordinator.saveProfile(
                ConnectionProfile(
                    baseUrl = url,
                    username = "opencode",
                    password = password,
                    serverProtocol = protocol,
                    name = "Scenario harness",
                ),
                protocol,
            )
            args.getString("harnessWorktree")?.let { worktree ->
                val store = entryPoint.connectionProfileStore()
                store.addWorktree(store.activeProfile()?.baseUrl ?: url, worktree)
            }
        }
        AppOnboardingPreferences.markCompleted()
        AnalyticsConsentPreferences.updateProductAnalyticsConsented(false)
        // The credential store writes with SharedPreferences.apply(); let it reach disk before the runner kills the process.
        Thread.sleep(2_000)
    }
}
