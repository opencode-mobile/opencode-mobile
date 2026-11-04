package dev.opencode.hexcode.firstrun

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cases from the build plan; iOS checks the same ones. */
class ServerNameTest {
    @Test
    fun namesFromThePlan() {
        assertEquals("Rapid Bison", ServerName.of("Ivans-MacBook-Pro.local", "http://192.168.1.2:4096"))
        assertEquals("Spry Seal", ServerName.of("mbp", "http://192.168.1.2:4096"))
        assertEquals("Quiet Lynx", ServerName.of("opencode-dev", "http://192.168.1.2:4096"))
    }

    @Test
    fun theHostnameIsLowercasedWithoutLocal() {
        assertEquals("ivans-macbook-pro", ServerName.seed("Ivans-MacBook-Pro.local", "http://192.168.1.2:4096"))
        assertEquals(ServerName.of("mbp"), ServerName.of("MBP.local", "http://192.168.1.2:4096"))
    }

    @Test
    fun withoutAHostnameTheFirstAddressIsTheSeed() {
        assertEquals("http://192.168.1.2:4096", ServerName.seed(null, "http://192.168.1.2:4096"))
        assertEquals("http://192.168.1.2:4096", ServerName.seed("  ", "http://192.168.1.2:4096"))
    }
}
