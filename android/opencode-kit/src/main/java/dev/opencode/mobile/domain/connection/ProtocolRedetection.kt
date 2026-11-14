package dev.opencode.mobile.domain.connection

/** Outcome of re-running protocol detection on the active connection, e.g. after a server upgrade. */
sealed interface ProtocolRedetection {
    /** A protocol answered and is now cached and saved on the profile. */
    data class Detected(
        val protocol: ServerProtocol,
    ) : ProtocolRedetection

    /** No protocol answered: the server is down or no longer serves opencode at this address. */
    data object Unreachable : ProtocolRedetection

    /** Nothing learned: no connection or probe, the check was cancelled, or the credential was rejected. */
    data object Skipped : ProtocolRedetection
}
