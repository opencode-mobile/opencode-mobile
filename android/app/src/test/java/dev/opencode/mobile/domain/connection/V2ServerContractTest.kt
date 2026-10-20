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

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

internal const val V2_0_PATCH_BODY =
    """{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{}},"additionalProperties":false}"""

internal const val V2_1_PATCH_BODY =
    """{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{},"archived":{"anyOf":[{"type":"boolean"},{"type":"null"}]}},"additionalProperties":false}"""
