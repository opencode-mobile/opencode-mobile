package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileContentDecoderTest {
    private val limit = ProjectFileLimits.TEXT_PREVIEW_BYTES

    @Test
    fun decodeV2_rangeBodyOfExactlyOneMebibyteEndingMidCharacter_isTruncatedText() {
        // Every split of every multibyte length: 2-byte é, 3-byte €, 4-byte 😀.
        listOf("é", "€", "😀").forEach { character ->
            val width = character.encodeToByteArray().size
            (1 until width).forEach { kept ->
                val body = rangeBody(character, keptOfLast = kept)
                assertEquals(limit, body.size)

                val content =
                    ProjectFileContentDecoder.decodeV2(
                        path = "big.txt",
                        bytes = body,
                        exceededLimit = true,
                        totalBytes = 5_000_000,
                        contentType = "text/plain",
                        requestedAsImage = false,
                    )

                val label = "$character with $kept of $width bytes kept"
                assertTrue(label, content is ProjectFileContent.Text)
                content as ProjectFileContent.Text
                assertTrue(label, content.isTruncated)
                assertFalse(label, content.text.contains('�'))
                assertEquals(label, limit - kept, content.text.encodeToByteArray().size)
                assertTrue(label, content.text.endsWith(character))
            }
        }
    }

    @Test
    fun decodeV2_overLimitBodyEndingMidCharacter_isTruncatedText() {
        val full = ("ab" + "€".repeat(limit / 3 + 1)).encodeToByteArray()
        val content =
            ProjectFileContentDecoder.decodeV2(
                path = "big.txt",
                bytes = full.copyOf(limit + 1),
                exceededLimit = true,
                totalBytes = null,
                contentType = null,
                requestedAsImage = false,
            ) as ProjectFileContent.Text

        assertTrue(content.isTruncated)
        assertEquals(limit - 2, content.text.encodeToByteArray().size)
    }

    @Test
    fun utf8BoundaryAtOrBefore_backsOffOnlyIncompleteSequences() {
        val euro = "a€".encodeToByteArray() // 61 E2 82 AC
        assertEquals(1, ProjectFileContentDecoder.utf8BoundaryAtOrBefore(euro, 2))
        assertEquals(1, ProjectFileContentDecoder.utf8BoundaryAtOrBefore(euro, 3))
        assertEquals(4, ProjectFileContentDecoder.utf8BoundaryAtOrBefore(euro, 4))
        assertEquals(1, ProjectFileContentDecoder.utf8BoundaryAtOrBefore(euro, 1))
        assertEquals(0, ProjectFileContentDecoder.utf8BoundaryAtOrBefore(euro, 0))
    }

    @Test
    fun decodeV2_untruncatedInvalidUtf8_isStillBinary() {
        val content =
            ProjectFileContentDecoder.decodeV2(
                path = "weird.dat",
                bytes = byteArrayOf(0x61, 0xC3.toByte()),
                exceededLimit = false,
                totalBytes = 2,
                contentType = null,
                requestedAsImage = false,
            )
        assertTrue(content is ProjectFileContent.Binary)
    }

    @Test
    fun v1TooLarge_isTooLargeWithUnknownSize() {
        assertEquals(ProjectFileContent.TooLarge(byteCount = null), ProjectFileContentDecoder.v1TooLarge())
    }

    /** Exactly [limit] bytes: ASCII padding, then [character]s, ending with [keptOfLast] bytes of one more. */
    private fun rangeBody(
        character: String,
        keptOfLast: Int,
    ): ByteArray {
        val width = character.encodeToByteArray().size
        val characterBytes = limit - keptOfLast
        val padding = characterBytes % width
        val whole = characterBytes / width
        val full = ("a".repeat(padding) + character.repeat(whole + 1)).encodeToByteArray()
        return full.copyOf(limit)
    }
}
