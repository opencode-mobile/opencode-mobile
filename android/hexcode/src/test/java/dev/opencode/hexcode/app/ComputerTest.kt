package dev.opencode.hexcode.app

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Settings and finish (M6) › Testing › Server. */
class ComputerTest {
    private val log = mutableListOf<String>()

    private class Credentials : SecureCredentialStore {
        val passwords = mutableMapOf<String, String>()

        override fun readPassword(serverBaseUrl: String) = passwords[serverBaseUrl]

        override fun writePassword(
            serverBaseUrl: String,
            password: String,
        ) {
            passwords[serverBaseUrl] = password
        }

        override fun deletePassword(serverBaseUrl: String) {
            passwords.remove(serverBaseUrl)
        }

        override fun clearAll() = passwords.clear()
    }

    /** A profile store whose passwords live in [credentials], as the kit's does. */
    private class Profiles(
        val credentials: Credentials,
    ) : ConnectionProfileStore {
        val profiles = mutableListOf<ConnectionProfile>()

        fun add(profile: ConnectionProfile) {
            profiles += profile.copy(password = "")
            credentials.writePassword(profile.id, profile.password)
        }

        override suspend fun activeProfile() = profiles.firstOrNull()

        override suspend fun listProfiles() = profiles.toList()

        override suspend fun saveProfile(profile: ConnectionProfile) = add(profile)

        // The kit's removeProfile deletes the password too; this one forgets to, so forget() mustn't rely on it.
        override suspend fun removeProfile(id: String) {
            profiles.removeAll { it.id == id }
        }

        override suspend fun addedWorktrees(serverBaseUrl: String) = emptyList<String>()

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) = Unit

        override suspend fun removeWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) = Unit

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) = Unit

        override suspend fun clearAll() = profiles.clear()
    }

    private val credentials = Credentials()
    private val profiles =
        Profiles(credentials).apply {
            add(ConnectionProfile("http://192.168.0.47:4196/", "opencode", "1800.sig", id = "p1"))
        }
    private val forgetting =
        ComputerForgetting(
            profiles,
            credentials,
            setup =
                object : SetupMemory {
                    override suspend fun forgetSetup() {
                        log += "setup"
                    }
                },
            disconnect = { log += "disconnect" },
            clearBrowser = { log += "browser" },
        )

    @Test
    fun forgetDeletesTheProfileAndItsPasswordTheSetupAndTheBrowsersData() =
        runTest {
            forgetting.forget()
            assertTrue(profiles.profiles.isEmpty())
            // The encrypted store has no entry left.
            assertTrue(credentials.passwords.isEmpty())
            assertEquals(listOf("disconnect", "setup", "browser"), log)
        }

    @Test
    fun switchingRemovesTheOldComputerBeforeSavingTheNewOne() =
        runTest {
            forgetting.switchTo {
                assertTrue(credentials.passwords.isEmpty())
                profiles.add(ConnectionProfile("http://10.0.0.9:4096/", "opencode", "new.sig", id = "p2"))
            }
            assertEquals(listOf("p2"), profiles.profiles.map { it.id })
            assertEquals(mapOf("p2" to "new.sig"), credentials.passwords)
            assertEquals(listOf("setup", "browser"), log)
        }

    @Test
    fun theSameHostnameIsTheSameComputerWhateverItsAddresses() {
        assertTrue(PairAgain.isSameComputer("Ivans-MacBook-Pro.local", "ivans-macbook-pro", listOf("http://a:1"), listOf("http://b:1")))
        assertFalse(PairAgain.isSameComputer("mbp", "opencode-dev", listOf("http://a:1"), listOf("http://a:1")))
    }

    @Test
    fun withoutAHostnameOverlappingAddressesDecide() {
        assertTrue(PairAgain.isSameComputer(null, "mbp", listOf("http://192.168.0.47:4196/"), listOf("http://192.168.0.47:4196")))
        assertFalse(PairAgain.isSameComputer("", null, listOf("http://192.168.0.47:4196"), listOf("http://10.0.0.9:4096")))
    }
}
