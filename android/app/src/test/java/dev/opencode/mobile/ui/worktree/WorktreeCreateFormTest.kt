package dev.opencode.mobile.ui.worktree

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.CreatedWorktree
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class WorktreeCreateFormTest {
    @Test
    fun randomNameIsAnAdjectiveAndANounFromTheSharedLists() {
        assertEquals("brave-otter", WorktreeName.random(FixedRandom(0, 0)))
        assertEquals("zesty-willow", WorktreeName.random(FixedRandom(15, 15)))
        assertEquals("nimble-lynx", WorktreeName.random(FixedRandom(9, 8)))
    }

    @Test
    fun nameValidationExplainsEmptyCharactersAndLength() {
        assertEquals("Enter a name", WorktreeName.validate("  "))
        assertEquals("Use letters, numbers, dots, dashes or underscores", WorktreeName.validate("my tree"))
        assertEquals("Use letters, numbers, dots, dashes or underscores", WorktreeName.validate("-lead"))
        assertEquals("Use letters, numbers, dots, dashes or underscores", WorktreeName.validate("a/b"))
        assertEquals("Use at most 64 characters", WorktreeName.validate("a".repeat(65)))
        assertNull(WorktreeName.validate("a".repeat(64)))
        assertNull(WorktreeName.validate(" fix_tax.rounding-2 "))
    }

    @Test
    fun openPrefillsTheNameAndShowsV1BranchAndBaseReadOnly() =
        runTest {
            val repository = FakeRepository()
            val form = form(repository, FixedRandom(1, 2))

            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V1, "proj", "/repo", sourceBranch = "dev")
            advanceUntilIdle()

            val state = form.state.value!!
            assertEquals("calm-maple", state.name)
            assertNull(state.nameError)
            assertEquals("opencode/calm-maple", state.branchValue)
            assertEquals("opencode v1 names it after the worktree", state.branchNote)
            assertEquals("dev", state.baseValue)
            assertEquals("opencode v1 uses the current checkout", state.baseNote)
            assertFalse(state.baseSelectable)
            assertEquals("Create and start", state.submitLabel)
            assertTrue(repository.branchLists.isEmpty())

            form.updateName("")
            assertEquals("opencode/…", form.state.value!!.branchValue)
            form.showBasePicker()
            assertFalse(form.state.value!!.basePickerVisible)

            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V1, "proj", "/repo", sourceBranch = null)
            assertEquals("Current checkout", form.state.value!!.baseValue)
        }

    @Test
    fun v2ShowsADetachedCheckoutAndLoadsBranchesFromTheSource() =
        runTest {
            val repository = FakeRepository(branches = listOf("dev", "main", "feature/x"))
            val form = form(repository)

            form.open(WorktreeCreateOrigin.Move("ses-1"), ServerProtocol.V2, "proj", "/wt/a", sourceBranch = "dev")
            assertEquals(WorktreeBaseOptions.Loading, form.state.value!!.baseOptions)
            assertEquals("Loading branches…", form.state.value!!.baseStatus)
            advanceUntilIdle()

            val state = form.state.value!!
            assertEquals(listOf("/wt/a"), repository.branchLists)
            assertEquals("No branch (detached)", state.branchValue)
            assertEquals("opencode v2 checkouts have no branch", state.branchNote)
            assertEquals("dev (current)", state.baseValue)
            assertEquals("Tap to pick a branch", state.baseNote)
            assertTrue(state.baseSelectable)
            assertEquals("Create and move", state.submitLabel)
            assertNull(state.baseStatus)
            assertEquals(
                listOf(
                    WorktreeBaseRow(null, "dev (current)", checked = true),
                    WorktreeBaseRow("main", "main", checked = false),
                    WorktreeBaseRow("feature/x", "feature/x", checked = false),
                ),
                state.baseRows,
            )
            assertEquals(listOf("worktreeCreateBaseOption:", "worktreeCreateBaseOption:main"), state.baseRows.take(2).map { it.testTag })
        }

    @Test
    fun failedBranchListStillOffersTheDefault() =
        runTest {
            val form = form(FakeRepository(branchError = IllegalStateException("down")))

            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V2, "proj", "/repo", sourceBranch = null)
            advanceUntilIdle()

            val state = form.state.value!!
            assertEquals(WorktreeBaseOptions.Failed, state.baseOptions)
            assertEquals("Couldn't load branches", state.baseStatus)
            assertEquals(listOf(WorktreeBaseRow(null, "Current checkout", checked = true)), state.baseRows)
        }

    @Test
    fun pickingABaseSendsItAndPickingTheSourceBranchIsTheDefault() =
        runTest {
            val repository = FakeRepository(branches = listOf("dev", "main"))
            val form = form(repository)
            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V2, "proj", "/repo", sourceBranch = "dev")
            advanceUntilIdle()

            form.showBasePicker()
            assertTrue(form.state.value!!.basePickerVisible)
            form.selectBase("main")
            val picked = form.state.value!!
            assertFalse(picked.basePickerVisible)
            assertEquals("main", picked.baseValue)
            assertEquals("main", picked.request.baseRef)
            assertEquals(listOf(false, true), picked.baseRows.map { it.checked })

            form.selectBase("dev")
            val source = form.state.value!!
            assertNull(source.selectedBaseRef)
            assertEquals("dev (current)", source.baseValue)
            form.selectBase("main")
            form.selectBase(null)
            assertNull(form.state.value!!.selectedBaseRef)
        }

    @Test
    fun invalidNameShowsItsErrorOnlyAfterAnEditOrASubmit() =
        runTest {
            val repository = FakeRepository()
            val form = form(repository)
            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V1, "proj", "/repo", sourceBranch = null)

            form.updateName("bad name")
            assertEquals("Use letters, numbers, dots, dashes or underscores", form.state.value!!.nameError)
            assertFalse(form.state.value!!.canSubmit)

            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V1, "proj", "/repo", sourceBranch = null)
            assertNull(form.state.value!!.nameError)
            form.updateName("ok")
            form.updateName(" ")
            form.submit()
            advanceUntilIdle()
            assertEquals("Enter a name", form.state.value!!.nameError)
            assertTrue(repository.requests.isEmpty())
        }

    @Test
    fun submitDisablesWhileCreatingThenHandsOverTheCheckout() =
        runTest {
            val gate = CompletableDeferred<CreatedWorktree>()
            val repository = FakeRepository(createAnswer = { gate.await() })
            val created = mutableListOf<Pair<WorktreeCreateOrigin, CreatedWorktree>>()
            val changed = mutableListOf<CreateWorktreeRequest>()
            val form =
                WorktreeCreateForm(
                    repository = repository,
                    scope = this,
                    onCreated = { origin, worktree -> created += origin to worktree },
                    onInventoryChanged = { _, request -> changed += request },
                    random = FixedRandom(0, 0),
                )
            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V2, "proj", "/repo", sourceBranch = "dev")
            advanceUntilIdle()
            form.updateName("  tax-fix ")

            form.submit()
            runCurrent()
            val submitting = form.state.value!!
            assertTrue(submitting.submitting)
            assertFalse(submitting.canSubmit)
            assertEquals("Creating…", submitting.submitLabel)
            form.submit()
            runCurrent()
            assertEquals(listOf(CreateWorktreeRequest("proj", "/repo", "tax-fix", baseRef = null)), repository.requests)

            val worktree = CreatedWorktree("/wt/tax-fix", "tax-fix", branch = null)
            gate.complete(worktree)
            advanceUntilIdle()

            assertNull(form.state.value)
            assertEquals(listOf(WorktreeCreateOrigin.RunIn("proj") to worktree), created)
            assertEquals(repository.requests, changed)
        }

    @Test
    fun failedCreateKeepsTheValuesAndSaysWhy() =
        runTest {
            val repository = FakeRepository(createAnswer = { throw WorktreeCreateError("fatal: invalid reference: nope") })
            val created = mutableListOf<CreatedWorktree>()
            val form =
                WorktreeCreateForm(
                    repository = repository,
                    scope = this,
                    onCreated = { _, worktree -> created += worktree },
                    random = FixedRandom(0, 0),
                )
            form.open(WorktreeCreateOrigin.Move("ses-1"), ServerProtocol.V2, "proj", "/repo", sourceBranch = "dev")
            advanceUntilIdle()
            form.updateName("tax-fix")
            form.selectBase("nope")

            form.submit()
            advanceUntilIdle()

            val state = form.state.value!!
            assertEquals("Couldn't create the worktree: fatal: invalid reference: nope", state.errorMessage)
            assertFalse(state.submitting)
            assertTrue(state.canSubmit)
            assertEquals("tax-fix", state.name)
            assertEquals("nope", state.selectedBaseRef)
            assertTrue(created.isEmpty())
        }

    @Test
    fun aCreateFinishingAfterBackDoesNotStartOrMove() =
        runTest {
            val gate = CompletableDeferred<CreatedWorktree>()
            val created = mutableListOf<CreatedWorktree>()
            val changed = mutableListOf<CreateWorktreeRequest>()
            val form =
                WorktreeCreateForm(
                    repository = FakeRepository(createAnswer = { gate.await() }),
                    scope = this,
                    onCreated = { _, worktree -> created += worktree },
                    onInventoryChanged = { _, request -> changed += request },
                )
            form.open(WorktreeCreateOrigin.RunIn("proj"), ServerProtocol.V1, "proj", "/repo", sourceBranch = null)
            form.submit()
            runCurrent()
            form.dismiss()

            gate.complete(CreatedWorktree("/wt/a", "a", "opencode/a"))
            advanceUntilIdle()

            assertNull(form.state.value)
            assertTrue(created.isEmpty())
            assertEquals(1, changed.size)
        }

    private fun TestScope.form(
        repository: WorktreeRepository,
        random: Random = FixedRandom(0, 0),
    ) = WorktreeCreateForm(repository = repository, scope = this, onCreated = { _, _ -> }, random = random)

    /** Answers [values] in turn, then starts over. */
    private class FixedRandom(
        private vararg val values: Int,
    ) : Random() {
        private var next = 0

        override fun nextBits(bitCount: Int): Int = 0

        override fun nextInt(until: Int): Int = values[next++ % values.size]
    }

    private class FakeRepository(
        private val branches: List<String> = emptyList(),
        private val branchError: Exception? = null,
        private val createAnswer: suspend (CreateWorktreeRequest) -> CreatedWorktree = { CreatedWorktree("/wt/" + it.name, it.name, null) },
    ) : WorktreeRepository by UnavailableWorktreeRepository {
        val branchLists = mutableListOf<String>()
        val requests = mutableListOf<CreateWorktreeRequest>()

        override suspend fun baseBranches(directory: String): List<String> {
            branchLists += directory
            branchError?.let { throw it }
            return branches
        }

        override suspend fun create(request: CreateWorktreeRequest): CreatedWorktree {
            requests += request
            return createAnswer(request)
        }
    }
}
