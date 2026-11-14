package dev.opencode.hexcode.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** app-plan.md, Settings and finish (M6) › Testing › Sign-in. */
class SignInRenewalTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private val calls = mutableListOf<String>()

    private inner class Api(
        var failPair: Boolean = false,
        var failRedeem: Boolean = false,
    ) : SignInApi {
        override suspend fun pairCode(): String {
            calls += "pair"
            if (failPair) error("HTTP 401")
            return "code1"
        }

        override suspend fun redeem(code: String): String {
            calls += "connect $code"
            if (failRedeem) error("HTTP 401")
            return "new-token"
        }
    }

    private class Store(
        var at: Long?,
        var saved: String? = "old-token",
    ) : SignInStore {
        override suspend fun signedInAt() = at

        override suspend fun token() = saved

        override suspend fun saveToken(
            token: String,
            at: Long,
        ) {
            saved = token
            this.at = at
        }
    }

    @Test
    fun aSignInSavedEightDaysAgoIsRenewedWithPairThenConnect() =
        runTest {
            val store = Store(now - 8 * day)
            assertEquals(Renewal.Renewed, SignInRenewal(Api(), store) { now }.renewIfDue())
            assertEquals(listOf("pair", "connect code1"), calls)
            assertEquals("new-token", store.saved)
            assertEquals(now, store.at)
        }

    @Test
    fun oneSavedSixDaysAgoIsLeft() =
        runTest {
            val store = Store(now - 6 * day)
            assertEquals(Renewal.NotDue, SignInRenewal(Api(), store) { now }.renewIfDue())
            assertEquals(emptyList<String>(), calls)
            assertEquals("old-token", store.saved)
        }

    @Test
    fun aFailedRenewalKeepsTheOldTokenAndItsDate() =
        runTest {
            for (api in listOf(Api(failPair = true), Api(failRedeem = true))) {
                val store = Store(now - 8 * day)
                assertEquals(Renewal.Failed, SignInRenewal(api, store) { now }.renewIfDue())
                assertEquals("old-token", store.saved)
                assertEquals(now - 8 * day, store.at)
            }
        }

    @Test
    fun withNoSavedDateTheTokensOwnExpirySaysHowOldItIs() =
        runTest {
            // Issued 10 days ago: it expires in 20.
            val expires = (now + 20 * day) / 1000
            val store = Store(at = null, saved = "$expires.c2lnbmF0dXJl")
            assertEquals(Renewal.Renewed, SignInRenewal(Api(), store) { now }.renewIfDue())
            // Issued 2 days ago: not yet.
            val fresh = Store(at = null, saved = "${(now + 28 * day) / 1000}.c2ln")
            assertEquals(Renewal.NotDue, SignInRenewal(Api(), fresh) { now }.renewIfDue())
        }

    @Test
    fun aPasswordThatIsNoTokenIsNeverRenewed() =
        runTest {
            assertNull(SignInRenewal.issuedAt("hunter2"))
            assertEquals(Renewal.NotDue, SignInRenewal(Api(), Store(at = null, saved = "hunter2")) { now }.renewIfDue())
        }
}
