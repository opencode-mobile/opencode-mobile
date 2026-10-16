package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFileReferenceTest {
    @Test
    fun url_encodesSpacesAndKeepsSlashes() {
        assertEquals("file:///repo/src/App.kt", ProjectFileReference.url("/repo", "src/App.kt"))
        assertEquals("file:///repo/my%20file.kt", ProjectFileReference.url("/repo", "my file.kt"))
        assertEquals("file:///repo/a%2Bb.kt", ProjectFileReference.url("/repo", "a+b.kt"))
    }
}
