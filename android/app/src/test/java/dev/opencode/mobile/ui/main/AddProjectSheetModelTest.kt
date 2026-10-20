package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddProjectSheetModelTest {
    private val homeListing =
        AddProjectListing.Loaded(
            listOf(
                DirectoryNode(".config", "/Users/me/.config", true),
                DirectoryNode("app", "/Users/me/app", true),
                DirectoryNode("code", "/Users/me/code", true),
                DirectoryNode("code-old", "/Users/me/code-old", true),
            ),
        )

    private fun session(
        fieldText: String,
        listings: Map<String, AddProjectListing> = mapOf("/Users/me" to homeListing),
        added: List<String> = emptyList(),
        serverProjects: List<Project> = emptyList(),
        isValidating: Boolean = false,
        openError: String? = null,
    ) = AddProjectSheetSession(
        home = "/Users/me",
        isOpening = false,
        openError = openError,
        fieldText = fieldText,
        listings = listings,
        serverProjects = serverProjects,
        addedWorktrees = added,
        isValidating = isValidating,
        browsing = true,
    )

    private fun build(session: AddProjectSheetSession) = AddProjectSheetModel.build(session, nowEpochMs = 0)

    @Test
    fun disabledReasons_coverEveryAddTitle() {
        val notFound = build(session("/Users/me/gone/", listings = mapOf("/Users/me/gone" to AddProjectListing.NotFound)))
        assertEquals(AddProjectSheetUi.PATH_NOT_FOUND to false, notFound.addTitle to notFound.addEnabled)

        val loading = build(session("/Users/me/code/", listings = mapOf("/Users/me/code" to AddProjectListing.Loading)))
        assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER to false, loading.addTitle to loading.addEnabled)

        val failed =
            build(session("/Users/me/code/", listings = mapOf("/Users/me/code" to AddProjectListing.Failed("x"))))
        assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER to false, failed.addTitle to failed.addEnabled)

        val nothingTyped = build(session(""))
        assertNull(nothingTyped.directory)
        assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER to false, nothingTyped.addTitle to nothingTyped.addEnabled)

        val pick = build(session("/Users/me/co"))
        assertEquals(AddProjectSheetUi.PICK_A_FOLDER to false, pick.addTitle to pick.addEnabled)

        val noMatch = build(session("/Users/me/zz"))
        assertEquals("No folder named “zz”" to false, noMatch.addTitle to noMatch.addEnabled)

        val onHome = build(session("/Users/me/app", added = listOf("/Users/me/app")))
        assertEquals(AddProjectSheetUi.ALREADY_ON_HOME to false, onHome.addTitle to onHome.addEnabled)
        assertEquals("Already on Home", onHome.addTitle)

        val home = build(session("/Users/me/"))
        assertEquals(AddProjectSheetUi.ADD_HOME_FOLDER to true, home.addTitle to home.addEnabled)

        val child = build(session("/Users/me/code"))
        assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER to true, child.addTitle to child.addEnabled)

        val validating = build(session("/Users/me/code", isValidating = true))
        assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER to false, validating.addTitle to validating.addEnabled)
    }

    @Test
    fun barTarget_namesTheAddTargetElseTheTypedFolder() {
        assertEquals(
            AddProjectBarTargetUi(name = "code", path = "/Users/me/code", isGitRepository = false),
            build(session("/Users/me/code")).barTarget,
        )
        assertEquals(
            AddProjectBarTargetUi(name = "me", path = "/Users/me", isGitRepository = false),
            build(session("/Users/me/co")).barTarget,
        )
        assertEquals(
            AddProjectBarTargetUi(name = "gone", path = "/Users/me/gone", isGitRepository = false),
            build(session("/Users/me/gone/", listings = mapOf("/Users/me/gone" to AddProjectListing.NotFound))).barTarget,
        )
        assertNull(build(session("")).barTarget)
    }

    @Test
    fun goTarget_isTheExactChildOrTheOnlyMatch() {
        assertNull(build(session("/Users/me/")).goTarget)
        assertEquals("/Users/me/code", build(session("/Users/me/code")).goTarget)
        assertNull(build(session("/Users/me/co")).goTarget)
        assertEquals("/Users/me/app", build(session("/Users/me/a")).goTarget)
        // Hidden folders match exactly even though they are not listed for a plain filter.
        assertEquals("/Users/me/.config", build(session("/Users/me/.config")).goTarget)
        assertNull(build(session("/Users/me/zz")).goTarget)
        assertNull(build(session("/Users/me/code", listings = emptyMap())).goTarget)
        assertNull(build(session("/Users/me/gone/x", listings = mapOf("/Users/me/gone" to AddProjectListing.NotFound))).goTarget)
    }

    @Test
    fun pathNotFound_clearsRowsAndErrors() {
        val sheet =
            build(session("/Users/me/gone/", listings = mapOf("/Users/me/gone" to AddProjectListing.NotFound)))

        assertTrue(sheet.pathNotFound)
        assertEquals(emptyList<AddProjectFolderUi>(), sheet.entries)
        assertNull(sheet.truncationHint)
        assertNull(sheet.listError)
        assertFalse(sheet.isLoading)
        assertEquals(AddProjectSheetUi.PATH_NOT_FOUND, sheet.emptyMessage)
    }

    @Test
    fun rows_describeCountsHomeAndGit() {
        val listings =
            mapOf(
                "/Users/me" to homeListing,
                "/Users/me/app" to
                    AddProjectListing.Loaded(
                        entries = listOf(DirectoryNode("src", "/Users/me/app/src", true)),
                        isGitRepository = true,
                    ),
                "/Users/me/code" to
                    AddProjectListing.Loaded(
                        listOf(
                            DirectoryNode(".cache", "/Users/me/code/.cache", true),
                            DirectoryNode("a", "/Users/me/code/a", true),
                            DirectoryNode("b", "/Users/me/code/b", true),
                        ),
                    ),
            )
        val sheet =
            build(
                session(
                    "/Users/me/",
                    listings = listings,
                    added = listOf("/Users/me/code", "/Users/me/code-old"),
                    serverProjects =
                        listOf(
                            Project(id = "o", worktree = "/Users/me/code-old/", displayName = "code-old", isRepository = true),
                        ),
                ),
            )

        assertEquals(listOf("app", "code", "code-old"), sheet.entries.map { it.name })
        assertEquals(listOf(1, 2, null), sheet.entries.map { it.folderCount })
        assertEquals(listOf("1 folder", "On Home · 2 folders", "On Home"), sheet.entries.map { it.subtitle })
        assertEquals(listOf(true, false, true), sheet.entries.map { it.isGitRepository })

        val empty =
            build(
                session(
                    "/Users/me/",
                    listings =
                        mapOf(
                            "/Users/me" to homeListing,
                            "/Users/me/app" to AddProjectListing.Loaded(emptyList()),
                        ),
                ),
            )
        assertEquals("Empty", empty.entries.first().subtitle)
    }
}
