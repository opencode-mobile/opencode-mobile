package dev.opencode.hexcode.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.firstrun.ProjectChoice
import dev.opencode.hexcode.session.AskStore
import dev.opencode.hexcode.session.DailySessionMemory
import dev.opencode.hexcode.session.SavedAsks
import dev.opencode.hexcode.session.SavedSummary
import dev.opencode.hexcode.session.SummaryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Light and dark follow the system unless one is pinned in Settings. */
enum class Appearance { Auto, Light, Dark }

/** What first run decides: the project, the model and its level. The server itself is a saved Connection Profile. */
data class HexPrefs(
    val project: ProjectChoice? = null,
    val model: ModelChoice? = null,
    val firstRunDone: Boolean = false,
    val appearance: Appearance = Appearance.Auto,
    /** The model's name as the catalog gave it, for Settings. */
    val modelName: String? = null,
    /** The paired computer's home folder and OpenCode version, as pairing found them. */
    val serverHome: String? = null,
    val serverVersion: String? = null,
)

private val Context.hexDataStore: DataStore<Preferences> by preferencesDataStore(name = "hexcode")

class HexSettings(
    context: Context,
    private val json: Json,
) : DailySessionMemory,
    SummaryStore,
    AskStore,
    SetupMemory {
    private val store = context.applicationContext.hexDataStore

    val prefs: Flow<HexPrefs> =
        store.data.map { p ->
            HexPrefs(
                project = p[PROJECT]?.let { runCatching { json.decodeFromString(ProjectChoice.serializer(), it) }.getOrNull() },
                model = p[MODEL]?.let { runCatching { json.decodeFromString(ModelChoice.serializer(), it) }.getOrNull() },
                firstRunDone = p[FIRST_RUN_DONE] ?: false,
                appearance = Appearance.entries.firstOrNull { it.name == p[APPEARANCE] } ?: Appearance.Auto,
                modelName = p[MODEL_NAME],
                serverHome = p[SERVER_HOME],
                serverVersion = p[SERVER_VERSION],
            )
        }

    suspend fun current(): HexPrefs = prefs.first()

    suspend fun setProject(project: ProjectChoice) {
        store.edit { it[PROJECT] = json.encodeToString(ProjectChoice.serializer(), project) }
    }

    /** Saves the tested model and level, and marks first run done. */
    suspend fun finishFirstRun(
        model: ModelChoice,
        name: String,
    ) {
        store.edit {
            it[MODEL] = json.encodeToString(ModelChoice.serializer(), model)
            it[MODEL_NAME] = name
            it[FIRST_RUN_DONE] = true
        }
    }

    suspend fun setServer(
        home: String?,
        version: String?,
    ) {
        store.edit {
            if (home != null) it[SERVER_HOME] = home else it.remove(SERVER_HOME)
            if (version != null) it[SERVER_VERSION] = version else it.remove(SERVER_VERSION)
        }
    }

    suspend fun setModel(
        model: ModelChoice,
        name: String,
    ) {
        store.edit {
            it[MODEL] = json.encodeToString(ModelChoice.serializer(), model)
            it[MODEL_NAME] = name
        }
    }

    suspend fun setAppearance(appearance: Appearance) {
        store.edit { it[APPEARANCE] = appearance.name }
    }

    /** The date of the last session the app opened (`2026-11-05`); null before the first. */
    override suspend fun lastOpenedOn(): LocalDate? =
        store.data
            .first()[LAST_SESSION_DATE]
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    override suspend fun setLastOpenedOn(date: LocalDate) {
        store.edit { it[LAST_SESSION_DATE] = date.toString() }
    }

    /** The session the app last opened, so a new day can read whether yesterday's goal still runs. */
    override suspend fun lastSessionId(): String? = store.data.first()[LAST_SESSION_ID]

    override suspend fun setLastSessionId(id: String) {
        store.edit { it[LAST_SESSION_ID] = id }
    }

    /** Today's Ask answers, the shown one and the closed ones: saving one session's replaces the one before. */
    override suspend fun loadAsks(): SavedAsks? =
        store.data
            .first()[ASKED]
            ?.let { runCatching { json.decodeFromString(SavedAsks.serializer(), it) }.getOrNull() }

    override suspend fun saveAsks(saved: SavedAsks) {
        store.edit { it[ASKED] = json.encodeToString(SavedAsks.serializer(), saved) }
    }

    /** The last summary card, for today's session only: saving one replaces the one before. */
    override suspend fun loadSummary(): SavedSummary? =
        store.data
            .first()[LAST_SUMMARY]
            ?.let { runCatching { json.decodeFromString(SavedSummary.serializer(), it) }.getOrNull() }

    override suspend fun saveSummary(saved: SavedSummary) {
        store.edit { it[LAST_SUMMARY] = json.encodeToString(SavedSummary.serializer(), saved) }
    }

    /** The server's OpenCode version, read from `GET /api/info` each time setup runs. */
    suspend fun setServerVersion(version: String) {
        store.edit { it[SERVER_VERSION] = version }
    }

    /** When the saved sign-in (the pairing token) was issued: at pairing and at each renewal. */
    suspend fun signedInAt(): Long? = store.data.first()[SIGNED_IN_AT]

    suspend fun setSignedInAt(at: Long) {
        store.edit { it[SIGNED_IN_AT] = at }
    }

    override suspend fun forgetSetup() {
        store.edit { prefs ->
            listOf(
                PROJECT,
                MODEL,
                MODEL_NAME,
                SERVER_HOME,
                SERVER_VERSION,
                LAST_SESSION_DATE,
                LAST_SUMMARY,
                LAST_SESSION_ID,
                ASKED,
            ).forEach { prefs.remove(it) }
            prefs.remove(FIRST_RUN_DONE)
            prefs.remove(SIGNED_IN_AT)
        }
    }

    private companion object {
        val PROJECT = stringPreferencesKey("project")
        val MODEL = stringPreferencesKey("model")
        val FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
        val APPEARANCE = stringPreferencesKey("appearance")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val SERVER_HOME = stringPreferencesKey("server_home")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val LAST_SESSION_DATE = stringPreferencesKey("session_last_date")
        val LAST_SUMMARY = stringPreferencesKey("summary_last")
        val LAST_SESSION_ID = stringPreferencesKey("session_last_id")
        val ASKED = stringPreferencesKey("ask_history")
        val SIGNED_IN_AT = longPreferencesKey("signed_in_at")
    }
}
