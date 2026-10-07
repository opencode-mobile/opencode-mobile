package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileCatalogTest {
    @Test
    fun saveFirstProfile_assignsIdAndMakesActive() {
        val catalog = ConnectionProfileCatalog().save(profile(baseUrl = "http://127.0.0.1:4096", name = "Mini"))
        val saved = catalog.profiles.single()
        assertTrue(saved.id.isNotBlank())
        assertEquals("http://127.0.0.1:4096/", saved.baseUrl)
        assertEquals("Mini", saved.name)
        assertEquals(saved.id, catalog.activeId)
        assertEquals("Mini", catalog.activeProfile()?.displayName)
    }

    @Test
    fun saveNewProfile_appendsAndActivates() {
        val first = ConnectionProfileCatalog().save(profile(baseUrl = "http://10.0.0.1:4096/", name = "Home"))
        val second = first.save(profile(baseUrl = "http://10.0.0.2:4096/", name = "Work"))
        assertEquals(2, second.profiles.size)
        assertEquals("Work", second.activeProfile()?.name)
        assertEquals("Home", first.activeProfile()?.name)
    }

    @Test
    fun saveExistingProfile_doesNotSwitchActive() {
        val first = ConnectionProfileCatalog().save(profile(id = "home", baseUrl = "http://10.0.0.1:4096/", name = "Home"))
        val two = first.save(profile(id = "work", baseUrl = "http://10.0.0.2:4096/", name = "Work"))
        val edited = two.save(profile(id = "home", baseUrl = "http://10.0.0.1:4096/", name = "Studio"))
        assertEquals("work", edited.activeId)
        assertEquals("Studio", edited.profiles.first { it.id == "home" }.name)
    }

    @Test(expected = DuplicateConnectionProfileException::class)
    fun saveRejectsDuplicateUrlAndUsername() {
        val catalog = ConnectionProfileCatalog().save(profile(baseUrl = "http://10.0.0.1:4096/", username = "opencode"))
        catalog.save(profile(baseUrl = "http://10.0.0.1:4096", username = "opencode"))
    }

    @Test(expected = DuplicateConnectionProfileException::class)
    fun saveRejectsSecondProfileUsingAnotherStoredAddress() {
        val catalog =
            ConnectionProfileCatalog().save(
                profile(
                    id = "home",
                    baseUrl = "http://100.84.57.15:4096/",
                    username = "opencode",
                    serverUrls = listOf("http://192.168.0.160:4096/", "http://100.84.57.15:4096/"),
                ),
            )
        catalog.save(profile(baseUrl = "http://192.168.0.160:4096/", username = "opencode"))
    }

    @Test
    fun saveAllowsSameUrlDifferentUsername() {
        val catalog =
            ConnectionProfileCatalog()
                .save(profile(baseUrl = "http://10.0.0.1:4096/", username = "opencode"))
                .save(profile(baseUrl = "http://10.0.0.1:4096/", username = "other"))
        assertEquals(2, catalog.profiles.size)
    }

    @Test
    fun blankName_fallsBackToHostLabel() {
        val profile = profile(baseUrl = "http://192.168.1.42:4096/", name = "  ")
        assertEquals("192.168.1.42:4096", profile.displayName)
    }

    @Test
    fun removeActive_selectsNextThenPrevious() {
        val catalog =
            ConnectionProfileCatalog()
                .save(profile(id = "a", baseUrl = "http://10.0.0.1:4096/", name = "A"))
                .save(profile(id = "b", baseUrl = "http://10.0.0.2:4096/", name = "B"))
                .save(profile(id = "c", baseUrl = "http://10.0.0.3:4096/", name = "C"))
                .apply("b")
        val afterB = catalog.remove("b")
        assertEquals("c", afterB.activeId)
        val afterC = afterB.remove("c")
        assertEquals("a", afterC.activeId)
        val empty = afterC.remove("a")
        assertNull(empty.activeId)
        assertTrue(empty.profiles.isEmpty())
    }

    @Test
    fun removeNonActive_leavesActiveUnchanged() {
        val catalog =
            ConnectionProfileCatalog()
                .save(profile(id = "a", baseUrl = "http://10.0.0.1:4096/", name = "A"))
                .save(profile(id = "b", baseUrl = "http://10.0.0.2:4096/", name = "B"))
        val removed = catalog.remove("a")
        assertEquals("b", removed.activeId)
        assertEquals(listOf("b"), removed.profiles.map { it.id })
    }

    @Test
    fun generatedIdsDiffer() {
        val first = ConnectionProfileCatalog().save(profile(baseUrl = "http://10.0.0.1:4096/"))
        val second = ConnectionProfileCatalog().save(profile(baseUrl = "http://10.0.0.2:4096/"))
        assertNotEquals(first.profiles.single().id, second.profiles.single().id)
    }

    private fun profile(
        baseUrl: String,
        username: String = "opencode",
        name: String = "",
        id: String = "",
        serverUrls: List<String> = emptyList(),
    ): ConnectionProfile =
        ConnectionProfile(
            baseUrl = baseUrl,
            username = username,
            password = "secret",
            id = id,
            name = name,
            serverUrls = serverUrls,
        )
}
