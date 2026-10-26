package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class AttachmentMimeTest {
    @Test
    fun sharedVectorsNormalizeTheSameOnBothPlatforms() {
        val cases = vectors()
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val declared = case["mime"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            val bytes = Base64.getDecoder().decode(case["bytesBase64"]!!.jsonPrimitive.content)
            assertEquals(
                case["name"]!!.jsonPrimitive.content,
                case["expected"]!!.jsonPrimitive.content,
                AttachmentMime.normalize(declared, bytes),
            )
        }
    }

    @Test
    fun onlyTheFirstFourKilobytesAreSniffed() {
        val bytes = ByteArray(AttachmentMime.SNIFF_BYTES) { 'a'.code.toByte() } + byteArrayOf(0)
        assertEquals(AttachmentMime.TEXT, AttachmentMime.normalize(null, bytes))
    }

    @Test
    fun mimeParametersAndCaseAreIgnored() {
        assertEquals("text/plain", AttachmentMime.normalize("Text/Markdown; charset=utf-8", byteArrayOf()))
        assertEquals("image/png", AttachmentMime.normalize("IMAGE/PNG", byteArrayOf()))
    }

    @Test
    fun extensionRecoversTheTypesTheAppReadsNatively() {
        assertEquals("image/jpeg", AttachmentMime.declaredFromExtension("shot.JPG"))
        assertEquals("application/pdf", AttachmentMime.declaredFromExtension("brief.pdf"))
        assertEquals(null, AttachmentMime.declaredFromExtension("archive.zip"))
    }

    private fun vectors(): List<JsonObject> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/attachment-mime/attachment-mime-cases.json")
            if (candidate.exists()) {
                val root = Json.parseToJsonElement(candidate.readText()).jsonObject
                return (root["cases"] as JsonArray).map { it.jsonObject }
            }
            dir = dir.parentFile
        }
        error("shared/attachment-mime/attachment-mime-cases.json not found above ${System.getProperty("user.dir")}")
    }
}
