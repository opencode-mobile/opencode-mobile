package dev.opencode.hexcode.home

import android.webkit.WebViewClient
import dev.opencode.hexcode.session.SummaryLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** app-plan.md, Settings and finish (M6) › Testing › Browser. */
class InAppBrowserTest {
    @Test
    fun aLocalhostUrlKeepsItsPortPathQueryAndFragmentAndCarriesNoCredentials() {
        val url = SummaryLinks.onComputer("http://user:secret@localhost:5173/app/page?x=1&y=2#top", "192.168.1.47")
        assertEquals("http://192.168.1.47:5173/app/page?x=1&y=2#top", url)
        assertFalse(url.contains("secret") || url.contains("@"))
        assertEquals("http://mbp.local:8080/", SummaryLinks.onComputer("http://0.0.0.0:8080/", "mbp.local"))
    }

    @Test
    fun theSameOriginStaysAndAnythingElseGoesToTheSystem() {
        val start = "http://192.168.1.47:5173/index.html"
        assertEquals(BrowserNavigation.Stay, BrowserNavigation.of(start, "http://192.168.1.47:5173/second.html?q=1"))
        assertEquals(BrowserNavigation.System, BrowserNavigation.of(start, "http://192.168.1.47:8080/"))
        assertEquals(BrowserNavigation.System, BrowserNavigation.of(start, "https://example.com/"))
        assertEquals(BrowserNavigation.System, BrowserNavigation.of(start, "mailto:me@example.com"))
        assertEquals(BrowserNavigation.System, BrowserNavigation.of(start, "tel:+441234"))
        // The default port is the same origin as none.
        assertEquals(BrowserNavigation.Stay, BrowserNavigation.of("http://mbp.local/", "http://mbp.local:80/a"))
    }

    @Test
    fun aRefusedConnectionIsTheDevServersAndAMissingComputerIsTheComputers() {
        assertEquals(BrowserFailure.DevServer, BrowserFailure.of(WebViewClient.ERROR_CONNECT))
        assertEquals(BrowserFailure.Computer, BrowserFailure.of(WebViewClient.ERROR_HOST_LOOKUP))
        assertEquals(BrowserFailure.Computer, BrowserFailure.of(WebViewClient.ERROR_TIMEOUT))
    }
}
