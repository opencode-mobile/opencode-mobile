package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list route's answers, drift, and the card's artifact (app-plan.md, Artifacts (M8)). */
class ArtifactsTest {
    private fun item(
        id: String = "art_1",
        session: String? = "ses_today",
        updated: Long = 2_000,
        provider: String = "local",
        title: String = "Login page preview",
    ): String {
        val sid = session?.let { "\"sessionID\":\"$it\"," }.orEmpty()
        return """{"id":"$id","provider":"$provider",$sid"title":"$title","entry":"index.html",""" +
            """"url":"/api/experimental/artifacts/$id/view/","time":{"created":$updated,"updated":$updated}}"""
    }

    private fun body(vararg items: String) = """{"location":{"directory":"/tmp/p"},"data":[${items.joinToString(",")}]}"""

    @Test
    fun twoHundredWithAListIsOn() {
        val answer = ArtifactListAnswer.of(200, body(item()))
        assertTrue(answer is ArtifactListAnswer.On)
        val info = (answer as ArtifactListAnswer.On).items.single()
        assertEquals("art_1", info.id)
        assertEquals("ses_today", info.sessionId)
        assertEquals(2_000L, info.updated)
        assertTrue(info.local)
    }

    @Test
    fun anEmptyListIsStillOn() = assertEquals(ArtifactListAnswer.On(emptyList()), ArtifactListAnswer.of(200, body()))

    @Test
    fun forbiddenIsOff() {
        val disabled = """{"_tag":"ForbiddenError","message":"Artifacts are disabled; set experimental.artifacts in config"}"""
        assertEquals(ArtifactListAnswer.Off, ArtifactListAnswer.of(403, disabled))
        // The wording isn't relied on.
        assertEquals(ArtifactListAnswer.Off, ArtifactListAnswer.of(403, "{}"))
    }

    @Test
    fun aBareNotFoundOrAnUnknownShapeIsChanged() {
        assertEquals(ArtifactListAnswer.Changed, ArtifactListAnswer.of(404, ""))
        assertEquals(ArtifactListAnswer.Changed, ArtifactListAnswer.of(405, null))
        assertEquals(ArtifactListAnswer.Changed, ArtifactListAnswer.of(400, """{"_tag":"BadRequest"}"""))
        assertEquals(ArtifactListAnswer.Changed, ArtifactListAnswer.of(200, """{"items":[]}"""))
        assertEquals(ArtifactListAnswer.Changed, ArtifactListAnswer.of(200, body("""{"id":"x1","title":"T"}""")))
        assertEquals(ArtifactListAnswer.Changed, ArtifactListAnswer.of(200, "not json"))
    }

    @Test
    fun signedOutAndUnknownKeepTheLastState() {
        assertEquals(ArtifactListAnswer.SignedOut, ArtifactListAnswer.of(401, ""))
        assertEquals(ArtifactListAnswer.Unknown, ArtifactListAnswer.of(500, ""))
        assertEquals(ArtifactListAnswer.Unknown, ArtifactListAnswer.of(503, ""))
        assertEquals(ArtifactListAnswer.Unknown, ArtifactListAnswer.of(null, null))
        assertEquals(ArtifactListAnswer.Unknown, ArtifactListAnswer.of(404, """{"_tag":"LocationNotFound","message":"x"}"""))
    }

    private val openapi =
        """
        {"paths":{
          "/api/experimental/artifacts":{"get":{},"post":{}},
          "/api/experimental/artifacts/{artifactID}/view/*":{"get":{}}},
         "components":{"schemas":{
          "Artifact.Info":{"type":"object","properties":{"id":{},"provider":{},"sessionID":{},"title":{},"entry":{},"url":{},
            "time":{"${'$'}ref":"#/components/schemas/Artifact.Time"}}},
          "Artifact.Time":{"type":"object","properties":{"created":{},"updated":{}}}}}}
        """.trimIndent()

    private fun parse(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun theRoutesAndTheShapeAreDeclared() = assertTrue(ArtifactsDeclared.of(parse(openapi)))

    @Test
    fun aMissingRouteOrFieldIsNotDeclared() {
        assertFalse(ArtifactsDeclared.of(parse(openapi.replace("/view/*", "/show"))))
        assertFalse(ArtifactsDeclared.of(parse(openapi.replace("\"title\":{},", ""))))
        assertFalse(ArtifactsDeclared.of(parse(openapi.replace("\"updated\":{}", "\"changed\":{}"))))
        assertFalse(ArtifactsDeclared.of(parse("""{"paths":{}}""")))
    }

    private fun infos(vararg items: String) = (ArtifactListAnswer.of(200, body(*items)) as ArtifactListAnswer.On).items

    @Test
    fun theNewestOfTheLatestRunMakesTheTile() {
        val items = infos(item("art_old", updated = 900), item("art_a", updated = 1_500), item("art_b", updated = 1_800, title = "B"))
        assertEquals(ArtifactTile("art_b", "B"), NewestArtifact.of(items, "ses_today", emptySet(), runStart = 1_000))
    }

    @Test
    fun anArtifactFromAnEarlierRunMakesNoTile() =
        assertNull(NewestArtifact.of(infos(item(updated = 900)), "ses_today", emptySet(), runStart = 1_000))

    @Test
    fun anArtifactUpdatedAtTheRunsStartCounts() =
        assertEquals("art_1", NewestArtifact.of(infos(item(updated = 1_000)), "ses_today", emptySet(), 1_000)?.id)

    @Test
    fun aSubagentsArtifactMakesTheTile() =
        assertEquals("art_s", NewestArtifact.of(infos(item("art_s", session = "ses_child")), "ses_today", setOf("ses_child"), 1_000)?.id)

    @Test
    fun anotherSessionsOrNoSessionOrAnotherProviderMakesNoTile() {
        assertNull(NewestArtifact.of(infos(item(session = "ses_other")), "ses_today", emptySet(), 1_000))
        assertNull(NewestArtifact.of(infos(item(session = null)), "ses_today", emptySet(), 1_000))
        assertNull(NewestArtifact.of(infos(item(provider = "cloud")), "ses_today", emptySet(), 1_000))
    }

    @Test
    fun noRunYetMakesNoTile() = assertNull(NewestArtifact.of(infos(item()), "ses_today", emptySet(), runStart = null))
}
