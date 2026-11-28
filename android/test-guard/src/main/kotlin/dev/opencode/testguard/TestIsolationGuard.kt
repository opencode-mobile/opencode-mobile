package dev.opencode.testguard

import java.io.File
import java.net.ProxySelector
import java.util.Locale
import java.util.ServiceLoader
import java.util.TimeZone
import java.util.UUID

/**
 * Keeps unit tests off the real network and stops one test class from leaking process-wide state
 * into the next.
 *
 * [TestIsolationListener] installs the guard once per test JVM and reports to it, so every JUnit
 * test in a module that depends on `:test-guard` runs under it with no per-class rule. The guard:
 *
 * - installs [NetworkBlock] as the default [ProxySelector]: loopback works, any other host fails
 *   with a `ConnectException` and is recorded against the running test;
 * - after each test class, compares every [StateProbe] with its value when the JVM started,
 *   records a leak and restores the value.
 *
 * A JUnit Platform listener can't fail a test, so [TestIsolationListener] writes the violations to
 * the directory in the [REPORT_DIR_PROPERTY] system property, and the Gradle test task fails when it
 * finds any (`android/gradle/test-guard.gradle.kts`).
 *
 * Modules add probes for singletons they own with a [StateProbe.Provider] registered under
 * `META-INF/services/dev.opencode.testguard.StateProbe$Provider` in their test resources.
 */
object TestIsolationGuard {
    const val REPORT_DIR_PROPERTY = "opencode.testguard.reportDir"

    private val lock = Any()
    private val violations = mutableListOf<String>()
    private var capturing: MutableList<String>? = null
    private var installed = false
    private var networkBlock: NetworkBlock? = null
    private var baselines: List<Pair<StateProbe, Any?>> = emptyList()

    @Volatile
    private var currentTest: String = "<no test running>"

    /** Installs the network block and records each probe's starting value. Idempotent. */
    fun install() {
        synchronized(lock) {
            if (installed) return
            installed = true
            networkBlock = NetworkBlock(ProxySelector.getDefault(), ::recordBlockedConnection).also(ProxySelector::setDefault)
            val probes = coreProbes() + ServiceLoader.load(StateProbe.Provider::class.java).flatMap { it.probes() }
            baselines = probes.map { it to it.read() }
        }
    }

    /** The test the next violation is charged to. */
    fun testStarted(name: String) {
        currentTest = name
    }

    /** Checks every probe after [name] (a test class) finished; leaked state is restored. */
    fun containerFinished(name: String) {
        val leaks =
            synchronized(lock) {
                val leaks = mutableListOf<String>()
                val block = networkBlock
                if (block != null && ProxySelector.getDefault() !== block) {
                    leaks += "[network] $name replaced the default ProxySelector; the network block is reinstalled"
                    ProxySelector.setDefault(block)
                }
                for ((probe, baseline) in baselines) {
                    val value = probe.read()
                    if (probe.isSame(baseline, value)) continue
                    leaks += "[state] $name leaked ${probe.name} = ${probe.describe(value)} (expected ${probe.describe(baseline)}); reset"
                    probe.restore(baseline)
                }
                leaks
            }
        leaks.forEach(::record)
    }

    /**
     * Runs [body] and returns the violations it caused instead of failing the run, so the guard's
     * own tests can prove it catches a deliberate leak or real connection.
     */
    fun <T> capturingViolations(body: () -> T): Pair<T, List<String>> {
        val captured = mutableListOf<String>()
        synchronized(lock) {
            check(capturing == null) { "capturingViolations does not nest" }
            capturing = captured
        }
        try {
            val result = body()
            return result to synchronized(lock) { captured.toList() }
        } finally {
            synchronized(lock) { capturing = null }
        }
    }

    /** Writes this JVM's violations, if any, for the Gradle task to fail on. */
    fun writeReport() {
        val all = synchronized(lock) { violations.toList() }
        if (all.isEmpty()) return
        val dir = System.getProperty(REPORT_DIR_PROPERTY)?.let(::File)
        if (dir == null) {
            System.err.println("TestIsolationGuard: ${all.size} violation(s), no $REPORT_DIR_PROPERTY to report them to")
            return
        }
        dir.mkdirs()
        File(dir, "violations-${UUID.randomUUID()}.txt").writeText(all.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun recordBlockedConnection(
        scheme: String,
        host: String,
    ) {
        record("[network] $currentTest opened a real connection to $scheme://$host; only loopback is allowed in unit tests")
    }

    private fun record(message: String) {
        val reported =
            synchronized(lock) {
                val sink = capturing ?: violations
                if (message !in sink) sink += message
                sink === violations
            }
        // Also printed now, next to the test's own output; the Gradle task fails on the report.
        if (reported) System.err.println("TestIsolationGuard $message")
    }

    private fun coreProbes(): List<StateProbe> =
        listOfNotNull(
            StateProbe(
                name = "Locale.getDefault()",
                read = { Locale.getDefault() },
                restore = { Locale.setDefault(it as Locale) },
            ),
            StateProbe(
                name = "TimeZone.getDefault()",
                read = { TimeZone.getDefault().id },
                restore = { TimeZone.setDefault(TimeZone.getTimeZone(it as String)) },
            ),
            MainDispatcherProbe.createOrNull(),
        )
}

/**
 * A piece of process-wide state the guard checks after each test class: [read] at JVM start is the
 * baseline, and a value that isn't `==` to it later is a leak that [restore] puts back. Lambdas and
 * plain objects compare by identity. [describe] names a value in the report; keep it free of user
 * or server content.
 */
class StateProbe(
    val name: String,
    private val read: () -> Any?,
    private val restore: (baseline: Any?) -> Unit,
    private val describe: (Any?) -> String = { it.toString() },
) {
    fun read(): Any? = read.invoke()

    fun restore(baseline: Any?) = restore.invoke(baseline)

    fun isSame(
        baseline: Any?,
        value: Any?,
    ): Boolean = baseline == value

    fun describe(value: Any?): String = describe.invoke(value)

    /** Registered through `ServiceLoader` from a module's test resources. */
    fun interface Provider {
        fun probes(): List<StateProbe>
    }
}
