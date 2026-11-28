package dev.opencode.testguard

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * Applies [TestIsolationGuard] to every test the JUnit Platform runs in this JVM (JUnit 4 tests run
 * through the vintage engine). Registered in `META-INF/services`, so depending on `:test-guard`
 * is enough.
 */
class TestIsolationListener : TestExecutionListener {
    override fun testPlanExecutionStarted(testPlan: TestPlan) = TestIsolationGuard.install()

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (testIdentifier.isTest) TestIsolationGuard.testStarted(testIdentifier.label())
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        val source = testIdentifier.source.orElse(null)
        if (testIdentifier.isContainer && source is ClassSource) {
            TestIsolationGuard.containerFinished(source.className)
        }
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) = TestIsolationGuard.writeReport()

    private fun TestIdentifier.label(): String =
        when (val source = source.orElse(null)) {
            is MethodSource -> "${source.className}.${source.methodName}"
            else -> displayName
        }
}
