package dev.opencode.mobile.domain.releasenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReleaseNotesParserTest {
    @Test
    fun parse_readsItemsAndDatesAndIgnoresUnknownFields() {
        val json =
            """
            {
              "schema": 2,
              "releases": [
                {
                  "android": { "version": "1.5.0", "released": "2026-10-27", "track": "production" },
                  "ios": { "version": "1.4.0", "released": null },
                  "items": [
                    { "kind": "new", "icon": "edit", "title": "Drafts", "body": "Write first.", "prs": [1005], "hero": true }
                  ]
                }
              ]
            }
            """.trimIndent()

        val releases = ReleaseNotesParser.parse(json, ReleaseNotesPlatform.Android)

        assertEquals(1, releases.size)
        val release = releases.single()
        assertEquals(SemanticVersion(1, 5, 0), release.version)
        assertEquals(LocalDate.of(2026, 10, 27), release.released)
        assertEquals(
            listOf(ReleaseNoteItem(ReleaseNoteKind.New, "edit", "Drafts", "Write first.")),
            release.items,
        )
        assertNull(ReleaseNotesParser.parse(json, ReleaseNotesPlatform.Ios).single().released)
    }

    @Test
    fun parse_readsAnUnknownKindAsImprovedAndKeepsAnUnknownIconKey() {
        val json =
            """
            { "releases": [ { "android": { "version": "1.0.0" }, "items": [
              { "kind": "removed", "icon": "rocket", "title": "Something", "body": "Else." }
            ] } ] }
            """.trimIndent()

        val item =
            ReleaseNotesParser
                .parse(json, ReleaseNotesPlatform.Android)
                .single()
                .items
                .single()

        assertEquals(ReleaseNoteKind.Improved, item.kind)
        assertEquals("rocket", item.iconKey)
    }

    @Test
    fun parse_keepsOnlyThisPlatformsReleasesAndItems() {
        val json =
            """
            { "releases": [
              { "ios": { "version": "1.3.0" }, "items": [ { "kind": "new", "icon": "edit", "title": "iOS only release", "body": "" } ] },
              { "android": { "version": "1.2.0" }, "ios": { "version": "1.1.0" }, "items": [
                { "kind": "new", "icon": "edit", "title": "Both", "body": "" },
                { "kind": "fixed", "icon": "fix", "title": "Android fix", "body": "", "platforms": ["android"] },
                { "kind": "fixed", "icon": "fix", "title": "iOS fix", "body": "", "platforms": ["ios"] }
              ] },
              { "android": { "version": "1.1.0" }, "ios": { "version": "1.0.0" }, "items": [
                { "kind": "fixed", "icon": "fix", "title": "Only on iOS", "body": "", "platforms": ["ios"] }
              ] }
            ] }
            """.trimIndent()

        val android = ReleaseNotesParser.parse(json, ReleaseNotesPlatform.Android)
        val ios = ReleaseNotesParser.parse(json, ReleaseNotesPlatform.Ios)

        assertEquals(listOf(SemanticVersion(1, 2, 0)), android.map { it.version })
        assertEquals(listOf("Both", "Android fix"), android.single().items.map { it.title })
        assertEquals(listOf(SemanticVersion(1, 3, 0), SemanticVersion(1, 1, 0), SemanticVersion(1, 0, 0)), ios.map { it.version })
        assertEquals(listOf("Both", "iOS fix"), ios[1].items.map { it.title })
    }

    @Test
    fun parse_sortsNewestFirstAndDropsUnreadableVersions() {
        val json =
            """
            { "releases": [
              { "android": { "version": "1.2.1" }, "items": [ { "kind": "new", "title": "a" } ] },
              { "android": { "version": "1.10.0" }, "items": [ { "kind": "new", "title": "b" } ] },
              { "android": { "version": "next" }, "items": [ { "kind": "new", "title": "c" } ] }
            ] }
            """.trimIndent()

        val versions = ReleaseNotesParser.parse(json, ReleaseNotesPlatform.Android).map { it.version.toString() }

        assertEquals(listOf("1.10.0", "1.2.1"), versions)
    }

    @Test
    fun parse_readsAMalformedFileAsNoReleases() {
        assertTrue(ReleaseNotesParser.parse("{ not json", ReleaseNotesPlatform.Android).isEmpty())
    }

    @Test
    fun release_groupsNewThenImprovedAboveFixes() {
        val release =
            ReleaseNote(
                version = SemanticVersion(1, 0, 0),
                released = null,
                items =
                    listOf(
                        ReleaseNoteItem(ReleaseNoteKind.Improved, "list", "Improved", ""),
                        ReleaseNoteItem(ReleaseNoteKind.Fixed, "fix", "Fixed", ""),
                        ReleaseNoteItem(ReleaseNoteKind.New, "edit", "New", ""),
                    ),
            )

        assertEquals(listOf("New", "Improved"), release.highlights.map { it.title })
        assertEquals(listOf("Fixed"), release.fixes.map { it.title })
    }

    @Test
    fun bundledReleaseNotes_parseWithAtLeastOneAndroidRelease() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/release-notes.json")) {
                "release-notes/release-notes.json is not on the test classpath"
            }.bufferedReader().use { it.readText() }

        val releases = ReleaseNotesParser.parse(text, ReleaseNotesPlatform.Android)

        assertTrue(releases.isNotEmpty())
        assertTrue(releases.all { it.items.isNotEmpty() })
    }
}
