package dev.opencode.mobile.domain.feedback

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URLEncoder

/** Firebase settings from the configured `FirebaseApp`, plus the API-key restriction headers. */
data class FeedbackFirebaseConfig(
    val apiKey: String,
    val projectId: String,
    val storageBucket: String,
    val androidPackage: String,
    /** SHA-1 of the signing certificate, uppercase hex without colons. */
    val androidCertSha1: String,
)

/** Returns an App Check token, or throws when this copy of the app cannot be verified. */
fun interface FeedbackAppCheckTokenProvider {
    suspend fun token(): String
}

/** Keeps the anonymous user's refresh token in Keystore-backed storage. */
interface FeedbackIdentityStore {
    fun readRefreshToken(): String?

    fun writeRefreshToken(token: String?)
}

/**
 * Release submit flow over Firebase REST (#721): App Check token, anonymous user (refresh,
 * or sign up again), one Storage upload per attachment, then one Firestore commit that
 * creates the report and stamps the per-user rate limit.
 *
 * With App Check enforced, a commit without a valid token and a commit inside the server's
 * 60 s rate limit both return 403. [cooldownStore] tells them apart: a 403 is a rate limit only
 * when the last successful send is under 60 s old, otherwise the request was not verified.
 */
class FirebaseRestFeedbackSubmitter(
    private val config: FeedbackFirebaseConfig,
    private val appCheck: FeedbackAppCheckTokenProvider,
    private val identityStore: FeedbackIdentityStore,
    private val httpClient: HttpClient,
    private val cooldownStore: FeedbackCooldownStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : FeedbackSubmitter {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submit(report: FeedbackReport): FeedbackSubmitResult {
        val appCheckToken =
            try {
                appCheck.token().takeIf { it.isNotBlank() } ?: return FeedbackSubmitResult.Unverified
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return FeedbackSubmitResult.Unverified
            }
        return try {
            val user = anonymousUser(appCheckToken)
            report.attachments.forEach { attachment -> upload(appCheckToken, user, report.reportId, attachment) }
            commit(appCheckToken, user, report)
            FeedbackSubmitResult.Sent(report.reference)
        } catch (failure: SubmitFailure) {
            failure.result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            FeedbackSubmitResult.Offline
        } catch (error: Throwable) {
            FeedbackSubmitResult.Failed(error.javaClass.simpleName)
        }
    }

    private suspend fun anonymousUser(appCheckToken: String): AnonymousUser {
        val refreshToken = identityStore.readRefreshToken()
        if (refreshToken != null) {
            val response =
                httpClient.post("$SECURE_TOKEN_URL?key=${config.apiKey}") {
                    firebaseHeaders(appCheckToken)
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("grant_type=refresh_token&refresh_token=${refreshToken.formEncoded()}")
                }
            val body = response.jsonBody()
            if (response.status.isSuccess()) {
                val user =
                    AnonymousUser(
                        uid = body.string("user_id") ?: throw failed("refresh: no user"),
                        idToken = body.string("id_token") ?: throw failed("refresh: no token"),
                    )
                body.string("refresh_token")?.let(identityStore::writeRefreshToken)
                return user
            }
            if (body.errorCode() !in SIGN_UP_AGAIN_ERRORS) {
                throw failed("refresh ${response.status.value}")
            }
            identityStore.writeRefreshToken(null)
        }
        return signUp(appCheckToken)
    }

    private suspend fun signUp(appCheckToken: String): AnonymousUser {
        val response =
            httpClient.post("$IDENTITY_TOOLKIT_URL/accounts:signUp?key=${config.apiKey}") {
                firebaseHeaders(appCheckToken)
                contentType(ContentType.Application.Json)
                setBody("""{"returnSecureToken":true}""")
            }
        if (!response.status.isSuccess()) throw failed("sign up ${response.status.value}")
        val body = response.jsonBody()
        val user =
            AnonymousUser(
                uid = body.string("localId") ?: throw failed("sign up: no user"),
                idToken = body.string("idToken") ?: throw failed("sign up: no token"),
            )
        body.string("refreshToken")?.let(identityStore::writeRefreshToken)
        return user
    }

    private suspend fun upload(
        appCheckToken: String,
        user: AnonymousUser,
        reportId: String,
        attachment: FeedbackAttachment,
    ) {
        val objectName = "feedback/${user.uid}/$reportId/${attachment.name}"
        val response =
            httpClient.post("$STORAGE_URL/b/${config.storageBucket}/o?name=${objectName.formEncoded()}") {
                firebaseHeaders(appCheckToken)
                header(HttpHeaders.Authorization, "Firebase ${user.idToken}")
                contentType(ContentType.parse(attachment.contentType))
                setBody(attachment.bytes)
            }
        when {
            response.status.isSuccess() -> Unit
            response.status.value == 401 || response.status.value == 403 ->
                throw SubmitFailure(FeedbackSubmitResult.Unverified)
            else -> throw failed("upload ${response.status.value}")
        }
    }

    private suspend fun commit(
        appCheckToken: String,
        user: AnonymousUser,
        report: FeedbackReport,
    ) {
        val response =
            httpClient.post("$FIRESTORE_URL/projects/${config.projectId}/databases/(default)/documents:commit") {
                firebaseHeaders(appCheckToken)
                header(HttpHeaders.Authorization, "Bearer ${user.idToken}")
                contentType(ContentType.Application.Json)
                setBody(commitBody(report, user.uid).toString())
            }
        when {
            response.status.isSuccess() -> Unit
            response.status.value == 401 -> throw SubmitFailure(FeedbackSubmitResult.Unverified)
            response.status.value == 403 -> throw SubmitFailure(commitForbidden())
            else -> throw failed("commit ${response.status.value}")
        }
    }

    /** A 403 right after a successful send is the server rate limit; otherwise App Check or rules. */
    private fun commitForbidden(): FeedbackSubmitResult {
        val remaining = FeedbackCooldownStore.remainingSeconds(cooldownStore.lastSentAtEpochMs(), nowMs())
        return if (remaining > 0) FeedbackSubmitResult.RateLimited(remaining) else FeedbackSubmitResult.Unverified
    }

    internal fun commitBody(
        report: FeedbackReport,
        uid: String,
    ): JsonObject {
        val documents = "projects/${config.projectId}/databases/(default)/documents"
        return buildJsonObject {
            putJsonArray("writes") {
                add(
                    buildJsonObject {
                        putJsonObject("update") {
                            put("name", "$documents/feedback/${report.reportId}")
                            put("fields", FirestoreValues.fields(FeedbackPayloadBuilder.document(report, uid)))
                        }
                        put("updateTransforms", serverTime("createdAt"))
                        putJsonObject("currentDocument") { put("exists", false) }
                    },
                )
                add(
                    buildJsonObject {
                        putJsonObject("update") {
                            put("name", "$documents/rateLimits/$uid")
                            putJsonObject("fields") {
                                putJsonObject("lastReportId") { put("stringValue", report.reportId) }
                            }
                        }
                        putJsonObject("updateMask") {
                            putJsonArray("fieldPaths") { add(kotlinx.serialization.json.JsonPrimitive("lastReportId")) }
                        }
                        put("updateTransforms", serverTime("lastReportAt"))
                    },
                )
            }
        }
    }

    private fun serverTime(fieldPath: String) =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("fieldPath", fieldPath)
                    put("setToServerValue", "REQUEST_TIME")
                },
            )
        }

    private fun HttpRequestBuilder.firebaseHeaders(appCheckToken: String) {
        header(HEADER_APP_CHECK, appCheckToken)
        header(HEADER_ANDROID_PACKAGE, config.androidPackage)
        header(HEADER_ANDROID_CERT, config.androidCertSha1)
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject =
        runCatching { json.parseToJsonElement(bodyAsText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    /** `TOKEN_EXPIRED` from `{"error":{"message":"TOKEN_EXPIRED : detail"}}`. */
    private fun JsonObject.errorCode(): String? =
        runCatching { this["error"]?.jsonObject?.string("message") }
            .getOrNull()
            ?.substringBefore(' ')
            ?.substringBefore(':')
            ?.trim()

    private fun String.formEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun failed(reason: String) = SubmitFailure(FeedbackSubmitResult.Failed(reason))

    private class SubmitFailure(
        val result: FeedbackSubmitResult,
    ) : Exception()

    private data class AnonymousUser(
        val uid: String,
        val idToken: String,
    )

    companion object {
        const val SECURE_TOKEN_URL = "https://securetoken.googleapis.com/v1/token"
        const val IDENTITY_TOOLKIT_URL = "https://identitytoolkit.googleapis.com/v1"
        const val STORAGE_URL = "https://firebasestorage.googleapis.com/v0"
        const val FIRESTORE_URL = "https://firestore.googleapis.com/v1"
        const val HEADER_APP_CHECK = "X-Firebase-AppCheck"
        const val HEADER_ANDROID_PACKAGE = "X-Android-Package"
        const val HEADER_ANDROID_CERT = "X-Android-Cert"

        /** Refresh failures that mean the anonymous user is gone: sign up again. */
        val SIGN_UP_AGAIN_ERRORS = setOf("USER_NOT_FOUND", "USER_DISABLED", "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN")
    }
}
