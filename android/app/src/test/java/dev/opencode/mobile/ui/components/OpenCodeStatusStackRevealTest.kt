package dev.opencode.mobile.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeStatusStackRevealTest {
    @Test
    fun immediateRevealIsVisibleAtOnce() =
        runTest {
            assertEquals(listOf(true), statusStackVisibility(OpenCodeStatusStackReveal.Immediate).toList())
        }

    @Test
    fun afterGraceStaysHiddenFor300Ms() =
        runTest {
            assertEquals(300L, OpenCodeStatusStackReveal.AfterGrace.graceMillis)
            val seen = mutableListOf<Boolean>()
            val job = launch { statusStackVisibility(OpenCodeStatusStackReveal.AfterGrace).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(false), seen)

            advanceTimeBy(299)
            runCurrent()
            assertEquals(listOf(false), seen)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(false, true), seen)
            job.join()
        }
}
