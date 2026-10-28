package dev.opencode.mobile.highlight.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * Copies the packed ints to [to], which needs room for [HighlightSpans.intCount]
 * `Int32`s. One bridge call for all spans:
 *
 * ```swift
 * var ints = [Int32](repeating: 0, count: Int(spans.intCount))
 * ints.withUnsafeMutableBufferPointer { spans.writeInts(to: $0.baseAddress!) }
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
fun HighlightSpans.writeInts(to: CPointer<IntVar>) {
    val count = intCount
    if (count == 0) return
    rawInts().usePinned { pinned ->
        memcpy(to, pinned.addressOf(0), (count.toLong() * Int.SIZE_BYTES).convert())
    }
}
