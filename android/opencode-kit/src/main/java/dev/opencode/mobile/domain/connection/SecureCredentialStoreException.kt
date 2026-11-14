package dev.opencode.mobile.domain.connection

/**
 * The Keystore-backed credential store could not be opened or written. Saving a
 * Connection Profile stops here: a profile without its password is useless, and a
 * password written anywhere else would defeat the store.
 */
class SecureCredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val USER_MESSAGE = "Couldn't store the password securely on this device. The server was not saved."
    }
}
