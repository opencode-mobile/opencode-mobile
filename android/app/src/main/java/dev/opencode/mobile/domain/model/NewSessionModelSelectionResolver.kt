package dev.opencode.mobile.domain.model

import android.util.Log
import dev.opencode.mobile.domain.connection.ConnectionProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

class NewSessionModelSelectionResolver
    @Inject
    constructor(
        private val store: SessionModelSelectionStore,
        private val modelRepository: ModelRepository,
    ) {
        suspend fun resolve(
            profile: ConnectionProfile,
            worktree: String,
        ): SessionModelSelection? {
            val identity = ModelSelectionConnectionIdentity(profile.baseUrl, profile.username)
            val (preference, catalog) =
                coroutineScope {
                    val preference =
                        async {
                            runCatching { store.loadNewSessionPreference(identity, worktree) }
                                .onFailure { logW("load new-session preference failed", it) }
                                .getOrNull()
                        }
                    val catalog =
                        async {
                            runCatching { modelRepository.listModelOptions(worktree) }
                                .onFailure { logW("load new-session catalog failed", it) }
                                .getOrNull()
                        }
                    preference.await() to catalog.await()
                }
            catalog ?: return null
            val option =
                preference?.let { catalog.option(it.providerId, it.modelId) }
                    ?: catalog.serverDefault
                    ?: catalog.options.firstOrNull()
                    ?: return null
            val variant = preference?.variant?.takeIf { it in option.variants }
            return SessionModelSelection(
                providerId = option.providerId,
                modelId = option.modelId,
                variant = variant,
                savedAtEpochMs = System.currentTimeMillis(),
            )
        }

        suspend fun seedSession(
            profile: ConnectionProfile,
            worktree: String,
            sessionId: String,
            selection: SessionModelSelection,
        ) {
            runCatching {
                store.saveSession(
                    ModelSelectionConnectionIdentity(profile.baseUrl, profile.username),
                    worktree,
                    sessionId,
                    selection,
                )
            }.onFailure { logW("seed session model selection failed", it) }
        }

        private fun logW(
            message: String,
            throwable: Throwable,
        ) {
            runCatching { Log.w(TAG, message, throwable) }
        }

        private companion object {
            const val TAG = "NewSessionModel"
        }
    }
