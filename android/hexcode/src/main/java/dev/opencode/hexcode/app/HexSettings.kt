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

/** What first run decides: the project, the model and its level. The server itself is a saved Connection Profile. */
data class HexPrefs(
    val project: ProjectChoice? = null,
    val model: ModelChoice? = null,
    val firstRunDone: Boolean = false,
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
            )
        }

    suspend fun current(): HexPrefs = prefs.first()

    suspend fun setProject(project: ProjectChoice) {
        store.edit { it[PROJECT] = json.encodeToString(ProjectChoice.serializer(), project) }
    }

    /** Saves the tested model and level, and marks first run done. */
    suspend fun finishFirstRun(model: ModelChoice) {
        store.edit {
            it[MODEL] = json.encodeToString(ModelChoice.serializer(), model)
            it[FIRST_RUN_DONE] = true
        }
    }

    private companion object {
        val PROJECT = stringPreferencesKey("project")
        val MODEL = stringPreferencesKey("model")
        val FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
    }
}
