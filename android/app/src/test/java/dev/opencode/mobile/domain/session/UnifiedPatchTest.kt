package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPatchTest {
    @Test fun buildsTheSamePatchAsSplittingAndJoiningBothFiles() {
        val cases =
            listOf(
                "old" to "new",
                "" to "hello",
                "goodbye" to "",
                "a\nb\n" to "a\nc\n",
                "one\r\ntwo" to "one\r\nthree\n\n",
                "\n" to "x",
            )
        for ((before, after) in cases) {
            assertEquals("$before -> $after", splitAndJoin(before, after), UnifiedPatch.fromContents(before, after)?.text)
            assertFalse(UnifiedPatch.fromContents(before, after)!!.isTruncated)
        }
        assertNull(UnifiedPatch.fromContents("same", "same"))
        assertNull(UnifiedPatch.fromContents(null, "x"))
    }

    @Test fun readsEachInputCharacterABoundedNumberOfTimes() {
        val text = (1..20_000).joinToString("\n") { "line $it" }
        val before = CountingText(text)
        val after = CountingText(text.replace("line 7", "LINE 7"))
        val patch = UnifiedPatch.fromContents(before, after, limit = Int.MAX_VALUE)!!
        val inputLength = before.length + after.length
        // Equality check, line count, then one copy: at most four reads per character.
        assertTrue("${before.reads + after.reads} reads for $inputLength chars", before.reads + after.reads <= 4L * inputLength)
        assertEquals(splitAndJoin(text, after.toString()), patch.text)
    }

    @Test fun stopsAtTheLimitOnALineEnd() {
        val before = (1..1_000).joinToString("\n") { "old line $it" }
        val after = (1..1_000).joinToString("\n") { "new line $it" }
        val preview = UnifiedPatch.fromContents(before, after, limit = 5_000)!!
        assertTrue(preview.isTruncated)
        assertTrue(preview.text.length <= 5_000)
        assertTrue(splitAndJoin(before, after).startsWith(preview.text + "\n"))
    }

    @Test fun cutsALongServerPatchAtItsLastLineEnd() {
        val patch = "@@ -1,3 +1,3 @@\n-aaaa\n+bbbb\n cccc"
        assertEquals(UnifiedPatch.Preview(patch, isTruncated = false), UnifiedPatch.preview(patch, limit = patch.length))
        assertEquals(UnifiedPatch.Preview("@@ -1,3 +1,3 @@\n-aaaa", isTruncated = true), UnifiedPatch.preview(patch, limit = 24))
    }

    @Test fun adapterMarksTruncatedPatches() {
        val adapter = SessionDomainAdapter()
        val long = "@@ -0,0 +1,300000 @@\n" + (1..300_000).joinToString("\n") { "+line $it" }
        val diff =
            adapter.toFileDiff(
                dev.opencode.mobile.data.remote.dto
                    .FileDiffDto(file = "big.txt", patch = long),
            )
        assertTrue(diff.isPatchTruncated)
        assertTrue(diff.patch!!.length <= UnifiedPatch.PREVIEW_CHARS)
        assertFalse(
            adapter
                .toFileDiff(
                    dev.opencode.mobile.data.remote.dto
                        .FileDiffDto(file = "a", patch = "@@ -1 +1 @@\n-a\n+b"),
                ).isPatchTruncated,
        )
    }

    /** The original builder: split both files and join prefixed lines. */
    private fun splitAndJoin(
        before: String,
        after: String,
    ): String {
        val oldLines = before.takeIf(String::isNotEmpty)?.split("\n").orEmpty()
        val newLines = after.takeIf(String::isNotEmpty)?.split("\n").orEmpty()
        return buildList {
            add("@@ -${if (oldLines.isEmpty()) 0 else 1},${oldLines.size} +${if (newLines.isEmpty()) 0 else 1},${newLines.size} @@")
            addAll(oldLines.map { "-$it" })
            addAll(newLines.map { "+$it" })
        }.joinToString("\n")
    }

    private class CountingText(
        private val text: String,
    ) : CharSequence {
        var reads = 0L

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence {
            reads += endIndex - startIndex
            return text.subSequence(startIndex, endIndex)
        }

        override fun toString(): String = text
    }
}
