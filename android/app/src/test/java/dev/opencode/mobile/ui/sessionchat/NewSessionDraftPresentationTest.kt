package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.session.NewSessionDraft
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionDraftPresentationTest {
    private val repo =
        HomeProject(
            key = "proj",
            projectId = "proj",
            displayName = "opencode-mobile",
            memberPaths = listOf("/repo"),
            isRepository = true,
            worktrees =
                listOf(
                    HomeWorktree("/repo", branch = "main", isMain = true),
                    HomeWorktree("/wt/busy", branch = "feature/busy"),
                    HomeWorktree("/wt/detached"),
                ),
        )
    private val plain =
        HomeProject(
            key = "/plain",
            projectId = null,
            displayName = "notes",
            memberPaths = listOf("/plain"),
            isRepository = false,
            worktrees = listOf(HomeWorktree("/plain", isMain = true)),
        )

    private fun draft(
        project: HomeProject = repo,
        place: NewSessionPlace = NewSessionPlace.Local,
    ) = NewSessionDraft.from(NewSessionDraftStart(project.key, project.mainCheckoutDirectory, place))

    private fun ui(
        draft: NewSessionDraft = draft(),
        worktreesEnabled: Boolean = true,
        serverCanCreate: Boolean = true,
        protocol: ServerProtocol = ServerProtocol.V2,
        sheet: NewSessionDraftSheet? = null,
        query: String = "",
    ) = NewSessionDraftPresentation.ui(
        draft = draft,
        projects = listOf(repo, plain),
        worktreesEnabled = worktreesEnabled,
        serverCanCreate = serverCanCreate,
        protocol = protocol,
        runningCounts = mapOf("/wt/busy" to 2),
        sheet = sheet,
        projectQuery = query,
    )

    @Test
    fun worktreeSheet_listsLocalNewWorktreeAndTheProjectsWorktrees() {
        val state = ui(sheet = NewSessionDraftSheet.Worktree)

        assertEquals("Run in opencode-mobile", state.worktreeSheetTitle.english())
        val local = state.placeOptions[0] as NewSessionWorktreeOption.Local
        assertEquals("main · current checkout", local.subtitle.english())
        assertTrue(local.selected)
        assertEquals(NewSessionWorktreeOption.NewWorktree(selected = false), state.placeOptions[1])
        assertEquals(listOf("feature/busy", "detached"), state.worktreeOptions.map { it.title })
        // Running count where there is one, else the folder; never a total.
        assertEquals(listOf("2 running", "detached"), state.worktreeOptions.map { it.subtitle.english() })
    }

    @Test
    fun worktreeSheet_hidesNewWorktreeWhenTheServerCannotCreateOne() {
        val state = ui(serverCanCreate = false, sheet = NewSessionDraftSheet.Worktree)

        assertEquals(1, state.placeOptions.size)
        assertTrue(state.placeOptions.single() is NewSessionWorktreeOption.Local)
        // A New worktree chosen earlier falls back to Local.
        assertEquals(NewSessionWorktreeValue.Local, ui(draft(place = NewSessionPlace.NewWorktree()), serverCanCreate = false).worktree)
    }

    @Test
    fun notAGitRepository_keepsTheRowButDisablesIt() {
        val state = ui(draft(plain))

        assertEquals(NewSessionWorktreeValue.NotRepository, state.worktree)
        assertFalse(state.worktreeEnabled)
        assertEquals(UiText.verbatim("/plain"), state.path)
    }

    @Test
    fun worktreesOff_hasNoWorktreeRowAndRunsOnTheMainCheckout() {
        val state = ui(draft(place = NewSessionPlace.Worktree("/wt/busy", "feature/busy")), worktreesEnabled = false)

        assertNull(state.worktree)
        assertFalse(state.worktreeEnabled)
        assertEquals(UiText.verbatim("/repo"), state.path)
        assertEquals(
            NewSessionPlace.Local,
            NewSessionDraftPresentation.effectivePlace(
                draft(place = NewSessionPlace.Worktree("/wt/busy", "feature/busy")),
                repo,
                worktreesEnabled = false,
                serverCanCreate = true,
            ),
        )
    }

    @Test
    fun card_showsTheChosenPlaceAndHighlightsAnythingButLocal() {
        val worktree = ui(draft(place = NewSessionPlace.Worktree("/wt/busy", "feature/busy")))
        assertEquals(NewSessionWorktreeValue.Worktree("feature/busy"), worktree.worktree)
        assertTrue(worktree.worktreeHighlighted)
        assertEquals(UiText.verbatim("/wt/busy"), worktree.path)

        val newWorktree = ui(draft(place = NewSessionPlace.NewWorktree()))
        assertEquals(NewSessionWorktreeValue.NewWorktree, newWorktree.worktree)
        assertTrue(newWorktree.worktreeHighlighted)
        assertEquals("Created when you send", newWorktree.path?.english())

        assertFalse(ui().worktreeHighlighted)
    }

    @Test
    fun startsFrom_onlyForANewWorktreeOnV2_defaultingToTheMainBranch() {
        assertEquals(UiText.verbatim("main"), ui(draft(place = NewSessionPlace.NewWorktree())).startsFrom)
        assertEquals(UiText.verbatim("release"), ui(draft(place = NewSessionPlace.NewWorktree("release"))).startsFrom)
        assertNull(ui(draft(place = NewSessionPlace.NewWorktree()), protocol = ServerProtocol.V1).startsFrom)
        assertNull(ui().startsFrom)
    }

    @Test
    fun projectSheet_filtersByNameInHomeOrderAndMarksTheDraftsProject() {
        val all = ui(sheet = NewSessionDraftSheet.Project).projectRows
        assertEquals(listOf("opencode-mobile" to true, "notes" to false), all.map { it.name to it.selected })
        assertEquals("/repo", all.first().path)

        assertEquals(listOf("notes"), ui(sheet = NewSessionDraftSheet.Project, query = " NOT ").projectRows.map { it.name })
        assertEquals(emptyList<String>(), ui(sheet = NewSessionDraftSheet.Project, query = "zzz").projectRows.map { it.name })
    }

    @Test
    fun baseRows_defaultFirstThenOtherBranches() {
        val rows =
            NewSessionDraftPresentation.baseRows(
                mainBranch = "main",
                selected = "release",
                options =
                    dev.opencode.mobile.ui.worktree.WorktreeBaseOptions
                        .Loaded(listOf("main", "release")),
            )
        assertEquals(listOf(null, "release"), rows.map { it.ref })
        assertEquals(listOf(false, true), rows.map { it.checked })
    }
}
