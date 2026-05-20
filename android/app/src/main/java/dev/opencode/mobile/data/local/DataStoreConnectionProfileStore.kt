package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DataStoreConnectionProfileStore
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val json: Json,
) : ConnectionProfileStore {
  override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
    val prefs = context.connectionProfileDataStore.data.first()
    return decodeWorktrees(prefs[stringPreferencesKey(serverBaseUrl)])
  }

  override suspend fun addWorktree(serverBaseUrl: String, worktree: String) {
    context.connectionProfileDataStore.edit { prefs ->
      val key = stringPreferencesKey(serverBaseUrl)
      val current = decodeWorktrees(prefs[key]).toMutableList()
      if (worktree !in current) {
        current.add(worktree)
        prefs[key] = json.encodeToString(current)
      }
    }
  }

  private fun decodeWorktrees(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
  }

  override suspend fun setAddedWorktrees(serverBaseUrl: String, worktrees: List<String>) {
    context.connectionProfileDataStore.edit { prefs ->
      prefs[stringPreferencesKey(serverBaseUrl)] = json.encodeToString(worktrees.distinct())
    }
  }
}
