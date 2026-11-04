package dev.opencode.hexcode.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.firstrun.ProjectChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

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
) {
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

    private companion object {
        val PROJECT = stringPreferencesKey("project")
        val MODEL = stringPreferencesKey("model")
        val FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
        val APPEARANCE = stringPreferencesKey("appearance")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val SERVER_HOME = stringPreferencesKey("server_home")
        val SERVER_VERSION = stringPreferencesKey("server_version")
    }
}
