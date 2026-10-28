package dev.opencode.mobile.domain.feedback

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.app.AppVersion
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogExportWindow
import dev.opencode.mobile.network.ActiveConnectionManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

fun interface FeedbackMetadataSource {
    fun metadata(screen: FeedbackScreen): FeedbackMetadata

    companion object {
        fun build(
            appVersion: AppVersion,
            androidRelease: String,
            model: String,
            locale: Locale,
            screen: FeedbackScreen,
            serverProtocol: ServerProtocol?,
            serverVersion: String?,
        ): FeedbackMetadata =
            FeedbackMetadata(
                version = appVersion.version,
                build = appVersion.build,
                osVersion = "Android ${androidRelease.trim()}",
                device = model.trim().ifEmpty { "Android" },
                locale = locale.toLanguageTag(),
                screen = screen,
                serverProtocol = serverProtocol?.wireValue,
                serverVersion = serverVersion,
            )

        /** Reads only the protocol and server version from the active connection. */
        fun fromActiveConnection(
            activeConnectionManager: ActiveConnectionManager,
            appVersion: AppVersion,
            androidRelease: String,
            model: String,
            locale: Locale,
            screen: FeedbackScreen,
        ): FeedbackMetadata =
            build(
                appVersion = appVersion,
                androidRelease = androidRelease,
                model = model,
                locale = locale,
                screen = screen,
                serverProtocol = activeConnectionManager.currentProtocol(),
                serverVersion = activeConnectionManager.currentServerVersion(),
            )
    }
}

/** App, device and active-connection facts. */
@Singleton
class DeviceFeedbackMetadataSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : FeedbackMetadataSource {
        override fun metadata(screen: FeedbackScreen): FeedbackMetadata =
            FeedbackMetadataSource.fromActiveConnection(
                activeConnectionManager = activeConnectionManager,
                appVersion = AppVersion.from(context),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                model = Build.MODEL.orEmpty(),
                locale = Locale.getDefault(),
                screen = screen,
            )
    }

/** 60 s from the last successful send, checked on the phone before any network call. */
interface FeedbackCooldownStore {
    fun lastSentAtEpochMs(): Long?

    fun recordSent(atEpochMs: Long)

    companion object {
        const val COOLDOWN_SECONDS = 60

        /** Whole seconds left before another report may go out; 0 when none. */
        fun remainingSeconds(
            lastSentAtEpochMs: Long?,
            nowEpochMs: Long,
        ): Int {
            if (lastSentAtEpochMs == null) return 0
            val remainingMs = lastSentAtEpochMs + COOLDOWN_SECONDS * 1000L - nowEpochMs
            if (remainingMs <= 0) return 0
            return ((remainingMs + 999) / 1000).toInt().coerceAtMost(COOLDOWN_SECONDS)
        }
    }
}

@Singleton
class SharedPreferencesFeedbackCooldownStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FeedbackCooldownStore {
        private val preferences: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun lastSentAtEpochMs(): Long? = preferences.getLong(KEY_LAST_SENT, 0L).takeIf { it > 0L }

        override fun recordSent(atEpochMs: Long) {
            preferences.edit().putLong(KEY_LAST_SENT, atEpochMs).apply()
        }

        private companion object {
            const val PREFS_NAME = "opencode_feedback"
            const val KEY_LAST_SENT = "last_sent_at"
        }
    }

/**
 * Turns draft attachments into upload bytes: screenshot boxes burned in and re-encoded,
 * the diagnostic log exported and gzipped. [logPreview] is the redacted JSONL as lines.
 */
interface FeedbackAttachmentRenderer {
    suspend fun screenshot(screenshot: FeedbackScreenshot): ByteArray

    suspend fun logs(window: DiagnosticLogExportWindow): ByteArray

    suspend fun logPreview(window: DiagnosticLogExportWindow): List<String>
}
