package dev.opencode.mobile

import dev.opencode.testguard.StateProbe

/**
 * Process-wide singletons in :app that tests swap and must put back; the isolation guard
 * (android/test-guard) checks them after each test class. Registered in
 * `META-INF/services/dev.opencode.testguard.StateProbe$Provider`.
 */
class AppTestIsolationProbes : StateProbe.Provider {
    override fun probes(): List<StateProbe> {
        val productionSink = ProductAnalytics.sink
        return listOf(
            StateProbe(
                name = "ProductAnalytics.sink",
                read = { ProductAnalytics.sink },
                restore = { ProductAnalytics.sink = productionSink },
                describe = { if (it === productionSink) "the production sink" else "a test sink" },
            ),
        )
    }
}
