package dev.opencode.hexcode.session

import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.DIR
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.GLOBAL_FILE
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.configOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state, `artifact-rules`, the tile and the hint (artifacts-proposal.md §2.2, §2.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactsFeatureTest {
    private val api = FakeArtifactsApi()
    private val store = MemoryArtifactsStore()

    private fun TestScope.feature() = ArtifactsFeature(backgroundScope, api, store, { DIR }, { null })

    private fun info(
        id: String,
        session: String,
        updated: Long,
    ) = ArtifactInfo(id, "local", session, "Page $id", "index.html", "/api/experimental/artifacts/$id/view/", updated, updated)

    @Test
    fun onPutsTheRuleOnTodaysSession() =
        runTest {
            api.list = ArtifactListAnswer.On(emptyList())
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            assertEquals(ArtifactsState.On, feature.state.value.state)
            assertEquals(listOf("put ses_today"), api.ruleCalls)
        }

    @Test
    fun offRemovesTheRule() =
        runTest {
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            assertEquals(ArtifactsState.Off, feature.state.value.state)
            assertEquals(listOf("delete ses_today"), api.ruleCalls)
        }

    @Test
    fun theRuleIsPutOnceAndRemovedAsSoonAsItsOff() =
        runTest {
            api.list = ArtifactListAnswer.On(emptyList())
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            feature.check()
            feature.tile("ses_today", emptySet())
            assertEquals(listOf("put ses_today"), api.ruleCalls)
            api.list = ArtifactListAnswer.Off
            feature.tile("ses_today", emptySet())
            assertEquals(listOf("put ses_today", "delete ses_today"), api.ruleCalls)
        }

    @Test
    fun undeclaredRoutesSwitchTheFeatureOffQuietly() =
        runTest {
            api.declared = false
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            assertEquals(ArtifactsState.Unavailable, feature.state.value.state)
            assertEquals(0, api.listCalls)
            assertEquals(ArtifactRead.Set(null), feature.tile("ses_today", emptySet()))
        }

    @Test
    fun aChangedAnswerStaysForTheConnection() =
        runTest {
            api.list = ArtifactListAnswer.Changed
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            assertEquals(ArtifactsState.Unavailable, feature.state.value.state)
            api.list = ArtifactListAnswer.On(emptyList())
            feature.check()
            assertEquals(ArtifactsState.Unavailable, feature.state.value.state)
            assertEquals(1, api.declaredReads)
        }

    @Test
    fun theOpenAPIIsReadOncePerConnection() =
        runTest {
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            feature.check()
            feature.check()
            assertEquals(1, api.declaredReads)
            feature.closed()
            feature.opened("ses_today")
            runCurrent()
            assertEquals(2, api.declaredReads)
        }

    @Test
    fun aTimeoutKeepsTheLastStateAndTheTile() =
        runTest {
            api.list = ArtifactListAnswer.On(emptyList())
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            api.list = ArtifactListAnswer.Unknown
            assertEquals(ArtifactRead.Keep, feature.tile("ses_today", emptySet()))
            assertEquals(ArtifactsState.On, feature.state.value.state)
        }

    @Test
    fun theTileIsTheNewestOfTheLatestRun() =
        runTest {
            api.runStart = 1_000
            api.list =
                ArtifactListAnswer.On(
                    listOf(info("art_new", "ses_child", 1_500), info("art_old", "ses_today", 900), info("art_mid", "ses_today", 1_200)),
                )
            val feature = feature()
            assertEquals(ArtifactRead.Set(ArtifactTile("art_new", "Page art_new")), feature.tile("ses_today", setOf("ses_child")))
            assertEquals(ArtifactRead.Set(ArtifactTile("art_mid", "Page art_mid")), feature.tile("ses_today", emptySet()))
        }

    @Test
    fun offMeansNoTileAndNoError() =
        runTest {
            val feature = feature()
            assertEquals(ArtifactRead.Set(null), feature.tile("ses_today", emptySet()))
        }

    @Test
    fun aConfigChangeIsCheckedASecondLater() =
        runTest {
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            val calls = api.listCalls
            api.list = ArtifactListAnswer.On(emptyList())
            feature.line("""{"type":"config.updated","data":{}}""")
            advanceTimeBy(900)
            assertEquals(calls, api.listCalls)
            advanceTimeBy(200)
            assertEquals(ArtifactsState.On, feature.state.value.state)
            assertTrue("put ses_today" in api.ruleCalls)
        }

    private fun called(
        session: String,
        tool: String,
        path: String,
    ) = listOf(
        """{"type":"session.tool.input.started","data":{"sessionID":"$session","assistantMessageID":"m","id":"c1","name":"$tool"}}""",
        """{"type":"session.tool.called","data":{"sessionID":"$session","assistantMessageID":"m","id":"c1","input":{"path":"$path"},"executed":true}}""",
    )

    @Test
    fun theHintShowsOnceAfterAnHtmlWriteWhileOff() =
        runTest {
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            called("ses_today", "write", "preview/login.html").forEach(feature::line)
            runCurrent()
            assertTrue(feature.state.value.hint)
            assertTrue(store.seen)
            feature.hintDone()
            called("ses_today", "write", "other.html").forEach(feature::line)
            runCurrent()
            assertFalse(feature.state.value.hint)
        }

    @Test
    fun theHintNeverShowsWhileOnOrForOtherFilesOrSessions() =
        runTest {
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            called("ses_today", "write", "notes.md").forEach(feature::line)
            called("ses_today", "read", "page.html").forEach(feature::line)
            called("ses_setup1", "write", "page.html").forEach(feature::line)
            runCurrent()
            assertFalse(feature.state.value.hint)
            api.list = ArtifactListAnswer.On(emptyList())
            feature.check()
            called("ses_today", "write", "page.html").forEach(feature::line)
            runCurrent()
            assertFalse(feature.state.value.hint)
            assertFalse(store.seen)
        }

    @Test
    fun theSetupTurningItOnPutsTheRule() =
        runTest {
            api.config = configOf(GLOBAL_FILE to """{"experimental":{}}""")
            api.files[GLOBAL_FILE] = "{}".toByteArray()
            val feature = feature()
            feature.opened("ses_today")
            runCurrent()
            feature.setup.start(SetupMode.On)
            runCurrent()
            feature.setup.send()
            runCurrent()
            api.config = configOf(GLOBAL_FILE to """{"experimental":{"artifacts":true}}""")
            api.list = ArtifactListAnswer.On(emptyList())
            advanceTimeBy(2_001)
            assertEquals(ArtifactsState.On, feature.state.value.state)
            assertEquals(
                SetupStage.Done,
                feature.state.value.setup
                    ?.stage,
            )
            assertEquals(listOf("delete ses_today", "put ses_today"), api.ruleCalls)
        }
}
