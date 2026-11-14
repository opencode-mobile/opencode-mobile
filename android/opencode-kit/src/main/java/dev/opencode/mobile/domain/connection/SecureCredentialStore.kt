package dev.opencode.mobile.domain.connection

interface SecureCredentialStore {
    fun readPassword(serverBaseUrl: String): String?

    /**
     * Throws [SecureCredentialStoreException] when the secure store refused the write. Callers
     * must not save the profile in that case.
     */
    fun writePassword(
        serverBaseUrl: String,
        password: String,
    )

    fun deletePassword(serverBaseUrl: String)

    fun clearAll()
}
