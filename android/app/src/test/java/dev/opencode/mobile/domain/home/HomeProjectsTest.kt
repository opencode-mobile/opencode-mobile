package dev.opencode.mobile.domain.home

import dev.opencode.mobile.domain.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeProjectsTest {
    private fun repo(
        id: String = "proj",
        canonical: String = "/Users/me/repo",
        name: String? = null,
    ) = ResolvedHomePath(projectId = id, canonicalWorktree = canonical, name = name, isRepository = true)

    @Test
    fun pathsOfOneProjectMergeInFirstMemberOrder() {
        val projects =
            HomeProjectGrouping.group(
                addedPaths = listOf("/Users/me/other", "/Users/me/repo-wt", "/Users/me/repo"),
                resolved =
                    mapOf(
                        "/Users/me/other" to repo(id = "other", canonical = "/Users/me/other"),
                        "/Users/me/repo-wt" to repo(),
                        "/Users/me/repo" to repo(),
                    ),
            )

        assertEquals(listOf("other", "proj"), projects.map { it.key })
        val merged = projects[1]
        assertEquals(listOf("/Users/me/repo-wt", "/Users/me/repo"), merged.memberPaths)
        assertEquals("/Users/me/repo-wt", merged.primaryPath)
        assertEquals("proj", merged.projectId)
        assertEquals("repo", merged.displayName)
        assertTrue(merged.isRepository)
        assertEquals(listOf("/Users/me/repo", "/Users/me/repo-wt"), merged.worktrees.map { it.directory })
        assertEquals(listOf(true, false), merged.worktrees.map { it.isMain })
    }

    @Test
    fun globalProjectsNeverMerge() {
        val global = ResolvedHomePath(projectId = "global", canonicalWorktree = "/")
        val projects =
            HomeProjectGrouping.group(
                addedPaths = listOf("/tmp/a", "/tmp/b"),
                resolved = mapOf("/tmp/a" to global, "/tmp/b" to global),
            )

        assertEquals(listOf("path:/tmp/a", "path:/tmp/b"), projects.map { it.key })
        assertEquals(listOf("a", "b"), projects.map { it.displayName })
        assertTrue(projects.none { it.isRepository })
        assertTrue(projects.all { it.projectId == null })
        assertEquals(listOf("/tmp/a"), projects[0].worktrees.map { it.directory })
    }

    @Test
    fun unresolvedPathsStayTheirOwnProject() {
        val projects =
            HomeProjectGrouping.group(
                addedPaths = listOf("/Users/me/repo", "/Users/me/repo-wt"),
                resolved = mapOf("/Users/me/repo" to repo()),
            )

        assertEquals(listOf("proj", "path:/Users/me/repo-wt"), projects.map { it.key })
        assertEquals("repo-wt", projects[1].displayName)
        assertFalse(projects[1].isRepository)
    }

    @Test
    fun displayNamePrefersTheProjectNameThenTheMainCheckoutFolder() {
        val named =
            HomeProjectGrouping.group(listOf("/Users/me/repo-wt"), mapOf("/Users/me/repo-wt" to repo(name = "OpenCode Mobile")))
        val unnamed = HomeProjectGrouping.group(listOf("/Users/me/repo-wt"), mapOf("/Users/me/repo-wt" to repo()))

        assertEquals("OpenCode Mobile", named.single().displayName)
        assertEquals("repo", unnamed.single().displayName)
    }

    @Test
    fun worktreesComeFromMembersAndInventoriesMainFirstThenByFolder() {
        val project =
            HomeProjectGrouping
                .group(
                    addedPaths = listOf("/Users/me/repo-wt"),
                    resolved = mapOf("/Users/me/repo-wt" to repo()),
                    inventories =
                        mapOf(
                            "/Users/me/repo-wt" to setOf("/Users/me/zeta", "/Users/me/repo", "/Users/me/alpha/"),
                        ),
                    branches = mapOf("/Users/me/repo" to "dev", "/Users/me/zeta" to "feature/z"),
                ).single()

        assertEquals(
            listOf(
                HomeWorktree("/Users/me/repo", branch = "dev", isMain = true),
                HomeWorktree("/Users/me/alpha", branch = null, isMain = false),
                HomeWorktree("/Users/me/repo-wt", branch = null, isMain = false),
                HomeWorktree("/Users/me/zeta", branch = "feature/z", isMain = false),
            ),
            project.worktrees,
        )
    }

    @Test
    fun resolvedHomePathFromProject() {
        val v1Repo = ResolvedHomePath.from(Project(id = "abc", worktree = "/repo", displayName = "repo", isRepository = true, name = " "))
        val v2Repo = ResolvedHomePath.from(Project(id = "abc", worktree = "/repo", displayName = "repo"))
        val v1Plain = ResolvedHomePath.from(Project(id = "global", worktree = "/", displayName = ""))

        assertTrue(v1Repo.isRepository)
        assertNull(v1Repo.name)
        // v2 locations carry no vcs; grouping decides once a branch is known.
        assertFalse(v2Repo.isRepository)
        assertTrue(v2Repo.isMergeable)
        assertFalse(v1Plain.isMergeable)
        assertFalse(v1Plain.isRepository)
    }

    @Test
    fun aKnownBranchMakesAProjectWithoutVcsARepository() {
        val noVcs = ResolvedHomePath(projectId = "proj", canonicalWorktree = "/Users/me/repo")
        val unknown = HomeProjectGrouping.group(listOf("/Users/me/repo"), mapOf("/Users/me/repo" to noVcs)).single()
        val branched =
            HomeProjectGrouping
                .group(
                    addedPaths = listOf("/Users/me/repo"),
                    resolved = mapOf("/Users/me/repo" to noVcs),
                    branches = mapOf("/Users/me/repo" to "dev"),
                ).single()

        assertFalse(unknown.isRepository)
        assertEquals("proj", unknown.key)
        assertNull(HomeSessionLocation.parts(unknown, "/Users/me/repo", includeProjectName = true))
        assertTrue(branched.isRepository)
    }

    @Test
    fun worktreeScopeFindsItsDirectoryWhenTheProjectKeyChanged() {
        val projects =
            HomeProjectGrouping.group(
                addedPaths = listOf("/Users/me/repo", "/Users/me/repo-wt"),
                resolved = mapOf("/Users/me/repo" to repo(), "/Users/me/repo-wt" to repo()),
            )

        assertEquals(
            HomeScope.Worktree("proj", "/Users/me/repo-wt"),
            HomeScope.resolve(HomeScope.worktreeId("path:/Users/me/repo-wt", "/Users/me/repo-wt"), projects),
        )
        assertEquals("worktree:proj\u001F/Users/me/repo-wt", HomeScope.worktreeId("proj", "/Users/me/repo-wt/"))
    }

    @Test
    fun normalizedBranchDropsDetachedAndBlank() {
        assertEquals("main", HomeProjectGrouping.normalizedBranch(" main "))
        assertNull(HomeProjectGrouping.normalizedBranch("HEAD"))
        assertNull(HomeProjectGrouping.normalizedBranch(""))
        assertNull(HomeProjectGrouping.normalizedBranch(null))
    }

    @Test
    fun scopeIdsRoundTripAndAddedPathsMapToTheirProject() {
        val projects =
            HomeProjectGrouping.group(
                addedPaths = listOf("/Users/me/repo", "/Users/me/repo-wt", "/tmp/plain"),
                resolved = mapOf("/Users/me/repo" to repo(), "/Users/me/repo-wt" to repo()),
            )
        val worktreeId = HomeScope.worktreeId("proj", "/Users/me/repo-wt")

        assertEquals(HomeScope.All, HomeScope.resolve("all", projects))
        assertEquals(HomeScope.Project("proj"), HomeScope.resolve("proj", projects))
        assertEquals(HomeScope.Worktree("proj", "/Users/me/repo-wt"), HomeScope.resolve(worktreeId, projects))
        assertEquals(worktreeId, HomeScope.id(HomeScope.Worktree("proj", "/Users/me/repo-wt")))
        // A persisted added-path scope now belongs to the merged project.
        assertEquals(HomeScope.Project("proj"), HomeScope.resolve("/Users/me/repo-wt", projects))
        assertEquals(HomeScope.Project("path:/tmp/plain"), HomeScope.resolve("/tmp/plain", projects))
        assertNull(HomeScope.resolve("/gone", projects))
        assertNull(HomeScope.resolve(HomeScope.worktreeId("proj", "/gone"), projects))
    }

    @Test
    fun worktreeScopeOfASingleWorktreeProjectReadsAsTheProject() {
        val projects = HomeProjectGrouping.group(listOf("/Users/me/repo"), mapOf("/Users/me/repo" to repo()))

        assertEquals(HomeScope.Project("proj"), HomeScope.resolve(HomeScope.worktreeId("proj", "/Users/me/repo"), projects))
    }

    @Test
    fun rowLocationShowsProjectBranchAndNonMainFolder() {
        val project =
            HomeProjectGrouping
                .group(
                    addedPaths = listOf("/Users/me/repo", "/Users/me/repo-723"),
                    resolved = mapOf("/Users/me/repo" to repo(), "/Users/me/repo-723" to repo()),
                    branches = mapOf("/Users/me/repo" to "dev", "/Users/me/repo-723" to "feature/723-iphone-duo"),
                ).single()

        assertEquals(
            listOf(
                HomeSessionLocation.Part("repo"),
                HomeSessionLocation.Part("feature/723-iphone-duo", isBranch = true),
                HomeSessionLocation.Part("repo-723"),
            ),
            HomeSessionLocation.parts(project, "/Users/me/repo-723", includeProjectName = true),
        )
        assertEquals(
            listOf(HomeSessionLocation.Part("dev", isBranch = true)),
            HomeSessionLocation.parts(project, "/Users/me/repo", includeProjectName = false),
        )
        assertEquals(
            "repo · dev",
            HomeSessionLocation.text(HomeSessionLocation.parts(project, "/Users/me/repo/", includeProjectName = true)!!),
        )
    }

    @Test
    fun rowLocationFallsBackToTheFolderWhenTheBranchIsUnknown() {
        val project =
            HomeProjectGrouping.group(listOf("/Users/me/repo"), mapOf("/Users/me/repo" to repo())).single()

        assertEquals(
            listOf(HomeSessionLocation.Part("repo")),
            HomeSessionLocation.parts(project, "/Users/me/repo", includeProjectName = false),
        )
        assertEquals(
            listOf(HomeSessionLocation.Part("unlisted")),
            HomeSessionLocation.parts(project, "/Users/me/unlisted", includeProjectName = false),
        )
    }

    @Test
    fun rowLocationKeepsThePathForNonRepositories() {
        val plain = HomeProjectGrouping.group(listOf("/tmp/plain"), emptyMap()).single()

        assertNull(HomeSessionLocation.parts(plain, "/tmp/plain", includeProjectName = true))
    }

    @Test
    fun worktreeCopy() {
        val main = HomeWorktree("/Users/me/repo", branch = "dev", isMain = true)
        val other = HomeWorktree("/Users/me/repo-723", branch = "feature/x", isMain = false)
        val unknown = HomeWorktree("/Users/me/repo-9", branch = null, isMain = false)

        assertEquals("dev", HomeWorktreeCopy.worktreeTitle(main))
        assertEquals("repo-9", HomeWorktreeCopy.worktreeTitle(unknown))
        assertEquals("main checkout", HomeWorktreeCopy.worktreeSubtitle(main))
        assertEquals("repo-723", HomeWorktreeCopy.worktreeSubtitle(other))
        assertEquals("dev", HomeWorktreeCopy.worktreePill(main))
        assertEquals("feature/x · repo-723", HomeWorktreeCopy.worktreePill(other))
        assertEquals("repo-9", HomeWorktreeCopy.worktreePill(unknown))
        assertEquals("3 worktrees", HomeWorktreeCopy.worktreeCount(3))
        assertEquals("All 3 worktrees", HomeWorktreeCopy.allWorktrees(3))
        assertEquals("2 running", HomeWorktreeCopy.runningCount(2))
        assertEquals(
            "Same repo as opencode-mobile. Showing it as a worktree there.",
            HomeWorktreeCopy.joinedProject("opencode-mobile"),
        )
    }

    private fun twoWorktreeProject(addedPaths: List<String> = listOf("/Users/me/repo-wt")): HomeProject =
        HomeProjectGrouping
            .group(
                addedPaths = addedPaths,
                resolved = addedPaths.associateWith { repo() },
                inventories = mapOf(addedPaths.first() to setOf("/Users/me/repo", "/Users/me/repo-wt")),
                branches = mapOf("/Users/me/repo" to "dev", "/Users/me/repo-wt" to "feature/x"),
            ).single()

    @Test
    fun mainCheckoutDirectoryIsTheMainWorktreeNotTheFirstAddedPath() {
        val project = twoWorktreeProject()

        assertEquals("/Users/me/repo-wt", project.primaryPath)
        assertEquals("/Users/me/repo", project.mainCheckoutDirectory)
        val plain = HomeProjectGrouping.group(listOf("/tmp/a"), emptyMap()).single()
        assertEquals("/tmp/a", plain.mainCheckoutDirectory)
    }

    @Test
    fun worktreesOffResolvesAWorktreeScopeToItsProjectAndBackWhenOn() {
        val projects = listOf(twoWorktreeProject())
        val worktreeId = HomeScope.worktreeId("proj", "/Users/me/repo-wt")

        assertEquals(HomeScope.Project("proj"), HomeScope.resolve(worktreeId, projects, worktreesEnabled = false))
        assertEquals(HomeScope.Worktree("proj", "/Users/me/repo-wt"), HomeScope.resolve(worktreeId, projects, worktreesEnabled = true))
    }

    @Test
    fun worktreesOffRowsLoseBranchAndFolderAndGetThemBackWhenOn() {
        val project = twoWorktreeProject()

        assertNull(HomeSessionLocation.parts(project, "/Users/me/repo-wt", includeProjectName = false, worktreesEnabled = false))
        assertEquals(
            listOf(HomeSessionLocation.Part("feature/x", isBranch = true), HomeSessionLocation.Part("repo-wt")),
            HomeSessionLocation.parts(project, "/Users/me/repo-wt", includeProjectName = false, worktreesEnabled = true),
        )
    }
}
