package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolImageThumbnails
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tool images in the session cache: metadata plus an on-disk thumbnail, never the base64 payload. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheToolImagesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    private val connection = "https://example.com/|opencode"

    @Test
    fun codec_keepsToolImageMetadataAndThumbnailFlagButNoBase64() {
        val messages = listOf(v2ReadResult())
        val image = (messages.single().parts.single() as MessagePart.Tool).images.single()
        assertTrue(image.dataUrl.startsWith("data:image/png;base64,"))

        val raw = SessionCacheCodec.encodeMessages(messages, json, hasThumbnail = { it.id == image.id })

        assertFalse(raw.contains(PNG_BASE64))
        assertFalse(raw.contains("base64,"))
        val withThumbnail = SessionCacheCodec.decodeMessages(raw, json, thumbnailPath = { "/thumbs/$it.jpg" })!!
        val restored = (withThumbnail.single().parts.single() as MessagePart.Tool).images.single()
        assertEquals(image.id, restored.id)
        assertEquals("screen.png", restored.filename)
        assertEquals("image/png", restored.mime)
        assertEquals(AttachmentKind.Image, restored.kind)
        assertEquals("", restored.dataUrl)
        assertEquals("/thumbs/${image.id}.jpg", restored.thumbnailPath)

        // An evicted thumbnail file reads back as no thumbnail.
        val evicted = SessionCacheCodec.decodeMessages(raw, json, thumbnailPath = { null })!!
        assertNull((evicted.single().parts.single() as MessagePart.Tool).images.single().thumbnailPath)
    }

    @Test
    fun codec_unflaggedImageNeverResolvesAThumbnail() {
        val raw = SessionCacheCodec.encodeMessages(listOf(v2ReadResult()), json, hasThumbnail = { false })
        val decoded = SessionCacheCodec.decodeMessages(raw, json, thumbnailPath = { "/thumbs/$it.jpg" })!!
        assertNull((decoded.single().parts.single() as MessagePart.Tool).images.single().thumbnailPath)
    }

    @Test
    fun codec_readsRowsWrittenBeforeToolImages() {
        val legacy =
            """{"schemaVersion":1,"messages":{"messages":[{"id":"a1","role":"assistant","text":"",""" +
                """"parts":[{"kind":"tool","id":"prt-tool","toolName":"read","status":"completed"}]}]}}"""
        val decoded = SessionCacheCodec.decodeMessages(legacy, json)!!
        assertTrue((decoded.single().parts.single() as MessagePart.Tool).images.isEmpty())
    }

    @Test
    fun messageHandle_savesThumbnailFileAndRestoresIt() =
        runTest {
            val thumbnails = thumbnails()
            val cache = cache(thumbnails, InMemorySessionCacheStore())
            val handle = cache.openMessages("ses_1", "/Users/example/project")
            val messages = listOf(v2ReadResult())
            handle.seed()
            handle.note(messages)
            testScheduler.runCurrent()
            handle.flush()

            val seeded = handle.seed()!!
            val image = (seeded.single().parts.single() as MessagePart.Tool).images.single()
            assertEquals("", image.dataUrl)
            assertNotNull(image.thumbnailPath)
            val path = image.thumbnailPath!!
            assertTrue(File(path).isFile)
            assertTrue(path.contains(ToolImageThumbnails.connectionFolder(connection)))
            assertFalse(path.contains("example.com"))
        }

    @Test
    fun purges_deleteMatchingThumbnailFolders() =
        runTest {
            val thumbnails = thumbnails()
            val store = InMemorySessionCacheStore()
            val cache = cache(thumbnails, store)
            val kept = cache.openMessages("ses_keep", "/Users/example/other")
            val purged = cache.openMessages("ses_purged", "/Users/example/project")
            listOf(kept, purged).forEach { handle ->
                handle.seed()
                handle.note(listOf(v2ReadResult()))
                testScheduler.runCurrent()
                handle.flush()
            }
            assertTrue(thumbnails.sessionDirectory(connection, "ses_purged").isDirectory)

            cache.purgeWorktree("https://example.com/", "/Users/example/project")

            assertFalse(thumbnails.sessionDirectory(connection, "ses_purged").exists())
            assertTrue(thumbnails.sessionDirectory(connection, "ses_keep").isDirectory)

            cache.purgeConnection(connection, "https://example.com/")
            assertFalse(thumbnails.sessionDirectory(connection, "ses_keep").exists())
        }

    @Test
    fun clearAll_deletesEveryThumbnail() =
        runTest {
            val thumbnails = thumbnails()
            val cache = cache(thumbnails, InMemorySessionCacheStore())
            val handle = cache.openMessages("ses_1", "/Users/example/project")
            handle.seed()
            handle.note(listOf(v2ReadResult()))
            testScheduler.runCurrent()
            handle.flush()

            cache.clearAll()

            assertFalse(File(temp.root, ToolImageThumbnails.DIRECTORY_NAME).exists())
        }

    @Test
    fun launchMaintenance_sweepsOrphanFolders() =
        runTest {
            val thumbnails = thumbnails()
            val store = InMemorySessionCacheStore()
            thumbnails.sync(connection, "ses_orphan", listOf(v2ReadResult()))
            assertTrue(thumbnails.sessionDirectory(connection, "ses_orphan").isDirectory)
            val cache = cache(thumbnails, store)

            cache.startThumbnailMaintenance()
            testScheduler.runCurrent()

            assertFalse(thumbnails.sessionDirectory(connection, "ses_orphan").exists())
        }

    private fun kotlinx.coroutines.test.TestScope.cache(
        thumbnails: ToolImageThumbnails,
        store: SessionCacheStore,
    ) = DefaultSessionCache(
        store = store,
        connectionIdProvider = { connection },
        scope = backgroundScope,
        json = json,
        debounceMs = 60_000L,
        thumbnails = thumbnails,
        thumbnailDispatcher = Dispatchers.Unconfined,
    )

    /** Real store on a temp folder; the fake encoder stands in for the platform JPEG codec. */
    private fun thumbnails() =
        ToolImageThumbnails(
            root = File(temp.root, ToolImageThumbnails.DIRECTORY_NAME),
            encode = { _, _, _ -> ByteArray(2_048) { 7 } },
        )

    /** A v2 `read` result that returned one image, mapped through the real adapter. */
    private fun v2ReadResult(): Message {
        val dto =
            json.decodeFromString<V2SessionMessageDto>(
                """
                {
                  "id":"msg_read","type":"assistant","agent":"build",
                  "model":{"id":"gpt-5","providerID":"openai"},
                  "time":{"created":1,"completed":2},
                  "content":[{
                    "type":"tool","id":"prt_read","callID":"call_1","name":"read",
                    "state":{
                      "status":"completed",
                      "input":{"filePath":"/Users/example/project/screen.png"},
                      "content":[
                        {"type":"text","text":"Image read successfully"},
                        {"type":"file","mime":"image/png","uri":"data:image/png;base64,$PNG_BASE64","name":"/Users/example/project/screen.png"}
                      ]
                    }
                  }]
                }
                """.trimIndent(),
            )
        val message = MessageDomainAdapter().projectV2(listOf(dto)).single()
        val tool = message.parts.filterIsInstance<MessagePart.Tool>().single()
        check(tool.images.size == 1) { "fixture must map to one tool image" }
        return message.copy(role = MessageRole.Assistant, parts = listOf(tool.copy(status = ToolStatus.Completed)))
    }

    private companion object {
        const val PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
