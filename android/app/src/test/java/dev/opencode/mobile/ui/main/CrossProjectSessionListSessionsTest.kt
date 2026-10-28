package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleSegment
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleTone
import dev.opencode.mobile.ui.components.SessionListLocationPart
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.visibleForFilter
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossProjectSessionListSessionsTest {
    @Test
    fun sameIdInTwoProjects_keepsNewerRowFromLaterProject() {
        val sessions =
            listOf(
                section(worktree = "/earlier", session(id = "ses", title = "older", updatedAtEpochMs = 10L)),
                section(worktree = "/later", session(id = "ses", title = "newer", updatedAtEpochMs = 20L)),
            ).toCrossProjectSessionListSessions()

        assertEquals(listOf("ses"), sessions.map { it.id })
        assertEquals("newer", sessions.single().title)
        assertEquals("path:/later", sessions.single().worktree)
        assertEquals(20L, sessions.single().updatedAtEpochMs)
    }

    @Test
    fun sameId_equalTimestamps_keepsEarlierProject() {
        val sessions =
            listOf(
                section(worktree = "/earlier", session(id = "ses", title = "first", updatedAtEpochMs = 10L)),
                section(worktree = "/later", session(id = "ses", title = "second", updatedAtEpochMs = 10L)),
            ).toCrossProjectSessionListSessions()

        assertEquals(listOf("ses"), sessions.map { it.id })
        assertEquals("first", sessions.single().title)
        assertEquals("path:/earlier", sessions.single().worktree)
    }

    @Test
    fun sameId_nullTimestampLosesToRealTimestamp() {
        val realThenNull =
            listOf(
                section(worktree = "/real", session(id = "ses", title = "dated", updatedAtEpochMs = 50L)),
                section(worktree = "/missing", session(id = "ses", title = "undated", updatedAtEpochMs = null)),
            ).toCrossProjectSessionListSessions()
        val nullThenReal =
            listOf(
                section(worktree = "/missing", session(id = "ses", title = "undated", updatedAtEpochMs = null)),
                section(worktree = "/real", session(id = "ses", title = "dated", updatedAtEpochMs = 50L)),
            ).toCrossProjectSessionListSessions()

        assertEquals("path:/real", realThenNull.single().worktree)
        assertEquals(50L, realThenNull.single().updatedAtEpochMs)
        assertEquals("path:/real", nullThenReal.single().worktree)
        assertEquals(50L, nullThenReal.single().updatedAtEpochMs)
    }

    @Test
    fun distinctIds_stayNewestFirst() {
        val sessions =
            listOf(
                section(
                    worktree = "/a",
                    session(id = "old", title = "Old", updatedAtEpochMs = 100L),
                    session(id = "tie-early", title = "Early", updatedAtEpochMs = 50L),
                ),
                section(
                    worktree = "/b",
                    session(id = "tie-late", title = "Late", updatedAtEpochMs = 50L),
                    session(id = "newest", title = "Newest", updatedAtEpochMs = 200L),
                ),
            ).toCrossProjectSessionListSessions()

        assertEquals(listOf("newest", "old", "tie-early", "tie-late"), sessions.map { it.id })
    }

    @Test
    fun sameIdTwiceInOneProject_keepsNewerRow() {
        val newerLast =
            listOf(
                section(
                    worktree = "/a",
                    session(id = "ses", title = "older", updatedAtEpochMs = 10L),
                    session(id = "ses", title = "newer", updatedAtEpochMs = 30L),
                ),
            ).toCrossProjectSessionListSessions()
        val newerFirst =
            listOf(
                section(
                    worktree = "/a",
                    session(id = "ses", title = "newer", updatedAtEpochMs = 30L),
                    session(id = "ses", title = "older", updatedAtEpochMs = 10L),
                ),
            ).toCrossProjectSessionListSessions()

        assertEquals("newer", newerLast.single().title)
        assertEquals(30L, newerLast.single().updatedAtEpochMs)
        assertEquals("newer", newerFirst.single().title)
        assertEquals(30L, newerFirst.single().updatedAtEpochMs)
    }

    @Test
    fun worktreeSession_staysInAddedProjectButOpensItsOwnDirectory() {
        val sessions =
            listOf(
                section(
                    worktree = "/repo",
                    session(
                        id = "ses_worktree",
                        title = "Add project sheet prototype",
                        updatedAtEpochMs = 20L,
                        directory = "/worktrees/add-project-browse",
                    ),
                ),
            ).toCrossProjectSessionListSessions()

        assertEquals("path:/repo", sessions.single().worktree)
        assertEquals("/worktrees/add-project-browse", sessions.single().directory)
        assertEquals(null, sessions.single().location)
        assertEquals("/repo", sessions.single().subtitle)
    }

    @Test
    fun mergedProject_listsEachSessionOnceWithItsBranchAndWorktree() {
        val sections =
            listOf(
                // v2 lists the whole project from either added worktree.
                section(
                    worktree = "/repo",
                    session(id = "main", title = "Main", updatedAtEpochMs = 10L, directory = "/repo"),
                    session(id = "wt", title = "Checkout", updatedAtEpochMs = 20L, directory = "/repo-wt"),
                ),
                section(
                    worktree = "/repo-wt",
                    session(id = "main", title = "Main", updatedAtEpochMs = 10L, directory = "/repo"),
                    session(id = "wt", title = "Checkout", updatedAtEpochMs = 20L, directory = "/repo-wt"),
                ),
            )
        val projects = mergedProjects(branches = mapOf("/repo" to "dev", "/repo-wt" to "feature/723-iphone-duo"))

        val sessions = homeSessionListSessions(projects, sections)

        assertEquals(listOf("wt", "main"), sessions.map { it.id })
        assertEquals(setOf("proj"), sessions.map { it.worktree }.toSet())
        val checkout = sessions.first { it.id == "wt" }
        assertEquals(HomeScope.worktreeId("proj", "/repo-wt"), checkout.worktreeScopeId)
        assertEquals(
            listOf(SessionListLocationPart("feature/723-iphone-duo", isBranch = true), SessionListLocationPart("repo-wt")),
            checkout.location,
        )
        assertEquals("repo", checkout.projectName)
        assertEquals(listOf("repo"), checkout.locationLines()?.subtitle?.map { it.text })
        assertEquals("feature/723-iphone-duo · repo-wt", checkout.locationLines()?.detail?.joinToString("") { it.text })
        assertEquals(listOf(SessionListLocationPart("dev", isBranch = true)), sessions.first { it.id == "main" }.location)
    }

    @Test
    fun compactRows_showTheShortBranchOnlyOutsideTheMainCheckout() {
        val sections =
            listOf(
                section(
                    worktree = "/repo",
                    session(id = "main", title = "Main", updatedAtEpochMs = 10L, directory = "/repo"),
                    session(id = "wt", title = "Checkout", updatedAtEpochMs = 20L, directory = "/repo-wt"),
                ),
            )
        val projects = mergedProjects(branches = mapOf("/repo" to "dev", "/repo-wt" to "feature/723-iphone-duo"))

        val on = homeSessionListSessions(projects, sections)
        val off = homeSessionListSessions(projects, sections, worktreesEnabled = false)

        assertEquals("723-iphone-duo", on.first { it.id == "wt" }.compactWorktree)
        assertNull(on.first { it.id == "main" }.compactWorktree)
        assertEquals(listOf(null, null), off.map { it.compactWorktree })
        assertEquals("repo", on.first { it.id == "wt" }.compactPath().project)
        assertEquals("repo", off.first { it.id == "wt" }.compactPath().project)
    }

    @Test
    fun rowLines_putProjectAndWorktreeOnSeparateLines() {
        fun row(
            location: List<SessionListLocationPart>?,
            project: String?,
        ) = SessionListSession(
            id = "s",
            title = "T",
            worktree = "prj",
            letter = 'R',
            letterAccent = LetterAccent.Red,
            subtitle = "/repo",
            status = SessionListStatus.Idle,
            caption = "",
            location = location,
            projectName = project,
        )
        val secondary = OpenCodeListItemSubtitleTone.Secondary
        val tertiary = OpenCodeListItemSubtitleTone.Tertiary

        val main = row(listOf(SessionListLocationPart("main", isBranch = true)), "repo").locationLines()
        assertEquals(listOf(OpenCodeListItemSubtitleSegment("repo", secondary, OpenCodeIcons.Folder)), main?.subtitle)
        assertEquals(listOf(OpenCodeListItemSubtitleSegment("main", tertiary, OpenCodeIcons.CallSplit)), main?.detail)

        val worktree =
            row(
                listOf(SessionListLocationPart("feature/b", isBranch = true), SessionListLocationPart("repo-wt")),
                "repo",
            ).locationLines()
        assertEquals(
            listOf(
                OpenCodeListItemSubtitleSegment("feature/b", tertiary, OpenCodeIcons.CallSplit),
                OpenCodeListItemSubtitleSegment(" · ", tertiary),
                OpenCodeListItemSubtitleSegment("repo-wt", tertiary),
            ),
            worktree?.detail,
        )

        // Detached HEAD: folder name only, still on its own line.
        assertEquals(
            listOf(OpenCodeListItemSubtitleSegment("brave-otter", tertiary)),
            row(listOf(SessionListLocationPart("brave-otter")), "repo").locationLines()?.detail,
        )

        // Non-git project keeps the path subtitle.
        assertNull(row(null, "notes").locationLines())

        // No project name: the worktree line moves up to the subtitle.
        val unnamed = row(listOf(SessionListLocationPart("main", isBranch = true)), null).locationLines()
        assertEquals(listOf(OpenCodeListItemSubtitleSegment("main", secondary, OpenCodeIcons.CallSplit)), unnamed?.subtitle)
        assertNull(unnamed?.detail)
    }

    @Test
    fun worktreeScope_filtersByTheSessionDirectory() {
        val sections =
            listOf(
                section(
                    worktree = "/repo",
                    session(id = "main", title = "Main", updatedAtEpochMs = 10L, directory = "/repo"),
                    session(id = "wt", title = "Checkout", updatedAtEpochMs = 20L, directory = "/repo-wt"),
                    session(id = "sub", title = "Subfolder", updatedAtEpochMs = 5L, directory = "/repo/packages/app"),
                ),
                section(worktree = "/repo-wt"),
            )
        val sessions = homeSessionListSessions(mergedProjects(), sections)

        assertEquals(listOf("wt", "main", "sub"), sessions.visibleForFilter("proj").map { it.id })
        assertEquals(listOf("wt"), sessions.visibleForFilter(HomeScope.worktreeId("proj", "/repo-wt")).map { it.id })
        assertEquals(listOf("main"), sessions.visibleForFilter(HomeScope.worktreeId("proj", "/repo")).map { it.id })
    }

    @Test
    fun extraWorktreeSessions_joinTheirProject() {
        val projects =
            HomeProjectGrouping.group(
                addedPaths = listOf("/repo"),
                resolved = mapOf("/repo" to ResolvedHomePath("proj", "/repo", isRepository = true)),
                inventories = mapOf("/repo" to setOf("/repo", "/wt-unadded")),
            )
        val sessions =
            homeSessionListSessions(
                projects,
                listOf(section(worktree = "/repo", session(id = "main", title = "Main", updatedAtEpochMs = 1L))),
                extraSessions =
                    mapOf("/wt-unadded" to listOf(session(id = "extra", title = "Elsewhere", updatedAtEpochMs = 2L))),
            )

        assertEquals(listOf("extra", "main"), sessions.map { it.id })
        assertEquals("/wt-unadded", sessions.first().directory)
        assertEquals(HomeScope.worktreeId("proj", "/wt-unadded"), sessions.first().worktreeScopeId)
    }

    @Test
    fun chromeProjects_countProjectsWorktreesAndRunningSessions() {
        val sections =
            listOf(
                section(
                    worktree = "/repo",
                    session(id = "main", title = "Main", updatedAtEpochMs = 10L, directory = "/repo"),
                    session(id = "wt", title = "Checkout", updatedAtEpochMs = 20L, directory = "/repo-wt", statusLabel = "Working"),
                ),
                section(worktree = "/repo-wt"),
            )
        val projects = mergedProjects()
        val sessions = homeSessionListSessions(projects, sections)

        val chrome = homeChromeProjects(projects, sessions).single()

        assertEquals(SessionListStatus.Running, sessions.first { it.id == "wt" }.status)
        assertEquals("proj", chrome.id)
        assertEquals("/repo", chrome.worktree)
        assertEquals(2, chrome.sessionCount)
        assertEquals(listOf("/repo", "/repo-wt"), chrome.worktrees.map { it.directory })
        assertEquals(listOf(1, 1), chrome.worktrees.map { it.sessionCount })
        assertEquals(listOf(0, 1), chrome.worktrees.map { it.runningCount })
    }

    @Test
    fun projectCounts_followTheWinningWorktree() {
        val sections =
            listOf(
                section(
                    worktree = "/earlier",
                    session(id = "shared", title = "older", updatedAtEpochMs = 10L),
                    session(id = "local", title = "stays", updatedAtEpochMs = 5L),
                ),
                section(worktree = "/later", session(id = "shared", title = "newer", updatedAtEpochMs = 20L)),
                section(worktree = "/empty-after-dedupe", session(id = "shared", title = "oldest", updatedAtEpochMs = 1L)),
            )
        val projects = HomeProjectGrouping.group(sections.map { it.worktree }, emptyMap())
        val counts =
            homeChromeProjects(projects, sections.toCrossProjectSessionListSessions())
                .associate { it.worktree to it.sessionCount }

        assertEquals(1, counts["/earlier"])
        assertEquals(1, counts["/later"])
        assertEquals(0, counts["/empty-after-dedupe"])
    }

    private fun mergedProjects(branches: Map<String, String> = emptyMap()) =
        HomeProjectGrouping.group(
            addedPaths = listOf("/repo", "/repo-wt"),
            resolved =
                mapOf(
                    "/repo" to ResolvedHomePath("proj", "/repo", isRepository = true),
                    "/repo-wt" to ResolvedHomePath("proj", "/repo", isRepository = true),
                ),
            branches = branches,
        )

    private fun section(
        worktree: String,
        vararg sessions: SessionSummary,
    ): ProjectSectionUi =
        ProjectSectionUi(
            worktree = worktree,
            displayName = worktree,
            pathSubtitle = worktree,
            letter = 'P',
            letterAccent = LetterAccent.Violet,
            allSessions = sessions.toList(),
        )

    private fun session(
        id: String,
        title: String,
        updatedAtEpochMs: Long?,
        directory: String? = null,
        statusLabel: String? = null,
    ): SessionSummary =
        SessionSummary(
            id = id,
            title = title,
            updatedAtEpochMs = updatedAtEpochMs,
            directory = directory,
            statusLabel = statusLabel,
        )
}
