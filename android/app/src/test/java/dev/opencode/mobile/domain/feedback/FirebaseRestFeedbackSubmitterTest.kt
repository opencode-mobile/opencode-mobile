package dev.opencode.mobile.domain.feedback

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FirebaseRestFeedbackSubmitterTest {
    private val config =
        FeedbackFirebaseConfig(
            apiKey = "test-key",
            projectId = "opencode-mobile-test",
            storageBucket = "opencode-mobile-test.firebasestorage.app",
            androidPackage = "dev.opencode.mobile",
            androidCertSha1 = "AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12",
        )

    private val report =
        FeedbackReport(
            reportId = "1f0de9a4-6c1b-4f3e-9d2a-7b8c9d0e1f2a",
            kind = FeedbackKind.Feedback,
            message = "Add a dark launcher icon.",
            metadata =
                FeedbackMetadata(
                    version = "1.1.0",
                    build = "214",
                    osVersion = "Android 16",
                    device = "Pixel 9",
                    locale = "en-GB",
                    screen = FeedbackScreen.Settings,
                    serverProtocol = "v1",
                    serverVersion = "1.18.4",
                ),
            attachments =
                listOf(
                    FeedbackAttachment.screenshot(byteArrayOf(1, 2, 3)),
                    FeedbackAttachment.logs(byteArrayOf(4, 5)),
                ),
        )

    private val requests = mutableListOf<HttpRequestData>()
    private val now = 10_000_000L
    private val cooldown = MemoryCooldownStore()

    @Test
    fun submit_refreshesUploadsThenCommitsWithKeyRestrictionHeaders() =
        runTest {
            val store = MemoryIdentityStore("refresh-1")
            val submitter = submitter(store) { request -> happyPath(request) }

            val result = submitter.submit(report)

            assertEquals(FeedbackSubmitResult.Sent("1F0DE9"), result)
            assertEquals(
                listOf(
                    "https://securetoken.googleapis.com/v1/token?key=test-key",
                    "https://firebasestorage.googleapis.com/v0/b/opencode-mobile-test.firebasestorage.app/o" +
                        "?name=feedback%2Fanon-uid%2F1f0de9a4-6c1b-4f3e-9d2a-7b8c9d0e1f2a%2Fscreenshot.jpg",
                    "https://firebasestorage.googleapis.com/v0/b/opencode-mobile-test.firebasestorage.app/o" +
                        "?name=feedback%2Fanon-uid%2F1f0de9a4-6c1b-4f3e-9d2a-7b8c9d0e1f2a%2Flogs.jsonl.gz",
                    "https://firestore.googleapis.com/v1/projects/opencode-mobile-test/databases/(default)/documents:commit",
                ),
                requests.map { it.url.toString() },
            )
            requests.forEach { request ->
                assertEquals("app-check-token", request.headers["X-Firebase-AppCheck"])
                assertEquals("dev.opencode.mobile", request.headers["X-Android-Package"])
                assertEquals(config.androidCertSha1, request.headers["X-Android-Cert"])
            }
            assertEquals(
                "grant_type=refresh_token&refresh_token=refresh-1",
                requests[0].bodyText(),
            )
            assertEquals("Firebase id-token", requests[1].headers[HttpHeaders.Authorization])
            assertEquals("image/jpeg", requests[1].body.contentType.toString())
            assertEquals("application/gzip", requests[2].body.contentType.toString())
            assertEquals("Bearer id-token", requests[3].headers[HttpHeaders.Authorization])
            assertEquals("refresh-2", store.token)
        }

    @Test
    fun commitBody_createsReportAndStampsRateLimit() =
        runTest {
            submitter(MemoryIdentityStore("refresh-1")) { request -> happyPath(request) }.submit(report)

            val body = Json.parseToJsonElement(requests.last().bodyText()).jsonObject
            val writes = body.getValue("writes").jsonArray
            assertEquals(2, writes.size)

            val feedback = writes[0].jsonObject
            val documents = "projects/opencode-mobile-test/databases/(default)/documents"
            val update = feedback.getValue("update").jsonObject
            assertEquals("$documents/feedback/${report.reportId}", update.string("name"))
            val fields = update.getValue("fields").jsonObject
            assertEquals(setOf("uid", "kind", "message", "app", "context", "attachments"), fields.keys)
            assertEquals("anon-uid", fields.getValue("uid").jsonObject.string("stringValue"))
            assertEquals("feedback", fields.getValue("kind").jsonObject.string("stringValue"))
            assertFalse(fields.containsKey("createdAt"))
            val createdAt =
                feedback
                    .getValue("updateTransforms")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("createdAt", createdAt.string("fieldPath"))
            assertEquals("REQUEST_TIME", createdAt.string("setToServerValue"))
            assertEquals(
                false,
                feedback
                    .getValue("currentDocument")
                    .jsonObject
                    .getValue("exists")
                    .jsonPrimitive.boolean,
            )

            val rateLimit = writes[1].jsonObject
            val rateUpdate = rateLimit.getValue("update").jsonObject
            assertEquals("$documents/rateLimits/anon-uid", rateUpdate.string("name"))
            assertEquals(
                report.reportId,
                rateUpdate
                    .getValue("fields")
                    .jsonObject
                    .getValue("lastReportId")
                    .jsonObject
                    .string("stringValue"),
            )
            assertEquals(
                listOf("lastReportId"),
                rateLimit
                    .getValue("updateMask")
                    .jsonObject
                    .getValue("fieldPaths")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            val lastReportAt =
                rateLimit
                    .getValue("updateTransforms")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("lastReportAt", lastReportAt.string("fieldPath"))
            assertEquals("REQUEST_TIME", lastReportAt.string("setToServerValue"))
        }

    @Test
    fun submit_signsUpWhenNoRefreshTokenIsStored() =
        runTest {
            val store = MemoryIdentityStore(null)

            val result = submitter(store) { request -> happyPath(request) }.submit(report.copy(attachments = emptyList()))

            assertEquals(FeedbackSubmitResult.Sent("1F0DE9"), result)
            assertEquals(
                listOf("identitytoolkit.googleapis.com", "firestore.googleapis.com"),
                requests.map { it.url.host },
            )
            assertEquals("""{"returnSecureToken":true}""", requests[0].bodyText())
            assertEquals("/v1/accounts:signUp", requests[0].url.encodedPath)
            assertEquals("signup-refresh", store.token)
        }

    @Test
    fun submit_signsUpAgainAfterExpiredRefreshToken() =
        runTest {
            val store = MemoryIdentityStore("expired")
            val submitter =
                submitter(store) { request ->
                    if (request.url.host == "securetoken.googleapis.com") {
                        jsonResponse(
                            """{"error":{"code":400,"message":"TOKEN_EXPIRED","status":"INVALID_ARGUMENT"}}""",
                            HttpStatusCode.BadRequest,
                        )
                    } else {
                        happyPath(request)
                    }
                }

            val result = submitter.submit(report)

            assertEquals(FeedbackSubmitResult.Sent("1F0DE9"), result)
            assertEquals(
                listOf(
                    "securetoken.googleapis.com",
                    "identitytoolkit.googleapis.com",
                    "firebasestorage.googleapis.com",
                    "firebasestorage.googleapis.com",
                    "firestore.googleapis.com",
                ),
                requests.map { it.url.host },
            )
            assertEquals("signup-refresh", store.token)
        }

    @Test
    fun submit_failsWithoutSigningUpForOtherRefreshErrors() =
        runTest {
            val store = MemoryIdentityStore("refresh-1")
            val submitter =
                submitter(store) {
                    jsonResponse("""{"error":{"code":400,"message":"INVALID_GRANT_TYPE"}}""", HttpStatusCode.BadRequest)
                }

            val result = submitter.submit(report)

            assertTrue(result is FeedbackSubmitResult.Failed)
            assertEquals(1, requests.size)
            assertEquals("refresh-1", store.token)
        }

    @Test
    fun submit_appCheckFailureIsUnverifiedWithoutRequests() =
        runTest {
            val submitter =
                FirebaseRestFeedbackSubmitter(
                    config = config,
                    appCheck = { throw IllegalStateException("Play Integrity unavailable") },
                    identityStore = MemoryIdentityStore("refresh-1"),
                    cooldownStore = cooldown,
                    nowMs = { now },
                    httpClient =
                        HttpClient(
                            MockEngine { request ->
                                requests += request
                                happyPath(request)
                            },
                        ),
                )

            assertEquals(FeedbackSubmitResult.Unverified, submitter.submit(report))
            assertTrue(requests.isEmpty())
        }

    @Test
    fun submit_storage401IsUnverified() =
        runTest {
            val result =
                submitter(MemoryIdentityStore("refresh-1")) { request ->
                    if (request.url.host == "firebasestorage.googleapis.com") {
                        jsonResponse("""{"error":{"code":401}}""", HttpStatusCode.Unauthorized)
                    } else {
                        happyPath(request)
                    }
                }.submit(report)

            assertEquals(FeedbackSubmitResult.Unverified, result)
            assertFalse(requests.any { it.url.host == "firestore.googleapis.com" })
        }

    @Test
    fun submit_storage403IsUnverified() =
        runTest {
            val result = submitWhere("firebasestorage.googleapis.com", HttpStatusCode.Forbidden)

            assertEquals(FeedbackSubmitResult.Unverified, result)
            assertFalse(requests.any { it.url.host == "firestore.googleapis.com" })
        }

    @Test
    fun submit_commit401IsUnverified() =
        runTest {
            cooldown.lastSent = now - 10_000

            assertEquals(FeedbackSubmitResult.Unverified, submitWhere("firestore.googleapis.com", HttpStatusCode.Unauthorized))
        }

    @Test
    fun submit_commit403AfterARecentSendIsRateLimitedForTheRemainingCooldown() =
        runTest {
            cooldown.lastSent = now - 15_000

            val result = submitWhere("firestore.googleapis.com", HttpStatusCode.Forbidden)

            assertEquals(FeedbackSubmitResult.RateLimited(45), result)
        }

    @Test
    fun submit_commit403WithoutARecentSendIsUnverified() =
        runTest {
            // App Check enforcement and the rate limit both answer 403 on commit.
            assertEquals(FeedbackSubmitResult.Unverified, submitWhere("firestore.googleapis.com", HttpStatusCode.Forbidden))

            requests.clear()
            cooldown.lastSent = now - 60_000
            assertEquals(FeedbackSubmitResult.Unverified, submitWhere("firestore.googleapis.com", HttpStatusCode.Forbidden))
        }

    @Test
    fun submit_transportErrorIsOffline() =
        runTest {
            val result = submitter(MemoryIdentityStore("refresh-1")) { throw IOException("no route") }.submit(report)

            assertEquals(FeedbackSubmitResult.Offline, result)
        }

    @Test
    fun submit_otherServerErrorsFail() =
        runTest {
            val result =
                submitter(MemoryIdentityStore("refresh-1")) { request ->
                    if (request.url.host == "firestore.googleapis.com") {
                        jsonResponse("""{"error":{"code":500}}""", HttpStatusCode.InternalServerError)
                    } else {
                        happyPath(request)
                    }
                }.submit(report)

            assertTrue(result is FeedbackSubmitResult.Failed)
        }

    @Test
    fun submit_sendsNoServerAddressOrCredentials() =
        runTest {
            submitter(MemoryIdentityStore(null)) { request -> happyPath(request) }.submit(report)

            val commit = requests.last().bodyText()
            listOf("http://", "4096", "opencode:", "password", "username").forEach { secret ->
                assertFalse("commit contains $secret", commit.contains(secret))
            }
            assertNull(requests.firstNotNullOfOrNull { it.headers["X-Ios-Bundle-Identifier"] })
        }

    private suspend fun submitWhere(
        host: String,
        status: HttpStatusCode,
    ): FeedbackSubmitResult =
        submitter(MemoryIdentityStore("refresh-1")) { request ->
            if (request.url.host == host) {
                jsonResponse("""{"error":{"code":${status.value},"status":"PERMISSION_DENIED"}}""", status)
            } else {
                happyPath(request)
            }
        }.submit(report)

    private fun submitter(
        store: FeedbackIdentityStore,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): FirebaseRestFeedbackSubmitter =
        FirebaseRestFeedbackSubmitter(
            config = config,
            appCheck = { "app-check-token" },
            identityStore = store,
            cooldownStore = cooldown,
            nowMs = { now },
            httpClient =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        handler(request)
                    },
                ),
        )

    private fun MockRequestHandleScope.happyPath(request: HttpRequestData): HttpResponseData =
        when (request.url.host) {
            "securetoken.googleapis.com" ->
                jsonResponse("""{"id_token":"id-token","refresh_token":"refresh-2","user_id":"anon-uid"}""")
            "identitytoolkit.googleapis.com" ->
                jsonResponse("""{"idToken":"id-token","refreshToken":"signup-refresh","localId":"anon-uid"}""")
            "firebasestorage.googleapis.com" -> jsonResponse("""{"name":"ok"}""")
            "firestore.googleapis.com" -> jsonResponse("""{"writeResults":[{},{}]}""")
            else -> jsonResponse("{}", HttpStatusCode.NotFound)
        }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyText(): String =
        when (val content = body) {
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> error("Unexpected body ${content::class}")
        }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private class MemoryCooldownStore : FeedbackCooldownStore {
        var lastSent: Long? = null

        override fun lastSentAtEpochMs(): Long? = lastSent

        override fun recordSent(atEpochMs: Long) {
            lastSent = atEpochMs
        }
    }

    private class MemoryIdentityStore(
        var token: String?,
    ) : FeedbackIdentityStore {
        override fun readRefreshToken(): String? = token

        override fun writeRefreshToken(token: String?) {
            this.token = token
        }
    }
}
