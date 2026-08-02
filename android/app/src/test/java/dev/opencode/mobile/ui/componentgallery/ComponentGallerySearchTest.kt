package dev.opencode.mobile.ui.componentgallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentGallerySearchTest {
    private val entry =
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.TextInput,
            title = "Text Input",
            description = "Single-line form field with semantic kinds.",
            category = ComponentGalleryCategory.Controls,
        )

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(entry.matchesGallerySearch(""))
        assertTrue(entry.matchesGallerySearch("   "))
    }

    @Test
    fun matchesTitleDescriptionOrCategory() {
        assertTrue(entry.matchesGallerySearch("text"))
        assertTrue(entry.matchesGallerySearch("FORM FIELD"))
        assertTrue(entry.matchesGallerySearch("controls"))
        assertFalse(entry.matchesGallerySearch("snackbar"))
    }
}
