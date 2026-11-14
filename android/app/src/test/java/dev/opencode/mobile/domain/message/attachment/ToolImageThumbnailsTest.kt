package dev.opencode.mobile.domain.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolImageThumbnailsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val connection = "https://example.com/|opencode"

    /** The fake encoder makes a thumbnail of `sizes[edge]` bytes, recording each edge it tried. */
    private val triedEdges = mutableListOf<Int>()

    private fun store(
        limits: ToolThumbnailLimits = ToolThumbnailLimits(),
        sizes: Map<Int, Int> = mapOf(400 to 1_000, 300 to 600),
        now: () -> Long = { 1_000L },
    ): Pair<ToolImageThumbnails, File> {
        val root = File(temp.root, ToolImageThumbnails.DIRECTORY_NAME)
        val thumbnails =
            ToolImageThumbnails(
                root = root,
                limits = limits,
                encode = { _, edge, _ ->
                    triedEdges += edge
                    sizes[edge]?.let { ByteArray(it) { 1 } }
                },
                decodeDataUrl = { dataUrl, _ -> dataUrl.toByteArray() },
                clock = now,
            )
        return thumbnails to root
    }

    @Test
    fun fit_keepsFirstEdgeThatFitsTheCap() {
        val (thumbnails, _) = store(sizes = mapOf(400 to 70 * 1024, 300 to 50 * 1024))
        val fitted = thumbnails.fit(ByteArray(10))
        assertEquals(50 * 1024, fitted?.size)
        assertEquals(listOf(400, 300), triedEdges)
    }

    @Test
    fun fit_skipsWhenEveryEdgeIsOverTheCap() {
        val (thumbnails, _) = store(sizes = mapOf(400 to 90 * 1024, 300 to 65 * 1024 + 1))
        assertNull(thumbnails.fit(ByteArray(10)))
    }

    @Test
    fun fit_respects64KbCap() {
        val (thumbnails, _) = store(sizes = mapOf(400 to 64 * 1024))
        assertEquals(64 * 1024, thumbnails.fit(ByteArray(10))?.size)
        assertEquals(listOf(400), triedEdges)
    }

    @Test
    fun sync_writesNewestImagesOnlyAndDropsOlderFiles() {
        val (thumbnails, root) = store(limits = ToolThumbnailLimits(perSessionCount = 2))
        val first = thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a", "b")))
        assertTrue(first.wrote)
        assertEquals(setOf("m1-prt:image:0", "m1-prt:image:1"), first.paths.keys)

        val second = thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a", "b"), toolMessage("m2", "c")))

        // Newest two: b (m1) and c (m2); a lost its thumbnail.
        assertEquals(setOf("m1-prt:image:1", "m2-prt:image:0"), second.paths.keys)
        val names = sessionFiles(root).map { it.name }.toSet()
        assertEquals(setOf("m1-prt_image_1.jpg", "m2-prt_image_0.jpg"), names)
        assertFalse(names.any { it.contains(":") })
    }

    @Test
    fun sync_keepsExistingThumbnailForCachedImageWithoutData() {
        val (thumbnails, _) = store()
        val written = thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a")))
        val path = written.paths.values.single()

        val cachedOnly = toolMessage("m1", null)
        val again = thumbnails.sync(connection, "ses_1", listOf(cachedOnly))

        assertFalse(again.wrote)
        assertEquals(path, again.paths.values.single())
        assertEquals(path, thumbnails.existingPath(connection, "ses_1", "m1-prt:image:0"))
    }

    @Test
    fun sync_staysUnderPerSessionByteBudget() {
        val (thumbnails, _) = store(limits = ToolThumbnailLimits(perSessionBytes = 2_500), sizes = mapOf(400 to 1_000))
        val result = thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a", "b", "c")))
        // Newest first: c and b fit (2 000 bytes), a would exceed 2 500.
        assertEquals(setOf("m1-prt:image:2", "m1-prt:image:1"), result.paths.keys)
    }

    @Test
    fun connectionFolderIsAHashNotTheUrl() {
        val (thumbnails, root) = store()
        thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a")))
        val folder = root.listFiles()!!.single().name
        assertEquals(ToolImageThumbnails.connectionFolder(connection), folder)
        assertFalse(folder.contains("example"))
        assertEquals(32, folder.length)
    }

    @Test
    fun evictIfNeeded_dropsLeastRecentlyViewedDownToTarget() {
        val (thumbnails, root) =
            store(limits = ToolThumbnailLimits(globalMaxBytes = 3_500, globalTargetBytes = 2_000), sizes = mapOf(400 to 1_000))
        thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a", "b")))
        thumbnails.sync(connection, "ses_2", listOf(toolMessage("m2", "c", "d")))
        val files = root.walkTopDown().filter { it.isFile }.associateBy { it.name }
        // Viewed order, oldest first: c, a, d, b.
        files.getValue("m2-prt_image_0.jpg").setLastModified(10_000L)
        files.getValue("m1-prt_image_0.jpg").setLastModified(20_000L)
        files.getValue("m2-prt_image_1.jpg").setLastModified(30_000L)
        files.getValue("m1-prt_image_1.jpg").setLastModified(40_000L)

        val report = thumbnails.evictIfNeeded()

        assertEquals(2, report.evicted)
        assertEquals(2_000L, report.bytes)
        assertEquals(2, report.files)
        assertEquals(
            setOf("m2-prt_image_1.jpg", "m1-prt_image_1.jpg"),
            sessionFiles(root).map { it.name }.toSet(),
        )
    }

    @Test
    fun evictIfNeeded_isNoOpUnderTheCap() {
        val (thumbnails, _) = store(limits = ToolThumbnailLimits(globalMaxBytes = 10_000, globalTargetBytes = 5_000))
        thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a", "b")))
        val report = thumbnails.evictIfNeeded()
        assertEquals(0, report.evicted)
        assertEquals(2, report.files)
    }

    @Test
    fun read_marksTheFileViewed() {
        var now = 1_000L
        val (thumbnails, _) = store(now = { now })
        val synced = thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a")))
        val path = synced.paths.values.single()
        File(path).setLastModified(5_000L)
        now = 90_000L

        val bytes = thumbnails.read(path)

        assertArrayEquals(ByteArray(1_000) { 1 }, bytes)
        assertEquals(90_000L, File(path).lastModified())
        assertNull(thumbnails.read(File(temp.root, "elsewhere.jpg").path))
    }

    @Test
    fun sweepOrphans_deletesSessionFoldersWithoutATimelineRow() {
        val (thumbnails, root) = store()
        thumbnails.sync(connection, "ses_keep", listOf(toolMessage("m1", "a")))
        thumbnails.sync(connection, "ses_gone", listOf(toolMessage("m2", "b")))
        thumbnails.sync("other|opencode", "ses_other", listOf(toolMessage("m3", "c")))

        val report = thumbnails.sweepOrphans(listOf(connection to "ses_keep"))

        assertEquals(2, report.evicted)
        assertEquals(1, report.files)
        assertTrue(thumbnails.sessionDirectory(connection, "ses_keep").isDirectory)
        assertFalse(thumbnails.sessionDirectory(connection, "ses_gone").exists())
        assertFalse(File(root, ToolImageThumbnails.connectionFolder("other|opencode")).exists())
    }

    @Test
    fun deleteAll_removesTheWholeDirectory() {
        val (thumbnails, root) = store()
        thumbnails.sync(connection, "ses_1", listOf(toolMessage("m1", "a")))
        thumbnails.deleteAll()
        assertFalse(root.exists())
    }

    private fun sessionFiles(root: File): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    /** A `read` tool message; a null payload is a cached copy (metadata, no data URL). */
    private fun toolMessage(
        messageId: String,
        vararg payloads: String?,
    ): Message {
        val partId = "$messageId-prt"
        return Message(
            id = messageId,
            role = MessageRole.Assistant,
            text = "",
            parts =
                listOf(
                    MessagePart.Tool(
                        toolName = "read",
                        status = ToolStatus.Completed,
                        id = partId,
                        images =
                            payloads.mapIndexed { index, payload ->
                                MessagePart.File(
                                    id = ToolResultImages.id(partId, index),
                                    filename = "shot.png",
                                    mime = "image/png",
                                    kind = AttachmentKind.Image,
                                    dataUrl = payload?.let { "data:image/png;base64,$it" }.orEmpty(),
                                )
                            },
                    ),
                ),
            completedAtEpochMs = 2_000L,
        )
    }
}
