package dev.opencode.mobile.domain.feedback

import dev.opencode.mobile.domain.app.AppVersion
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.DetectedServer
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FeedbackPayloadBuilderTest {
    private val example: JsonObject by lazy {
        val text =
            checkNotNull(FeedbackPayloadBuilderTest::class.java.getResourceAsStream("/report.example.json")) {
                "shared/feedback/report.example.json is not on the test classpath"
            }.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    /** A profile whose values must never reach a report. */
    private val profile =
        ConnectionProfile(
            baseUrl = "https://zz-secret-host.example.invalid:4096/",
            username = "zz-secret-user",
            password = "zz-secret-password",
            name = "zz-secret-profile",
        )

    private val metadata =
        FeedbackMetadataSource.build(
            appVersion = AppVersion(version = "1.1.0", build = "214"),
            androidRelease = "16",
            model = "Pixel 9",
            locale = Locale.UK,
            screen = FeedbackScreen.SessionChat,
            serverProtocol = ServerProtocol.V2,
            serverVersion = "1.3.2",
        )

    private val report =
        FeedbackReport(
            reportId = "1f0de9a4-6c1b-4f3e-9d2a-7b8c9d0e1f2a",
            kind = FeedbackKind.Bug,
            message = "The session list still shows Running after unlock.",
            metadata = metadata,
            attachments =
                listOf(
                    FeedbackAttachment.screenshot(ByteArray(312)),
                    FeedbackAttachment.logs(ByteArray(41)),
                ),
        )

    @Test
    fun document_matchesExampleKeysAndTypes() {
        val document = FeedbackPayloadBuilder.document(report, uid = "anon-uid")

        // createdAt is a server timestamp transform; contactEmail is optional and not collected.
        val expected = example.keys - setOf("\$comment", "createdAt", "contactEmail")
        assertEquals(expected, document.keys)
        assertSameShape(example, document, path = "")
    }

    @Test
    fun document_carriesAndroidMetadata() {
        val document = FeedbackPayloadBuilder.document(report, uid = "anon-uid")
        val app = document.getValue("app").jsonObject
        val context = document.getValue("context").jsonObject

        assertEquals("anon-uid", document.string("uid"))
        assertEquals("bug", document.string("kind"))
        assertEquals("android", app.string("platform"))
        assertEquals("1.1.0", app.string("version"))
        assertEquals("214", app.string("build"))
        assertEquals("Android 16", app.string("osVersion"))
        assertEquals("Pixel 9", app.string("device"))
        assertEquals("en-GB", app.string("locale"))
        assertEquals("session-chat", context.string("screen"))
        assertEquals("v2", context.string("serverProtocol"))
        assertEquals("1.3.2", context.string("serverVersion"))
        assertEquals("opencode", context.string("engine"))
    }

    @Test
    fun document_neverContainsServerAddressOrCredentials() =
        runTest {
            val manager =
                ActiveConnectionManager(
                    SingleProfileStore(),
                    object : ConnectionProbing {
                        override suspend fun probe(connection: ServerConnection) = ServerProtocol.V2

                        override suspend fun probeServer(connection: ServerConnection) =
                            DetectedServer(ServerProtocol.V2, version = "1.3.2")
                    },
                )
            manager.applyProfile(profile)
            manager.probeActive()
            val connected =
                report.copy(
                    metadata =
                        FeedbackMetadataSource.fromActiveConnection(
                            activeConnectionManager = manager,
                            appVersion = AppVersion(version = "1.1.0", build = "214"),
                            androidRelease = "16",
                            model = "Pixel 9",
                            locale = Locale.UK,
                            screen = FeedbackScreen.SessionChat,
                        ),
                )

            assertEquals(metadata, connected.metadata)
            assertNoSecrets(connected)
        }

    private fun assertNoSecrets(report: FeedbackReport) {
        val text = FeedbackPayloadBuilder.document(report, uid = "anon-uid").toString()
        val share = FeedbackSharePayload.from(report)

        listOf(profile.baseUrl, "zz-secret-host", profile.username, profile.password, profile.name).forEach { secret ->
            assertFalse("report contains $secret", text.contains(secret))
            assertFalse("share text contains $secret", share.text.contains(secret))
        }
        val forbiddenKeys = setOf("baseUrl", "url", "host", "username", "password", "profile", "name")
        val topKeys = FeedbackPayloadBuilder.document(report, uid = "u").keys
        assertTrue(topKeys.intersect(forbiddenKeys).isEmpty())
    }

    @Test
    fun attachments_haveContractNamesTypesAndUploadedSizes() {
        val attachments = FeedbackPayloadBuilder.document(report, uid = "u").getValue("attachments").jsonArray

        assertEquals(2, attachments.size)
        val screenshot = attachments[0].jsonObject
        val logs = attachments[1].jsonObject
        assertEquals("screenshot.jpg", screenshot.string("name"))
        assertEquals("image/jpeg", screenshot.string("contentType"))
        assertEquals(312, screenshot.getValue("size").jsonPrimitive.int)
        assertEquals("logs.jsonl.gz", logs.string("name"))
        assertEquals("application/gzip", logs.string("contentType"))
        assertEquals(41, logs.getValue("size").jsonPrimitive.int)
    }

    @Test
    fun document_omitsUnknownServerFactsAndCapsLongStrings() {
        val bare =
            report.copy(
                attachments = emptyList(),
                metadata = metadata.copy(serverProtocol = null, serverVersion = null, device = "x".repeat(100)),
                message = "m".repeat(6000),
            )

        val document = FeedbackPayloadBuilder.document(bare, uid = "u")
        val context = document.getValue("context").jsonObject

        assertEquals(setOf("screen", "engine"), context.keys)
        assertEquals(
            64,
            document
                .getValue("app")
                .jsonObject
                .string("device")
                .length,
        )
        assertEquals(5000, document.string("message").length)
        assertTrue(document.getValue("attachments").jsonArray.isEmpty())
    }

    @Test
    fun reference_isFirstSixHexUppercased() {
        assertEquals("1F0DE9", report.reference)
        val id = FeedbackReport.newReportId()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
    }

    @Test
    fun firestoreValues_encodeEveryJsonType() {
        val encoded = FirestoreValues.fields(FeedbackPayloadBuilder.document(report, uid = "u"))

        assertEquals("u", encoded.getValue("uid").jsonObject.string("stringValue"))
        val app =
            encoded
                .getValue("app")
                .jsonObject
                .getValue("mapValue")
                .jsonObject
                .getValue("fields")
                .jsonObject
        assertEquals("android", app.getValue("platform").jsonObject.string("stringValue"))
        val values =
            encoded
                .getValue("attachments")
                .jsonObject
                .getValue("arrayValue")
                .jsonObject
                .getValue("values")
                .jsonArray
        val size =
            values[0]
                .jsonObject
                .getValue("mapValue")
                .jsonObject
                .getValue("fields")
                .jsonObject
                .getValue("size")
                .jsonObject
        assertEquals("312", size.string("integerValue"))
    }

    private fun assertSameShape(
        expected: JsonElement,
        actual: JsonElement,
        path: String,
    ) {
        when (expected) {
            is JsonObject -> {
                assertTrue("$path should be an object", actual is JsonObject)
                (actual as JsonObject).forEach { (key, value) ->
                    val expectedValue = expected[key]
                    assertTrue("$path.$key is not in report.example.json", expectedValue != null)
                    assertSameShape(expectedValue!!, value, "$path.$key")
                }
            }
            is JsonArray -> {
                assertTrue("$path should be an array", actual is JsonArray)
                val template = expected.firstOrNull() ?: return
                (actual as JsonArray).forEachIndexed { index, item -> assertSameShape(template, item, "$path[$index]") }
            }
            is JsonPrimitive -> {
                assertTrue("$path should be a primitive", actual is JsonPrimitive)
                assertEquals("$path string-ness", expected.isString, (actual as JsonPrimitive).isString)
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private class SingleProfileStore : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) = Unit

        override suspend fun removeWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) = Unit

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) = Unit

        override suspend fun clearAll() {
            profile = null
        }
    }
}
