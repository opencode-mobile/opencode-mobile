package dev.opencode.mobile.domain.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Light or dark override for the whole app (#1044). System follows the phone's setting. */
enum class AppearanceMode(
    val wireValue: String,
) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    /** Whether the app draws dark, given the phone's own setting. */
    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            System -> systemDark
            Light -> false
            Dark -> true
        }

    companion object {
        val DEFAULT = System

        fun fromWire(value: String?): AppearanceMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One step of an app-level text size (#1044). [multiplier] multiplies the phone's own font
 * scale; it never replaces it.
 */
enum class AppearanceTextSize(
    val wireValue: String,
    val multiplier: Float,
) {
    ExtraSmall("extra_small", 0.85f),
    Small("small", 0.92f),
    Default("default", 1.0f),
    Large("large", 1.12f),
    ExtraLarge("extra_large", 1.26f),
    ;

    companion object {
        val DEFAULT = Default

        fun fromWire(value: String?): AppearanceTextSize? = entries.firstOrNull { it.wireValue == value }

        /** Slider position [index] clamped to the steps. */
        fun atIndex(index: Int): AppearanceTextSize = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

/** Which surfaces a text size applies to (#1044). */
enum class AppearanceTextRegion(
    val wireValue: String,
) {
    /** Home session rows and the lists that reuse them: archived sessions, session search. */
    HomeList("home_list"),

    /** Session Chat timeline, inline code, code blocks in messages, tool lines, composer. */
    SessionChat("session_chat"),

    /** Terminal, Changes diff, file viewer, tool output and shell output sheets. */
    Code("code"),
}

/** The three text sizes at once. */
data class AppearanceTextSizes(
    val homeList: AppearanceTextSize = AppearanceTextSize.DEFAULT,
    val sessionChat: AppearanceTextSize = AppearanceTextSize.DEFAULT,
    val code: AppearanceTextSize = AppearanceTextSize.DEFAULT,
) {
    operator fun get(region: AppearanceTextRegion): AppearanceTextSize =
        when (region) {
            AppearanceTextRegion.HomeList -> homeList
            AppearanceTextRegion.SessionChat -> sessionChat
            AppearanceTextRegion.Code -> code
        }

    fun with(
        region: AppearanceTextRegion,
        size: AppearanceTextSize,
    ): AppearanceTextSizes =
        when (region) {
            AppearanceTextRegion.HomeList -> copy(homeList = size)
            AppearanceTextRegion.SessionChat -> copy(sessionChat = size)
            AppearanceTextRegion.Code -> copy(code = size)
        }

    val allDefault: Boolean
        get() = this == DEFAULT

    companion object {
        val DEFAULT = AppearanceTextSizes()
    }
}

/**
 * Appearance mode and text sizes (#1044). Device-local, not per Connection Profile, cleared by
 * Clear All Data. Values are Compose state so the theme root and every scaled region recompose
 * the moment they change.
 */
object AppearancePreferences {
    private const val PREFS_NAME = "opencode_appearance"
    private const val KEY_MODE = "mode"
    private const val KEY_HOME_LIST_TEXT_SIZE = "home_list_text_size"
    private const val KEY_SESSION_CHAT_TEXT_SIZE = "session_chat_text_size"
    private const val KEY_CODE_TEXT_SIZE = "code_text_size"

    var mode: AppearanceMode by mutableStateOf(AppearanceMode.DEFAULT)
        private set

    var textSizes: AppearanceTextSizes by mutableStateOf(AppearanceTextSizes.DEFAULT)
        private set

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        initialize(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    internal fun initialize(prefs: SharedPreferences) {
        preferences = prefs
        mode = AppearanceMode.fromWire(prefs.getString(KEY_MODE, null)) ?: AppearanceMode.DEFAULT
        textSizes =
            AppearanceTextSizes(
                homeList = storedSize(prefs, KEY_HOME_LIST_TEXT_SIZE),
                sessionChat = storedSize(prefs, KEY_SESSION_CHAT_TEXT_SIZE),
                code = storedSize(prefs, KEY_CODE_TEXT_SIZE),
            )
    }

    fun updateMode(value: AppearanceMode) {
        mode = value
        preferences?.edit()?.putString(KEY_MODE, value.wireValue)?.apply()
    }

    fun updateTextSize(
        region: AppearanceTextRegion,
        size: AppearanceTextSize,
    ) {
        textSizes = textSizes.with(region, size)
        preferences?.edit()?.putString(region.key(), size.wireValue)?.apply()
    }

    /** All three text sizes back to Default; Mode stays. */
    fun resetTextSizes() {
        textSizes = AppearanceTextSizes.DEFAULT
        preferences
            ?.edit()
            ?.remove(KEY_HOME_LIST_TEXT_SIZE)
            ?.remove(KEY_SESSION_CHAT_TEXT_SIZE)
            ?.remove(KEY_CODE_TEXT_SIZE)
            ?.apply()
    }

    /** Text sizes back to Default and Mode back to System. */
    fun resetAll() {
        resetTextSizes()
        mode = AppearanceMode.DEFAULT
        preferences?.edit()?.remove(KEY_MODE)?.apply()
    }

    /** Clear All Data. */
    fun clear() = resetAll()

    private fun storedSize(
        prefs: SharedPreferences,
        key: String,
    ): AppearanceTextSize = AppearanceTextSize.fromWire(prefs.getString(key, null)) ?: AppearanceTextSize.DEFAULT

    private fun AppearanceTextRegion.key(): String =
        when (this) {
            AppearanceTextRegion.HomeList -> KEY_HOME_LIST_TEXT_SIZE
            AppearanceTextRegion.SessionChat -> KEY_SESSION_CHAT_TEXT_SIZE
            AppearanceTextRegion.Code -> KEY_CODE_TEXT_SIZE
        }
}
