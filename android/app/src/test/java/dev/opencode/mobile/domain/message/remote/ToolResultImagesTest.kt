package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.domain.event.EventDomainAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool-result images on both protocols, from REST and from live events (#898). */
class ToolResultImagesTest {
    private val adapter = MessageDomainAdapter()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun v1CompletedAttachments_becomeToolImages_andOutputIsUnchanged() {
        val tool = v1Tool(attachments = """[$V1_PNG, $V1_JPEG]""")

        assertEquals("Image read successfully", tool.output)
        assertEquals(listOf("prt-file-1", "prt-file-2"), tool.images.map { it.id })
        assertEquals(listOf("screen.png", "photo.jpg"), tool.images.map { it.filename })
        assertEquals(listOf("image/png", "image/jpeg"), tool.images.map { it.mime })
        assertTrue(tool.images.all { it.kind == AttachmentKind.Image && it.dataUrl.startsWith("data:image/") })
    }

    @Test
    fun v1Attachments_withoutIds_getStableToolDerivedIds() {
        val tool = v1Tool(attachments = """[{"type":"file","mime":"image/png","url":"$PNG_URL"}]""")

        assertEquals(listOf("prt-tool:image:0"), tool.images.map { it.id })
        assertEquals("Image", tool.images.single().filename)
    }

    @Test
    fun v1NonImageAndNonDataAttachments_areIgnored() {
        val tool =
            v1Tool(
                attachments =
                    """
                    [
                      {"id":"pdf","type":"file","mime":"application/pdf","filename":"notes.pdf","url":"data:application/pdf;base64,JVBERi0x"},
                      {"id":"remote","type":"file","mime":"image/png","filename":"remote.png","url":"https://example.com/remote.png"},
                      {"id":"svg","type":"file","mime":"image/svg+xml","filename":"icon.svg","url":"data:image/svg+xml;base64,PHN2Zz4="}
                    ]
                    """,
            )

        assertTrue(tool.images.isEmpty())
        assertEquals("Image read successfully", tool.output)
    }

    @Test
    fun v1ToolWithoutAttachments_hasNoImages() {
        val tool = v1Tool(attachments = null)

        assertTrue(tool.images.isEmpty())
    }

    @Test
    fun v2RestContentFiles_becomeToolImages_andTextOutputIsUnchanged() {
        val tool = v2Tool(content = """[{"type":"text","text":"Read 2 images"},$V2_PNG,$V2_PDF,$V2_WEBP]""")

        assertEquals("Read 2 images", tool.output)
        assertEquals(listOf("call-1:image:0", "call-1:image:2"), tool.images.map { it.id })
        assertEquals(listOf("screen.png", "diagram.webp"), tool.images.map { it.filename })
        assertEquals(listOf("image/png", "image/webp"), tool.images.map { it.mime })
    }

    @Test
    fun v2RestToolWithOnlyText_hasNoImages() {
        val tool = v2Tool(content = """[{"type":"text","text":"done"}]""")

        assertEquals("done", tool.output)
        assertTrue(tool.images.isEmpty())
    }

    @Test
    fun v2LiveToolSuccess_carriesTheSameImagesAsRest() {
        val event =
            EventDomainAdapter(json).parseEvent(
                """{"type":"session.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"call-1","content":[{"type":"text","text":"Read 2 images"},$V2_PNG,$V2_PDF,$V2_WEBP],"executed":true}}""",
            )
        val partJson = requireNotNull(event?.rawProperties?.get("part")).jsonObject
        val live = adapter.toMessagePart(json.decodeFromJsonElement<MessagePartDto>(partJson)) as MessagePart.Tool
        val rest = v2Tool(content = """[{"type":"text","text":"Read 2 images"},$V2_PNG,$V2_PDF,$V2_WEBP]""")

        assertEquals(ToolStatus.Completed, live.status)
        assertEquals("Read 2 images", live.output)
        assertEquals(rest.images, live.images)
    }

    @Test
    fun v2LiveToolProgress_hasNoImages() {
        val event =
            EventDomainAdapter(json).parseEvent(
                """{"type":"session.tool.progress","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"call-1","metadata":{"title":"Reading"}}}""",
            )
        val partJson = requireNotNull(event?.rawProperties?.get("part")).jsonObject
        val live = adapter.toMessagePart(json.decodeFromJsonElement<MessagePartDto>(partJson)) as MessagePart.Tool

        assertTrue(live.images.isEmpty())
    }

    @Test
    fun image_prefersTheDataUrlMimeAndRejectsMismatchedHeaders() {
        val png = ToolResultImages.image("a", PNG_URL, mime = "image/jpeg", name = "/Users/example/project/shot.png")
        val broken = ToolResultImages.image("b", "data:image/png,not-base64", mime = "image/png", name = null)

        assertEquals("image/png", png?.mime)
        assertEquals("shot.png", png?.filename)
        assertEquals(null, broken)
    }

    private fun v1Tool(attachments: String?): MessagePart.Tool {
        val attachmentsField = attachments?.let { ""","attachments":${it.trimIndent()}""" }.orEmpty()
        val raw =
            """
            {
              "info":{"id":"msg-1","role":"assistant"},
              "parts":[
                {"id":"prt-tool","type":"tool","tool":"read","callID":"call-1",
                 "state":{"status":"completed","input":{"filePath":"/Users/example/project/screen.png"},
                          "output":"Image read successfully","title":"screen.png"$attachmentsField}}
              ]
            }
            """.trimIndent()
        return adapter
            .toMessage(json.decodeFromString<MessageDto>(raw))
            .parts
            .filterIsInstance<MessagePart.Tool>()
            .single()
    }

    private fun v2Tool(content: String): MessagePart.Tool {
        val raw =
            """
            [
              {
                "id":"msg-2",
                "type":"assistant",
                "content":[
                  {"type":"tool","id":"call-1","name":"read",
                   "state":{"status":"completed","input":{"filePath":"/Users/example/project/screen.png"},"content":$content}}
                ],
                "time":{"created":3}
              }
            ]
            """.trimIndent()
        return adapter
            .projectV2(json.decodeFromString<List<V2SessionMessageDto>>(raw))
            .single()
            .parts
            .filterIsInstance<MessagePart.Tool>()
            .single()
    }

    private companion object {
        const val PNG_URL =
            "data:image/png;base64," +
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        const val JPEG_URL = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2w=="
        const val WEBP_URL = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="

        const val V1_PNG = """{"id":"prt-file-1","type":"file","mime":"image/png","filename":"screen.png","url":"$PNG_URL"}"""
        const val V1_JPEG = """{"id":"prt-file-2","type":"file","mime":"image/jpeg","filename":"photo.jpg","url":"$JPEG_URL"}"""

        const val V2_PNG = """{"type":"file","uri":"$PNG_URL","mime":"image/png","name":"/Users/example/project/screen.png"}"""
        const val V2_PDF = """{"type":"file","uri":"data:application/pdf;base64,JVBERi0x","mime":"application/pdf","name":"notes.pdf"}"""
        const val V2_WEBP = """{"type":"file","uri":"$WEBP_URL","mime":"image/webp","name":"diagram.webp"}"""
    }
}
