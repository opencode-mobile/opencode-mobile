package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProjectPickerTest {
    private val home = "/Users/example"
    private val now = LocalDateTime.of(2026, 11, 4, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun showsAtMostFiveRepositoriesNewestFirst() {
        val projects =
            (1..7).map { i -> repo("p$i", "$home/Projects/app$i", activity = now - i * 60_000L) } +
                Project(id = "plain", worktree = "$home/notes", displayName = "notes", activityAtEpochMs = now, isRepository = false)
        val rows = ProjectPicker.rows(projects, home, now)
        assertEquals(listOf("app1", "app2", "app3", "app4", "app5"), rows.map { it.choice.name })
        assertEquals("$home/Projects/app1", rows.first().choice.path)
        assertEquals("~/Projects/app1", rows.first().shownPath)
    }

    @Test
    fun leavesOutTheGlobalProjectArchivedOnesAndScratchFolders() {
        val projects =
            listOf(
                Project(id = "global", worktree = "/", displayName = "global", isRepository = true),
                repo("old", "$home/old", archived = true),
                repo("tmp", "/private/tmp/scratch"),
                repo("dot", "$home/.cache/thing"),
                repo("keep", "$home/keep"),
            )
        assertEquals(listOf("keep"), ProjectPicker.rows(projects, home, now).map { it.choice.name })
    }

    @Test
    fun shortensTheHomeFolder() {
        assertEquals("~", ProjectPicker.shortPath(home, home))
        assertEquals("~/src/opencode", ProjectPicker.shortPath("$home/src/opencode", home))
        assertEquals("/opt/work", ProjectPicker.shortPath("/opt/work", home))
        assertEquals("/Users/examples/x", ProjectPicker.shortPath("/Users/examples/x", home))
    }

    @Test
    fun saysHowLongAgoAsThePrototypeDoes() {
        val zone = ZoneOffset.UTC
        assertEquals(Ago.Now, Ago.of(now - 20_000L, now, zone))
        assertEquals(Ago.Minutes(5), Ago.of(now - 5 * 60_000L, now, zone))
        assertEquals(Ago.Hours(2), Ago.of(now - 2 * 3_600_000L, now, zone))
        assertEquals(Ago.Yesterday, Ago.of(now - 20 * 3_600_000L, now, zone))
        assertEquals(Ago.Days(3), Ago.of(now - 3 * 86_400_000L, now, zone))
        assertEquals(Ago.Weeks(2), Ago.of(now - 15 * 86_400_000L, now, zone))
    }

    @Test
    fun browserRowsCarryGitTagsAndFolderCountsOnceListed() {
        val projectsDir = "$home/Projects"
        val browser =
            FolderBrowserState(home = home, directory = projectsDir)
                .withListing(projectsDir, loaded("hexcode", "notes", ".hidden", parent = projectsDir))
                .withListing(
                    "$projectsDir/hexcode",
                    FolderListing.Loaded(DirectoryListing(listOf(node("prototype", "$projectsDir/hexcode")), isGitRepository = true)),
                )
        val rows = browser.rows
        assertEquals(listOf("hexcode", "notes"), rows.map { it.name })
        assertTrue(rows[0].isGitRepository)
        assertEquals(1, rows[0].folderCount)
        assertFalse(rows[1].isGitRepository)
        assertEquals(null, rows[1].folderCount)
    }

    @Test
    fun theHomeFolderItselfCannotBeUsed() {
        val atHome = FolderBrowserState(home = home).withListing(home, loaded("Projects", parent = home))
        assertFalse(atHome.canUse)
        val inProjects = atHome.open("$home/Projects").withListing("$home/Projects", loaded(parent = "$home/Projects"))
        assertTrue(inProjects.canUse)
        assertEquals(ProjectChoice("Projects", "$home/Projects"), inProjects.choice)
    }

    @Test
    fun aFolderThatIsStillLoadingOrMissingCannotBeUsed() {
        val browser = FolderBrowserState(home = home).open("$home/gone")
        assertFalse(browser.canUse)
        assertFalse(browser.withListing("$home/gone", FolderListing.NotFound).canUse)
    }

    @Test
    fun upGoesToTheParentAndStopsAtTheRoot() {
        assertEquals(home, FolderBrowserState(home = home, directory = "$home/Projects").upTarget)
        assertEquals(null, FolderBrowserState(home = home, directory = "/").upTarget)
    }

    @Test
    fun rootsAreHomeSlashAndVolumes() {
        val browser =
            FolderBrowserState(
                home = home,
                directory = "/",
                volumes = listOf(node("Backup", "/Volumes"), node(".timemachine", "/Volumes")),
            )
        assertEquals(listOf(null, "/", "Backup"), browser.roots.map { it.label })
        assertEquals(listOf(false, true, false), browser.roots.map { it.selected })
    }

    @Test
    fun aFailedListingIsTriedAgain() {
        val browser = FolderBrowserState(home = home)
        assertTrue(browser.needsListing(home))
        assertTrue(browser.withListing(home, FolderListing.Failed).needsListing(home))
        assertFalse(browser.withListing(home, FolderListing.NotFound).needsListing(home))
    }

    private fun repo(
        name: String,
        path: String,
        activity: Long? = null,
        archived: Boolean = false,
    ) = Project(
        id = name,
        worktree = path,
        displayName = path.substringAfterLast('/'),
        activityAtEpochMs = activity,
        isRepository = true,
        isArchived = archived,
    )

    private fun node(
        name: String,
        parent: String,
    ) = DirectoryNode(name, "$parent/$name", isDirectory = true)

    private fun loaded(
        vararg names: String,
        parent: String,
    ) = FolderListing.Loaded(DirectoryListing(names.map { node(it, parent) }, isGitRepository = false))
}
