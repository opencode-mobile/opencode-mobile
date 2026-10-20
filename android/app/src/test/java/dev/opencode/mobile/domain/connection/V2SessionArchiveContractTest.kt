package dev.opencode.mobile.domain.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SessionArchiveContractTest {
    @Test
    fun declaredWhenSessionPatchAcceptsArchived() {
        assertTrue(V2SessionArchiveContract.isDeclared(document(V2_1_PATCH_BODY)))
    }

    @Test
    fun notDeclaredOnOlderServerPatchBody() {
        assertFalse(V2SessionArchiveContract.isDeclared(document(V2_0_PATCH_BODY)))
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

        assertTrue(V2SessionArchiveContract.isDeclared(doc))
    }

    @Test
    fun notDeclaredWithoutSessionPatch() {
        assertFalse(V2SessionArchiveContract.isDeclared(parse("""{"paths":{"/api/session":{"get":{}}}}""")))
        assertFalse(V2SessionArchiveContract.isDeclared(parse("""{}""")))
    }

    private fun document(patchBody: String): JsonObject =
        parse(
            """
            {"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":
              {"schema":$patchBody}}}}}}}
            """.trimIndent(),
        )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

internal const val V2_0_PATCH_BODY =
    """{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{}},"additionalProperties":false}"""

internal const val V2_1_PATCH_BODY =
    """{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{},"archived":{"anyOf":[{"type":"boolean"},{"type":"null"}]}},"additionalProperties":false}"""
