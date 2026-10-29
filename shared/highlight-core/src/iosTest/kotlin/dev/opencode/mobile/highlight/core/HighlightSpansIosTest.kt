package dev.opencode.mobile.highlight.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class HighlightSpansIosTest {
    @Test
    fun writeIntsCopiesEveryPackedIntInOneCall() {
        val spans = HighlightCore.highlight("diff", "+a\n-b", HighlightCancellation.Never, HighlightDeadline.never).spans
        memScoped {
            val ints = allocArray<IntVar>(spans.intCount)
            spans.writeInts(ints)
            assertEquals(spans.toIntArray().toList(), List(spans.intCount) { ints[it] })
        }
    }
}
