package dev.opencode.mobile.domain.connection

interface SecureCredentialStore {
    fun readPassword(serverBaseUrl: String): String?

    fun writePassword(
        serverBaseUrl: String,
        password: String,
    )

    fun deletePassword(serverBaseUrl: String)

    fun clearAll()
}
