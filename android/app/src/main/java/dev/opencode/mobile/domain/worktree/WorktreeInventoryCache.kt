package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The added paths and the last worktree inventory each of them loaded, per server.
 *
 * Home records what it loads; Session Chat reads it synchronously when it binds, so a worktree
 * session names its checkout on the first frame instead of after its own inventory fetch.
 * Session Chat records what it loads too, for the next open.
 */
@Singleton
class WorktreeInventoryCache
    @Inject
    constructor() {
        data class Snapshot(
            val addedPaths: List<String> = emptyList(),
            val inventories: Map<String, WorktreeInventory> = emptyMap(),
        ) {
            fun inventoryFor(addedPath: String): WorktreeInventory = inventories[addedPath] ?: WorktreeInventory.Unloaded
        }

        private data class Entry(
            val addedPaths: List<String>? = null,
            val inventories: Map<String, WorktreeInventory> = emptyMap(),
        )

        private val lock = Any()
        private val servers = mutableMapOf<String, Entry>()

        /** Null until the added paths of [server] were recorded. */
        fun snapshot(server: String): Snapshot? {
            val key = key(server)
            if (key.isEmpty()) return null
            return synchronized(lock) {
                val entry = servers[key] ?: return null
                val addedPaths = entry.addedPaths ?: return null
                Snapshot(addedPaths, entry.inventories)
            }
        }

        /** Added paths of [server], in Home order. Inventories of paths no longer added are dropped. */
        fun setAddedPaths(
            paths: List<String>,
            server: String,
        ) {
            val key = key(server)
            if (key.isEmpty()) return
            synchronized(lock) {
                val entry = servers[key] ?: Entry()
                val kept = paths.toSet()
                servers[key] = entry.copy(addedPaths = paths, inventories = entry.inventories.filterKeys { it in kept })
            }
        }

        /** A failed load keeps the last inventory, as Home does. */
        fun record(
            inventory: WorktreeInventory,
            addedPath: String,
            server: String,
        ) {
            val key = key(server)
            if (key.isEmpty() || inventory is WorktreeInventory.Failed) return
            synchronized(lock) {
                val entry = servers[key] ?: Entry()
                servers[key] = entry.copy(inventories = entry.inventories + (addedPath to inventory))
            }
        }

        fun clear() {
            synchronized(lock) { servers.clear() }
        }

        private fun key(server: String): String = ConnectionProfileIdentity.normalizeBaseUrl(server)
    }
