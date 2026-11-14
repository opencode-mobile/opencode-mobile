package dev.opencode.hexcode.app

import android.util.Log
import kotlinx.coroutines.CancellationException

/** The server calls a renewal makes, with the current credentials (m6-proposal.md §1.3). */
interface SignInApi {
    /** `POST /api/pair`: a one-use pairing code, good for 300 s. */
    suspend fun pairCode(): String

    /** `GET /auth/connect/<code>` with `Accept: application/json`: a new token, good for 30 days. */
    suspend fun redeem(code: String): String
}

/** Where the sign-in is kept on the phone: the token (the profile's password) and when it was issued. */
interface SignInStore {
    /** When the saved token was issued, in [System.currentTimeMillis] time; null when the phone never saved it. */
    suspend fun signedInAt(): Long?

    /** The saved token, to read its own expiry when [signedInAt] isn't known; null without a server. */
    suspend fun token(): String?

    /** Replaces the token with [token], issued at [at]. */
    suspend fun saveToken(
        token: String,
        at: Long,
    )
}

/** How a renewal check went. */
enum class Renewal {
    /** Younger than [SignInRenewal.RENEW_AFTER_MS], or there's nothing to renew. */
    NotDue,

    /** A new token was saved. */
    Renewed,

    /** Either call failed: the old token stays, and the next setup tries again. */
    Failed,
}

/**
 * The pairing sign-in expires 30 days after it was issued (app-plan.md, Settings and finish (M6) › Sign-in renewal).
 * At setup, once the saved one is more than a week old, it's renewed silently: `POST /api/pair`, then
 * `GET /auth/connect/<code>`, then the new token and date are saved. The old token keeps working until its own
 * expiry, so a renewal that fails halfway does no harm.
 */
class SignInRenewal(
    private val api: SignInApi,
    private val store: SignInStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun renewIfDue(): Renewal {
        val issued = store.signedInAt() ?: store.token()?.let(::issuedAt) ?: return Renewal.NotDue
        if (clock() - issued <= RENEW_AFTER_MS) return Renewal.NotDue
        return try {
            val token = api.redeem(api.pairCode())
            if (token.isBlank()) return Renewal.Failed
            store.saveToken(token, clock())
            Log.i(TAG, "renewed the sign-in")
            Renewal.Renewed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "couldn't renew the sign-in: ${error.message}")
            Renewal.Failed
        }
    }

    companion object {
        private const val TAG = "HexCode.SignIn"

        /** A sign-in older than this is renewed at the next setup. */
        const val RENEW_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        /** How long a pairing token lasts. */
        const val LIFETIME_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * When [token] was issued, from its own expiry: a pairing token is `<expires unix seconds>.<signature>`
         * (OpenCode's `server/src/auth.ts`). Null for anything else, such as a password typed by hand.
         */
        fun issuedAt(token: String): Long? {
            val expires = token.substringBefore('.', "").toLongOrNull() ?: return null
            if (expires <= 0) return null
            return expires * 1000 - LIFETIME_MS
        }
    }
}
