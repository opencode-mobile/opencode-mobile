package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentDropRoutingTest {
    private val vectors: JsonObject by lazy { loadVectors() }

    @Test
    fun sharedRouteVectors() {
        val cases = vectors.getValue("routes").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val expected = case.getValue("expected").jsonObject
            val route = AttachmentDropRouting.route(item(case.getValue("item").jsonObject), context(case["context"]?.jsonObject))
            val wanted =
                expected["skipped"]?.jsonPrimitive?.content?.let(AttachmentDropRoute::Skipped)
                    ?: AttachmentDropRoute.Accepted
            assertEquals(case.name(), wanted, route)
        }
    }

    @Test
    fun sharedSummaryVectors() {
        val cases = vectors.getValue("summaries").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val summary =
                AttachmentDropRouting.summary(
                    items = case.getValue("items").jsonArray.map { item(it.jsonObject) },
                    context = context(case["context"]?.jsonObject),
                    countKnown = case["countKnown"]?.jsonPrimitive?.boolean ?: true,
                )
            val expected = case.getValue("expected")
            if (expected is JsonNull) {
                assertNull(case.name(), summary)
                return@forEach
            }
            val fields = expected.jsonObject
            assertEquals(
                case.name(),
                AttachmentDropSummary(
                    title = fields.getValue("title").jsonPrimitive.content,
                    subtitle = fields.getValue("subtitle").jsonPrimitive.content,
                    previews = fields.getValue("previews").jsonArray.map { kind(it.jsonPrimitive.content) },
                    rejected = fields.getValue("rejected").jsonPrimitive.boolean,
                ),
                summary,
            )
        }
    }

    @Test
    fun sharedPastedImageNameVectors() {
        val cases = vectors.getValue("pastedImageNames").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            assertEquals(
                case.name(),
                case.getValue("expected").jsonPrimitive.content,
                AttachmentDropRouting.pastedImageName(case.getValue("draft").jsonArray.map { it.jsonPrimitive.content }),
            )
        }
    }

    @Test
    fun kindFollowsTheTypeIngestionWillSee() {
        assertEquals(AttachmentDropKind.Image, AttachmentDropRouting.kind("image/png", null))
        assertEquals(AttachmentDropKind.Image, AttachmentDropRouting.kind("application/octet-stream", "shot.JPG"))
        assertEquals(AttachmentDropKind.Image, AttachmentDropRouting.kind("image/*", null))
        assertEquals(AttachmentDropKind.File, AttachmentDropRouting.kind("image/heic", "photo.heic"))
        assertEquals(AttachmentDropKind.Pdf, AttachmentDropRouting.kind("application/pdf", null))
        assertEquals(AttachmentDropKind.Text, AttachmentDropRouting.kind("text/markdown; charset=utf-8", "notes.md"))
        assertEquals(AttachmentDropKind.Text, AttachmentDropRouting.kind("application/json", null))
        assertEquals(AttachmentDropKind.Text, AttachmentDropRouting.kind(null, "device.log"))
        assertEquals(AttachmentDropKind.File, AttachmentDropRouting.kind("application/zip", "dump.zip"))
        assertEquals(AttachmentDropKind.File, AttachmentDropRouting.kind(null, null))
        assertEquals(AttachmentDropKind.Folder, AttachmentDropRouting.kind(AttachmentDropRouting.DIRECTORY_MIME, "designs"))
    }

    private fun JsonObject.name(): String = getValue("name").jsonPrimitive.content

    private fun item(json: JsonObject): AttachmentDropItem =
        AttachmentDropItem(
            kind = kind(json.getValue("kind").jsonPrimitive.content),
            filename = json["filename"]?.jsonPrimitive?.contentOrNull,
            byteCount = json["byteCount"]?.jsonPrimitive?.longOrNull,
        )

    private fun context(json: JsonObject?): AttachmentDropContext {
        if (json == null) return AttachmentDropContext()
        return AttachmentDropContext(
            imagesSupported = json["imagesSupported"]?.jsonPrimitive?.boolean ?: true,
            pdfSupported = json["pdfSupported"]?.jsonPrimitive?.boolean ?: true,
            uploadAvailable = json["uploadAvailable"]?.jsonPrimitive?.boolean ?: false,
            draft =
                (json["draft"] as? JsonArray).orEmpty().mapIndexed { index, entry ->
                    val fields = entry.jsonObject
                    Attachment(
                        id = "draft-$index",
                        filename = fields.getValue("filename").jsonPrimitive.content,
                        mime = "application/octet-stream",
                        kind = AttachmentKind.File,
                        dataUrl = "",
                        byteCount = fields.getValue("byteCount").jsonPrimitive.long,
                    )
                },
        )
    }

    private fun kind(name: String): AttachmentDropKind = AttachmentDropKind.entries.first { it.name.equals(name, ignoreCase = true) }

    private fun loadVectors(): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/attachment-drop/attachment-drop-cases.json")
            if (candidate.exists()) return Json.parseToJsonElement(candidate.readText()).jsonObject
            dir = dir.parentFile
        }
        error("shared/attachment-drop/attachment-drop-cases.json not found above ${System.getProperty("user.dir")}")
    }
}
