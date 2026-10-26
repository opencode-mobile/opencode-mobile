package dev.opencode.mobile.domain.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ServerContractTest {
    @Test
    fun declaredWhenSessionPatchAcceptsArchived() {
        assertTrue(V2ServerContract.declaresSessionArchive(document(V2_1_PATCH_BODY)))
    }

    @Test
    fun notDeclaredOnOlderServerPatchBody() {
        assertFalse(V2ServerContract.declaresSessionArchive(document(V2_0_PATCH_BODY)))
    }

    @Test
    fun followsComponentReference() {
        val doc =
            parse(
                """
                {"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":
                  {"schema":{"${'$'}ref":"#/components/schemas/SessionUpdate"}}}}}}},
                 "components":{"schemas":{"SessionUpdate":$V2_1_PATCH_BODY}}}
                """.trimIndent(),
            )

        assertTrue(V2ServerContract.declaresSessionArchive(doc))
    }

    @Test
    fun notDeclaredWithoutSessionPatch() {
        assertFalse(V2ServerContract.declaresSessionArchive(parse("""{"paths":{"/api/session":{"get":{}}}}""")))
        assertFalse(V2ServerContract.declaresSessionArchive(parse("""{}""")))
    }

    @Test
    fun turnDiffDeclaredWhenSessionDiffRouteExists() {
        val doc = parse("""{"paths":{"/api/session/{sessionID}/diff":{"get":{}}}}""")

        assertTrue(V2ServerContract.declaresSessionTurnDiff(doc))
        assertEquals(V2ServerFeatures(sessionArchive = false, sessionTurnDiff = true), V2ServerContract.features(doc))
    }

    @Test
    fun sessionGenerateDeclaredWhenGenerateRouteExists() {
        val doc = parse("""{"paths":{"/api/session/{sessionID}/generate":{"post":{}}}}""")

        assertTrue(V2ServerContract.declaresSessionGenerate(doc))
        assertEquals(V2ServerFeatures(sessionGenerate = true), V2ServerContract.features(doc))
        assertTrue(ServerCapabilities.forProtocol(ServerProtocol.V2, V2ServerContract.features(doc)).supportsSideQuestion)
    }

    @Test
    fun sessionGenerateNotDeclaredWithoutRoute() {
        assertFalse(V2ServerContract.declaresSessionGenerate(parse("""{"paths":{"/api/experimental/generate":{"post":{}}}}""")))
        assertFalse(V2ServerContract.declaresSessionGenerate(parse("""{}""")))
        assertFalse(ServerCapabilities.forProtocol(ServerProtocol.V2).supportsSideQuestion)
        assertFalse(
            ServerCapabilities.forProtocol(ServerProtocol.V1, V2ServerFeatures(sessionGenerate = true)).supportsSideQuestion,
        )
    }

    @Test
    fun turnDiffNotDeclaredOnOlderServer() {
        assertFalse(V2ServerContract.declaresSessionTurnDiff(document(V2_0_PATCH_BODY)))
        assertFalse(V2ServerContract.declaresSessionTurnDiff(parse("""{}""")))
    }

    private fun document(patchBody: String): JsonObject =
        parse(
            """
            {"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":
              {"schema":$patchBody}}}}}}}
            """.trimIndent(),
        )

    @Test
    fun attachmentUploadDeclaredForTheKnownWriteShape() {
        assertTrue(V2ServerContract.declaresAttachmentUpload(parse(UPLOAD_DOCUMENT)))
    }

    @Test
    fun attachmentUploadNotDeclaredWhenTheShapeChanges() {
        val broken =
            listOf(
                UPLOAD_DOCUMENT.replace("/api/experimental/fs/write", "/api/experimental/fs/put"),
                UPLOAD_DOCUMENT.replace("\"required\":true}", "\"required\":false}"),
                UPLOAD_DOCUMENT.replace("\"name\":\"path\"", "\"name\":\"target\""),
                UPLOAD_DOCUMENT.replace("application/octet-stream", "application/json"),
                UPLOAD_DOCUMENT.replace("\"path\":{\"type\":\"string\"}", "\"file\":{\"type\":\"string\"}"),
                UPLOAD_DOCUMENT.replace("\"200\"", "\"201\""),
                UPLOAD_DOCUMENT.replace("\"post\"", "\"put\""),
            )
        broken.forEachIndexed { index, document ->
            assertFalse("variant $index", V2ServerContract.declaresAttachmentUpload(parse(document)))
        }
        assertFalse(V2ServerContract.declaresAttachmentUpload(parse("{}")))
    }

    @Test
    fun attachmentUploadFollowsAllOfAndRequiresAJsonAnswer() {
        val wrapped =
            UPLOAD_DOCUMENT.replace(
                "\"${'$'}ref\":\"#/components/schemas/FileSystem.Write\"",
                "\"allOf\":[{\"${'$'}ref\":\"#/components/schemas/FileSystem.Write\"}]",
            )
        assertTrue(V2ServerContract.declaresAttachmentUpload(parse(wrapped)))
        val nested =
            UPLOAD_DOCUMENT.replace(
                "\"data\":{\"${'$'}ref\":\"#/components/schemas/FileSystem.Write\"}",
                "\"data\":{\"oneOf\":[{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}]}",
            )
        assertTrue(V2ServerContract.declaresAttachmentUpload(parse(nested)))
        // A 200 that is not JSON cannot carry data.path.
        assertFalse(V2ServerContract.declaresAttachmentUpload(parse(UPLOAD_DOCUMENT.replace("\"application/json\":{\"schema\"", "\"text/plain\":{\"schema\""))))
    }

    @Test
    fun tmpDirectoryComesFromApiInfoPaths() {
        assertEquals("/var/tmp", V2ServerContract.tmpDirectory(parse("""{"version":"local","paths":{"tmp":"/var/tmp"}}""")))
        assertEquals(null, V2ServerContract.tmpDirectory(parse("""{"version":"local","paths":{}}""")))
        assertEquals(null, V2ServerContract.tmpDirectory(parse("""{"paths":{"tmp":" "}}""")))
        assertEquals(null, V2ServerContract.tmpDirectory(parse("""{"version":"2.0.9"}""")))
    }

    @Test
    fun uploadCapabilityNeedsBothTheRouteAndTmp() {
        assertFalse(ServerCapabilities.forProtocol(ServerProtocol.V2, V2ServerFeatures()).supportsAttachmentUpload)
        assertTrue(
            ServerCapabilities
                .forProtocol(ServerProtocol.V2, V2ServerFeatures(attachmentUploadTmp = "/tmp"))
                .supportsAttachmentUpload,
        )
        assertFalse(
            ServerCapabilities
                .forProtocol(ServerProtocol.V1, V2ServerFeatures(attachmentUploadTmp = "/tmp"))
                .supportsAttachmentUpload,
        )
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

internal const val V2_0_PATCH_BODY =
    """{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{}},"additionalProperties":false}"""

internal const val V2_1_PATCH_BODY =
    """{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{},"archived":{"anyOf":[{"type":"boolean"},{"type":"null"}]}},"additionalProperties":false}"""

/** Trimmed from opencode's `/openapi.json`: the write route with a `$ref` answer, as the server emits it. */
private val UPLOAD_DOCUMENT =
    """
    {"paths":{"/api/experimental/fs/write":{"post":{
      "parameters":[
        {"name":"location","in":"query","required":false,"style":"deepObject","schema":{"type":"object"}},
        {"name":"path","in":"query","schema":{"type":"string"},"required":true}],
      "requestBody":{"content":{"application/octet-stream":{"schema":{"type":"string","format":"binary"}}},"required":true},
      "responses":{"200":{"description":"Success","content":{"application/json":{"schema":{"type":"object","properties":{
        "location":{"type":"object"},"data":{"${'$'}ref":"#/components/schemas/FileSystem.Write"}}}}}}}}}},
     "components":{"schemas":{"FileSystem.Write":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}}
    """.trimIndent()
