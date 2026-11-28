package dev.opencode.mobile.domain.session

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

/**
 * Why a server request failed, read from its HTTP error body. The server's own words stay
 * verbatim ([Server]); the cases the app explains in its own words are typed, so the app maps
 * them to keyed text ([text]) instead of carrying English.
 */
sealed interface ServerErrorReason {
    /** `UnauthorizedError`: the pairing is no longer valid. */
    data object PairingExpired : ServerErrorReason

    /** `LocationNotFoundError` / `FileNotFoundError`; [path] is the server's path, when it sent one. */
    data class NotFound(
        val path: String?,
    ) : ServerErrorReason

    /** `SessionBusyError` / `BusyError`: the session is running a turn. */
    data object SessionBusy : ServerErrorReason

    /** The server's own message, with its error [ref] when it sent one. */
    data class Server(
        val message: String,
        val ref: String? = null,
    ) : ServerErrorReason

    /** The server's words when the reason is its own; null when the app explains it. */
    val serverText: String?
        get() = (this as? Server)?.let { if (it.ref.isNullOrBlank()) it.message else "${it.message} (${it.ref})" }

    /** What the app shows. */
    val text: UiText
        get() =
            when (this) {
                PairingExpired -> UiText.resource(R.string.server_error_pairing_expired)
                is NotFound ->
                    if (path.isNullOrBlank()) {
                        UiText.resource(R.string.server_error_not_found)
                    } else {
                        UiText.resource(R.string.server_error_not_found_path, path)
                    }
                SessionBusy -> UiText.resource(R.string.server_error_session_busy)
                is Server ->
                    if (ref.isNullOrBlank()) {
                        UiText.verbatim(message)
                    } else {
                        UiText.resource(R.string.server_error_with_ref, message, ref)
                    }
            }
}
