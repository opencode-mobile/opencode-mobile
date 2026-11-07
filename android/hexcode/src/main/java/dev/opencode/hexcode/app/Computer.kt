package dev.opencode.hexcode.app

import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore

/** What the phone keeps about its setup on the computer besides the server itself ([HexSettings]). */
interface SetupMemory {
    /**
     * Forgets the project, the model, first run being done, the server's home and version, the sign-in date, the saved
     * card, the Ask history and the last date and session id. Keeps Appearance and the developer unlock.
     */
    suspend fun forgetSetup()
}

/**
 * Forget this computer and Pair again's switch (app-plan.md, Settings and finish (M6) › Server). Every saved profile
 * goes **with its password**, so the encrypted store keeps no entry; the speech model, Appearance and the developer
 * unlock stay.
 */
class ComputerForgetting(
    private val profiles: ConnectionProfileStore,
    private val credentials: SecureCredentialStore,
    private val setup: SetupMemory,
    /** Unbinds the server, so no call reaches it any more. */
    private val disconnect: suspend () -> Unit,
    /** Clears the in-app browser's website data. */
    private val clearBrowser: suspend () -> Unit,
) {
    /** Forget this computer: first run starts at Welcome afterwards. */
    suspend fun forget() {
        removeServers()
        disconnect()
        setup.forgetSetup()
        clearBrowser()
    }

    /**
     * Pair again with another computer, after "Switch to …?": the old profile and its password go, then [useNew] saves
     * and binds the new one, and first run goes on at its project page. The browser's data belonged to the old one.
     */
    suspend fun switchTo(useNew: suspend () -> Unit) {
        removeServers()
        setup.forgetSetup()
        clearBrowser()
        useNew()
    }

    private suspend fun removeServers() {
        profiles.listProfiles().forEach { profiles.removeProfile(it.id) }
        profiles.clearAll()
        credentials.clearAll()
    }
}

/** Pair again's question: is the code from the computer already paired? */
object PairAgain {
    /**
     * The same computer when both hostnames are known and match (lowercase, without `.local`, as the name is made);
     * when either isn't known, when their addresses overlap.
     */
    fun isSameComputer(
        savedHostname: String?,
        newHostname: String?,
        savedUrls: List<String>,
        newUrls: List<String>,
    ): Boolean {
        val saved = normalise(savedHostname)
        val fresh = normalise(newHostname)
        if (saved != null && fresh != null) return saved == fresh
        val before = savedUrls.map(ConnectionProfileIdentity::normalizeBaseUrl).toSet()
        return newUrls.any { ConnectionProfileIdentity.normalizeBaseUrl(it) in before }
    }

    private fun normalise(hostname: String?): String? =
        hostname
            ?.trim()
            ?.lowercase()
            ?.removeSuffix(".local")
            ?.takeIf { it.isNotEmpty() }
}
