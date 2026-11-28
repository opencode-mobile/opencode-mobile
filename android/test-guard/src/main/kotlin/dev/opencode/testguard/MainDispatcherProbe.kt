package dev.opencode.testguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain

/**
 * Catches `Dispatchers.setMain` without `resetMain`. kotlinx-coroutines-test makes
 * `Dispatchers.Main` a wrapper that forwards to the dispatcher a test set. While a test dispatcher
 * is set, `Main.immediate` is the wrapper itself; after `resetMain` it's the real main dispatcher's
 * `immediate`, or that dispatcher itself when there is none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object MainDispatcherProbe {
    private const val SET = "a test dispatcher (setMain without resetMain)"
    private const val UNSET = "unset"

    fun createOrNull(): StateProbe? {
        val present = runCatching { Class.forName("kotlinx.coroutines.test.TestDispatchers") }.isSuccess
        if (!present) return null
        return StateProbe(
            name = "Dispatchers.Main",
            read = { if (isTestDispatcherSet()) SET else UNSET },
            restore = { Dispatchers.resetMain() },
        )
    }

    private fun isTestDispatcherSet(): Boolean =
        runCatching {
            val main = Dispatchers.Main
            main.immediate === main
        }.getOrDefault(false)
}
