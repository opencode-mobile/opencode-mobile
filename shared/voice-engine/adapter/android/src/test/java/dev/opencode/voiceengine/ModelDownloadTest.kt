package dev.opencode.voiceengine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ModelDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val file = ModelFile("model.onnx", "https://example.invalid/model.onnx", content.size.toLong(), sha256(content))

    /** A server that drops the connection after [dropAfter] bytes of each answer, for the first [drops] answers. */
    private class Server(
        val content: ByteArray,
        var drops: Int = 0,
        val dropAfter: Int = 100_000,
        val honoursRange: Boolean = true,
    ) {
        val asked = mutableListOf<Long>()

        fun open(
            url: String,
            from: Long,
        ): ModelDownload.Response {
            asked += from
            val start = if (honoursRange) from.toInt() else 0
            val body = content.copyOfRange(start, content.size)
            val stream: InputStream =
                if (drops-- > 0) Dropping(body, dropAfter) else ByteArrayInputStream(body)
            return ModelDownload.Response(if (honoursRange && from > 0) 206 else 200, stream)
        }
    }

    private class Dropping(
        bytes: ByteArray,
        val after: Int,
    ) : InputStream() {
        private val input = ByteArrayInputStream(bytes)
        private var read = 0

        override fun read(): Int {
            if (read >= after) throw IOException("connection reset")
            read++
            return input.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (read >= after) throw IOException("connection reset")
            val n = input.read(b, off, minOf(len, after - read))
            if (n > 0) read += n
            return n
        }
    }

    private val sleeps = mutableListOf<Long>()

    private fun download(server: Server) = ModelDownload(open = server::open, sleep = { sleeps += it }, now = { 0L })

    @Test
    fun aDroppedConnectionResumesWhereItStopped() {
        val server = Server(content, drops = 2)
        val target = File(folder.root, "model.onnx")
        val seen = mutableListOf<Long>()

        download(server).fetch(file, target) { seen += it }

        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf(0L, 100_000L, 200_000L), server.asked)
        // Each attempt got further, so each waits the first backoff again.
        assertEquals(listOf(1_000L, 1_000L), sleeps)
        assertEquals(content.size.toLong(), seen.last())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun aPartFromAnEarlierRunCarriesOn() {
        val target = File(folder.root, "model.onnx")
        File(target.path + ".part").writeBytes(content.copyOfRange(0, 120_000))
        val server = Server(content)

        download(server).fetch(file, target) {}

        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf(120_000L), server.asked)
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsTheFileOver() {
        val target = File(folder.root, "model.onnx")
        File(target.path + ".part").writeBytes(content.copyOfRange(0, 120_000))
        val server = Server(content, honoursRange = false)

        download(server).fetch(file, target) {}

        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun noProgressGivesUpAfterFourRetries() {
        val server = Server(content, drops = Int.MAX_VALUE, dropAfter = 0)
        val target = File(folder.root, "model.onnx")

        val error = runCatching { download(server).fetch(file, target) {} }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), sleeps)
        assertEquals(5, server.asked.size)
        assertFalse(target.exists())
    }

    @Test
    fun aDamagedFileIsFetchedAgainFromTheStart() {
        val target = File(folder.root, "model.onnx")
        // Same length as the file but not its bytes: the check fails, so it's fetched whole once more.
        File(target.path + ".part").writeBytes(ByteArray(content.size))
        val server = Server(content)

        download(server).fetch(file, target) {}

        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf(0L), server.asked)
    }

    @Test
    fun downloadRateSaysNothingUntilItKnowsTheSpeed() {
        var now = 0L
        val rate = DownloadRate(now = { now })
        // Resumed bytes on the first report don't count as speed.
        assertEquals(null, rate.secondsLeft(500_000_000, 1_000_000_000))
        for (step in 1..6) {
            now = step * 500L
            rate.secondsLeft(500_000_000L + step * 5_000_000L, 1_000_000_000)
        }
        // 10 MB/s with 470 MB to go.
        assertEquals(47L, rate.secondsLeft(530_000_000, 1_000_000_000))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
