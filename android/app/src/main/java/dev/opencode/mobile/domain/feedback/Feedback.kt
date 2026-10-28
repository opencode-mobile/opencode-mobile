package dev.opencode.mobile.domain.feedback

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import java.util.UUID

/** What the user is reporting. Shown as Bug / Idea; [wireValue] is the report's `kind`. */
enum class FeedbackKind(
    val wireValue: String,
    val label: String,
) {
    Bug("bug", "Bug"),
    Feedback("feedback", "Idea"),
}

/** Where the report was started; the report's `context.screen`. */
enum class FeedbackScreen(
    val wireValue: String,
) {
    Settings("settings"),
    SessionChat("session-chat"),
}

/** An area to black out, as fractions (0...1) of the screenshot's width and height. */
data class FeedbackHiddenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        /** The prototype's 140 x 44 pt box on a 390 x 844 pt screen. */
        const val WIDTH_FRACTION = 140f / 390f
        const val HEIGHT_FRACTION = 44f / 844f

        /** One box centred on a tap at ([x], [y]), both fractions of the image. */
        fun centeredAt(
            x: Float,
            y: Float,
        ): FeedbackHiddenRect {
            val halfWidth = WIDTH_FRACTION / 2f
            val halfHeight = HEIGHT_FRACTION / 2f
            return FeedbackHiddenRect(
                left = (x - halfWidth).coerceIn(0f, 1f),
                top = (y - halfHeight).coerceIn(0f, 1f),
                right = (x + halfWidth).coerceIn(0f, 1f),
                bottom = (y + halfHeight).coerceIn(0f, 1f),
            )
        }
    }
}

/** A screenshot on the draft: downscaled JPEG without boxes, plus the areas to hide. */
class FeedbackScreenshot(
    val jpeg: ByteArray,
    val hiddenRects: List<FeedbackHiddenRect> = emptyList(),
) {
    fun withHiddenRects(rects: List<FeedbackHiddenRect>): FeedbackScreenshot = FeedbackScreenshot(jpeg, rects)

    override fun equals(other: Any?): Boolean =
        other is FeedbackScreenshot && jpeg.contentEquals(other.jpeg) && hiddenRects == other.hiddenRects

    override fun hashCode(): Int = 31 * jpeg.contentHashCode() + hiddenRects.hashCode()
}

data class FeedbackDraft(
    val kind: FeedbackKind = FeedbackKind.Bug,
    val message: String = "",
    val screenshot: FeedbackScreenshot? = null,
    val includeLogs: Boolean = true,
    val logWindow: DiagnosticLogExportWindow = DiagnosticLogExportWindow.DEFAULT,
    val screen: FeedbackScreen = FeedbackScreen.Settings,
)

/** Device and connection facts sent with a report. Never the server address or credentials. */
data class FeedbackMetadata(
    val version: String,
    val build: String,
    val osVersion: String,
    val device: String,
    val locale: String,
    val screen: FeedbackScreen,
    val serverProtocol: String? = null,
    val serverVersion: String? = null,
    val engine: String = ENGINE_OPENCODE,
) {
    val platform: String get() = PLATFORM_ANDROID

    companion object {
        const val PLATFORM_ANDROID = "android"
        const val ENGINE_OPENCODE = "opencode"
    }
}

/** An uploaded file. [size] is the uploaded byte count. */
class FeedbackAttachment(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    val size: Int get() = bytes.size

    companion object {
        const val SCREENSHOT_NAME = "screenshot.jpg"
        const val SCREENSHOT_TYPE = "image/jpeg"
        const val LOGS_NAME = "logs.jsonl.gz"
        const val LOGS_TYPE = "application/gzip"

        /** Storage rules reject bigger files. */
        const val MAX_BYTES = 1_000_000

        fun screenshot(jpeg: ByteArray): FeedbackAttachment = FeedbackAttachment(SCREENSHOT_NAME, SCREENSHOT_TYPE, jpeg)

        fun logs(gzip: ByteArray): FeedbackAttachment = FeedbackAttachment(LOGS_NAME, LOGS_TYPE, gzip)
    }
}

/** A report ready to send. [reportId] is a lowercase UUID v4 and names the Storage folder. */
data class FeedbackReport(
    val reportId: String,
    val kind: FeedbackKind,
    val message: String,
    val metadata: FeedbackMetadata,
    val attachments: List<FeedbackAttachment> = emptyList(),
) {
    /** The user-facing reference, e.g. `1F0DE9`. */
    val reference: String get() = FeedbackReference.of(reportId)

    companion object {
        const val MAX_MESSAGE_LENGTH = 5000

        fun newReportId(): String = UUID.randomUUID().toString().lowercase()
    }
}

object FeedbackReference {
    /** First 6 hex characters of the report id, uppercased. */
    fun of(reportId: String): String = reportId.filter { it.isLetterOrDigit() }.take(6).uppercase()
}

/** What the share sheet gets in debug builds, or after Share Instead. */
data class FeedbackSharePayload(
    val subject: String,
    val text: String,
    val attachments: List<FeedbackAttachment>,
) {
    companion object {
        fun from(report: FeedbackReport): FeedbackSharePayload {
            val metadata = report.metadata
            val facts =
                listOfNotNull(
                    "OpenCode Mobile ${metadata.version} (${metadata.build})",
                    metadata.osVersion,
                    metadata.device,
                    metadata.locale,
                    metadata.screen.wireValue,
                    metadata.serverProtocol,
                    metadata.serverVersion?.let { "opencode $it" },
                    "Ref ${report.reference}",
                )
            return FeedbackSharePayload(
                subject = "OpenCode Mobile ${report.kind.label.lowercase()} report",
                text = report.message + "\n\n" + facts.joinToString(" · "),
                attachments = report.attachments,
            )
        }
    }
}

sealed interface FeedbackSubmitResult {
    data class Sent(
        val reference: String,
    ) : FeedbackSubmitResult

    data object Offline : FeedbackSubmitResult

    data class RateLimited(
        val retryAfterSeconds: Int,
    ) : FeedbackSubmitResult

    /** App Check rejected this copy of the app. */
    data object Unverified : FeedbackSubmitResult

    data class Failed(
        val message: String,
    ) : FeedbackSubmitResult

    /** Debug builds: hand the report to the share sheet. */
    data class ShareInstead(
        val payload: FeedbackSharePayload,
    ) : FeedbackSubmitResult
}

/** Sends a report. Feature code talks to this seam and never to Firebase. */
fun interface FeedbackSubmitter {
    suspend fun submit(report: FeedbackReport): FeedbackSubmitResult
}

/** Debug builds, and release builds without Firebase config: always share instead. */
class ShareFeedbackSubmitter : FeedbackSubmitter {
    override suspend fun submit(report: FeedbackReport): FeedbackSubmitResult =
        FeedbackSubmitResult.ShareInstead(FeedbackSharePayload.from(report))
}
