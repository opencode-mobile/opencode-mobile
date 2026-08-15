package dev.opencode.mobile.domain.connection

import java.util.UUID

class DuplicateConnectionProfileException(
    val conflictingId: String,
) : IllegalStateException("A Connection Profile for this server and username already exists")

data class ConnectionProfileCatalog(
    val profiles: List<ConnectionProfile> = emptyList(),
    val activeId: String? = null,
) {
    fun activeProfile(): ConnectionProfile? = profiles.firstOrNull { it.id == activeId }

    fun save(profile: ConnectionProfile): ConnectionProfileCatalog {
        val normalizedUrl = ConnectionProfileIdentity.normalizeBaseUrl(profile.baseUrl)
        val username = ConnectionProfileIdentity.normalizeUsername(profile.username)
        val id = profile.id.ifBlank { UUID.randomUUID().toString() }
        val stored =
            profile.copy(
                id = id,
                baseUrl = normalizedUrl,
                username = username,
                name = profile.name.trim(),
            )
        val conflict =
            profiles.firstOrNull { existing ->
                existing.id != id &&
                    ConnectionProfileIdentity.sameConnectionIdentity(
                        existing.baseUrl,
                        existing.username,
                        stored.baseUrl,
                        stored.username,
                    )
            }
        if (conflict != null) {
            throw DuplicateConnectionProfileException(conflict.id)
        }
        val existingIndex = profiles.indexOfFirst { it.id == id }
        val nextProfiles =
            if (existingIndex < 0) {
                profiles + stored
            } else {
                profiles.toMutableList().also { it[existingIndex] = stored }
            }
        val nextActive = if (existingIndex < 0 || activeId == null) id else activeId
        return copy(profiles = nextProfiles, activeId = nextActive)
    }

    fun apply(id: String): ConnectionProfileCatalog {
        if (profiles.none { it.id == id }) return this
        return copy(activeId = id)
    }

    fun remove(id: String): ConnectionProfileCatalog {
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = profiles.filterNot { it.id == id }
        val nextActive =
            when {
                remaining.isEmpty() -> null
                activeId != id -> activeId
                else -> remaining[minOf(index, remaining.lastIndex)].id
            }
        return copy(profiles = remaining, activeId = nextActive)
    }
}
