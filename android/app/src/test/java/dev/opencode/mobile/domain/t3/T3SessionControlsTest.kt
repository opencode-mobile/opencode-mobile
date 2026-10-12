package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.SessionCreateOptions
import dev.opencode.mobile.domain.session.SessionError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Thread lifecycle and settings commands the T3 session repository dispatches over the socket. */
@OptIn(ExperimentalCoroutinesApi::class)
class T3SessionControlsTest {
    private class Harness(
        scope: TestScope,
    ) {
        val http = FakeT3Http()
        val socket = FakeT3Socket()
        val events = RecordingEventStream()
        lateinit var runtime: T3Runtime
        lateinit var repository: T3SessionRepository
        private val testScope = scope

        suspend fun build(): Harness {
            val api = T3Api(http)
            runtime =
                T3Runtime(
                    api = api,
                    socket = socket,
                    activeConnectionManager = activeManager(FakeProfileStore(t3Profile)),
                    eventStream = events,
                    scope = testScope.backgroundScope,
                    shellRefreshDebounceMs = 10,
                )
            repository = T3SessionRepository(runtime, api)
            return this
        }

        fun dispatched(): List<JsonObject> = socket.requests.filter { it.tag == T3RpcCodec.DISPATCH_COMMAND }.map { it.payload }

        fun lastCommand(): JsonObject = dispatched().last()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // region fixtures

    private fun thread(
        id: String,
        activeRunId: String? = null,
        archivedAt: String? = null,
        settledAt: String? = null,
        settledOverride: String? = null,
        deletedAt: String? = null,
        runtimeMode: String = "approval-required",
        instanceId: String = "codex-1",
        model: String = "gpt-5",
        createdAt: String? = "2026-10-01T08:00:00Z",
        updatedAt: String? = "2026-10-03T12:00:00Z",
        snoozedUntil: String? = null,
        pendingRequestId: String? = null,
        pendingRequestKind: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", "Thread $id")
            put("projectId", "proj-a")
            put("status", "idle")
            put("runtimeMode", runtimeMode)
            put("providerInstanceId", instanceId)
            put(
                "modelSelection",
                buildJsonObject {
                    put("instanceId", instanceId)
                    put("model", model)
                },
            )
            if (activeRunId != null) put("activeRunId", activeRunId)
            if (archivedAt != null) put("archivedAt", archivedAt)
            if (settledAt != null) put("settledAt", settledAt)
            if (settledOverride != null) put("settledOverride", settledOverride)
            if (deletedAt != null) put("deletedAt", deletedAt)
            if (createdAt != null) put("createdAt", createdAt)
            if (updatedAt != null) put("updatedAt", updatedAt)
            if (snoozedUntil != null) put("snoozedUntil", snoozedUntil)
            if (pendingRequestId != null) {
                put(
                    "pendingRuntimeRequest",
                    buildJsonObject {
                        put("id", pendingRequestId)
                        if (pendingRequestKind != null) put("kind", pendingRequestKind)
                    },
                )
            }
        }

    private fun shell(
        threads: List<JsonObject>,
        archivedThreads: List<JsonObject> = emptyList(),
        sequence: Long = 1,
    ): String =
        T3Json.parser.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("snapshotSequence", sequence)
                put("threads", JsonArray(threads))
                put("archivedThreads", JsonArray(archivedThreads))
                put("projects", JsonArray(listOf(projectJson("proj-a", "/repos/a"))))
            },
        )

    // endregion

    // region parsing

    @Test
    fun shellSnapshot_parsesArchivedThreadsSeparately() {
        val snapshot =
            T3Json.shellSnapshot(
                shell(
                    threads = listOf(thread("thr-active")),
                    archivedThreads = listOf(thread("thr-archived", archivedAt = "2026-10-02T00:00:00Z")),
                ),
            )

        assertEquals(listOf("thr-active"), snapshot.threads.map { it.id })
        assertEquals(listOf("thr-archived"), snapshot.archivedThreads.map { it.id })
        assertEquals(listOf("thr-active", "thr-archived"), snapshot.allThreads.map { it.id })
        assertEquals("thr-archived", snapshot.thread("thr-archived")?.id)
        assertTrue(T3Activity.isArchivedListRow(snapshot.archivedThreads.single()))
        assertFalse(T3Activity.isActiveListRow(snapshot.archivedThreads.single()))
    }

    @Test
    fun shellSnapshot_allThreadsPrefersActiveCopyOnDuplicateId() {
        val snapshot =
            T3Json.shellSnapshot(
                shell(
                    threads = listOf(thread("thr-1")),
                    archivedThreads = listOf(thread("thr-1", archivedAt = "2026-10-02T00:00:00Z")),
                ),
            )

        assertEquals(1, snapshot.allThreads.size)
        assertNull(snapshot.allThreads.single().archivedAt)
    }

    @Test
    fun toSession_usesThreadCreatedAtAndSettledAt() {
        val created = T3Json.thread(thread("thr-1"))!!
        val session = T3SessionMapper.toSession(created)
        assertEquals(T3MessageMapper.epochMs("2026-10-01T08:00:00Z"), session.createdAtEpochMs)
        assertEquals(T3MessageMapper.epochMs("2026-10-03T12:00:00Z"), session.updatedAtEpochMs)
        assertNull(session.settledAtEpochMs)
        assertFalse(session.isSettled)

        val withoutCreated = T3SessionMapper.toSession(T3Json.thread(thread("thr-2", createdAt = null))!!)
        assertNull(withoutCreated.createdAtEpochMs)

        val settled = T3SessionMapper.toSession(T3Json.thread(thread("thr-3", settledAt = "2026-10-02T00:00:00Z"))!!)
        assertTrue(settled.isSettled)
        assertNull(settled.archivedAtEpochMs)

        val overridden =
            T3SessionMapper.toSession(
                T3Json.thread(thread("thr-4", settledAt = "2026-10-02T00:00:00Z", settledOverride = "active"))!!,
            )
        assertFalse(overridden.isSettled)
    }

    @Test
    fun toSession_mapsSnoozeAndBlockReason() {
        val snoozed = T3SessionMapper.toSession(T3Json.thread(thread("thr-s", snoozedUntil = "2099-01-01T00:00:00Z"))!!)
        assertEquals(T3MessageMapper.epochMs("2099-01-01T00:00:00Z"), snoozed.snoozedUntilEpochMs)
        assertNull(snoozed.snoozeBlockReason)

        val running =
            T3SessionMapper.toSession(
                T3Json.thread(
                    thread(
                        "thr-r",
                        activeRunId = "run-1",
                        snoozedUntil = "2099-01-01T00:00:00Z",
                        pendingRequestId = "req",
                    ),
                )!!,
            )
        assertEquals("Still running", running.snoozeBlockReason)

        val waiting =
            T3SessionMapper.toSession(
                T3Json.thread(thread("thr-w", pendingRequestId = "req", pendingRequestKind = "user_input"))!!,
            )
        assertEquals("Waiting on you", waiting.snoozeBlockReason)
    }

    @Test
    fun providerLabels_titleCaseDriverAndDropInstanceSuffix() {
        assertEquals("Codex", T3ProviderLabels.displayName("codex-1"))
        assertEquals("Codex", T3ProviderLabels.displayName("codex"))
        assertEquals("Claude Code", T3ProviderLabels.displayName("claude-code"))
        assertEquals("Claude Code", T3ProviderLabels.displayName("claude-code-2"))
        assertEquals("Cursor Agent", T3ProviderLabels.displayName("cursor_agent"))
        assertNull(T3ProviderLabels.displayName(null))
        assertNull(T3ProviderLabels.displayName("  "))
        assertEquals("Codex · gpt-5", T3ProviderLabels.modelSelectionLabel("codex-1", "gpt-5"))
        assertEquals("Codex", T3ProviderLabels.modelSelectionLabel("codex-1", null))
        assertEquals("gpt-5", T3ProviderLabels.modelSelectionLabel(null, "gpt-5"))
    }

    @Test
    fun runtimeModes_matchContractValues() {
        assertEquals(listOf("approval-required", "auto-accept-edits", "auto", "full-access"), T3RuntimeModeLabels.allModes)
        assertEquals(listOf("Supervised", "Auto-accept edits", "Auto", "Full access"), T3RuntimeModeLabels.allModes.map(T3RuntimeModeLabels::displayName))
    }

    // endregion

    // region repository commands

    @Test
    fun listArchivedSessions_returnsArchivedAndSettledThreadsOnly() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson =
                shell(
                    threads =
                        listOf(
                            thread("thr-active"),
                            thread("thr-settled", settledAt = "2026-10-02T00:00:00Z"),
                            thread("thr-reactivated", settledAt = "2026-10-02T00:00:00Z", settledOverride = "active"),
                            thread("thr-deleted", archivedAt = "2026-10-02T00:00:00Z", deletedAt = "2026-10-02T01:00:00Z"),
                        ),
                    archivedThreads = listOf(thread("thr-archived", archivedAt = "2026-10-02T00:00:00Z")),
                )

            val active = h.repository.listSessions(directory = "/repos/a")
            val archived = h.repository.listArchivedSessions(directory = "/repos/a")

            assertEquals(listOf("thr-active", "thr-reactivated"), active.map { it.id })
            assertEquals(listOf("thr-settled", "thr-archived"), archived.map { it.id })
            assertTrue(archived.first { it.id == "thr-settled" }.isSettled)
            assertTrue(archived.first { it.id == "thr-archived" }.isArchived)
            assertEquals(emptyList<String>(), h.repository.listArchivedSessions(directory = "/elsewhere").map { it.id })
        }

    @Test
    fun getSession_findsArchivedThread() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = emptyList(), archivedThreads = listOf(thread("thr-archived", archivedAt = "2026-10-02T00:00:00Z")))

            val session = h.repository.getSession("thr-archived", directory = null)

            assertEquals("thr-archived", session.id)
            assertTrue(session.isArchived)
            assertEquals("/repos/a", h.repository.getSession("thr-archived", directory = "/repos/a").directory)
        }

    @Test
    fun archiveSession_dispatchesThreadArchive() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1")))

            val session = h.repository.archiveSession("thr-1", directory = "/repos/a")

            val command = h.lastCommand()
            assertEquals("thread.archive", command.str("type"))
            assertEquals("thr-1", command.str("threadId"))
            assertTrue(command.str("commandId")!!.isNotBlank())
            assertEquals("thr-1", session.id)
            assertTrue(session.isArchived)
            assertEquals(listOf("thr-1"), h.runtime.shell.value!!.archivedThreads.map { it.id })
            assertEquals(emptyList<String>(), h.runtime.shell.value!!.threads.map { it.id })
        }

    @Test
    fun unarchiveSession_dispatchesThreadUnarchiveAndClearsArchivedAt() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = emptyList(), archivedThreads = listOf(thread("thr-1", archivedAt = "2026-10-02T00:00:00Z")))

            val session = h.repository.unarchiveSession("thr-1", directory = "/repos/a")

            val command = h.lastCommand()
            assertEquals("thread.unarchive", command.str("type"))
            assertEquals("thr-1", command.str("threadId"))
            assertFalse(session.isArchived)
        }

    @Test
    fun settleAndUnsettle_dispatchContractCommands() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1")))

            assertTrue(h.repository.settleSession("thr-1", directory = "/repos/a"))
            val settle = h.lastCommand()
            assertEquals("thread.settle", settle.str("type"))
            assertEquals("thr-1", settle.str("threadId"))
            assertNull(settle["reason"])

            assertTrue(h.repository.unsettleSession("thr-1", directory = "/repos/a"))
            val unsettle = h.lastCommand()
            assertEquals("thread.unsettle", unsettle.str("type"))
            assertEquals("thr-1", unsettle.str("threadId"))
            assertEquals("user", unsettle.str("reason"))
            assertEquals(2, h.dispatched().size)
        }

    @Test
    fun snoozeAndWake_dispatchContractCommands() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1")))

            assertTrue(h.repository.snoozeSession("thr-1", snoozedUntil = "2099-01-01T00:00:00Z", directory = "/repos/a"))
            val snooze = h.lastCommand()
            assertEquals("thread.snooze", snooze.str("type"))
            assertEquals("thr-1", snooze.str("threadId"))
            assertEquals("2099-01-01T00:00:00Z", snooze.str("snoozedUntil"))
            assertNull(snooze["reason"])

            assertTrue(h.repository.wakeSession("thr-1", directory = "/repos/a"))
            val wake = h.lastCommand()
            assertEquals("thread.unsnooze", wake.str("type"))
            assertEquals("thr-1", wake.str("threadId"))
            assertEquals("user", wake.str("reason"))
        }

    @Test
    fun deleteSession_dispatchesThreadDelete() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1")))

            assertTrue(h.repository.deleteSession("thr-1", directory = "/repos/a"))

            val command = h.lastCommand()
            assertEquals("thread.delete", command.str("type"))
            assertEquals("thr-1", command.str("threadId"))
        }

    @Test
    fun updateSession_dispatchesMetadataUpdateAndReturnsTitle() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1")))

            val session = h.repository.updateSession("thr-1", title = "Renamed", directory = "/repos/a")

            val command = h.lastCommand()
            assertEquals("thread.metadata.update", command.str("type"))
            assertEquals("thr-1", command.str("threadId"))
            assertEquals("Renamed", command.str("title"))
            assertEquals("Renamed", session.title)

            h.repository.updateSession("thr-1", title = "  ", directory = "/repos/a")
            assertEquals(1, h.dispatched().size)
        }

    @Test
    fun abortSession_interruptsActiveRunOnly() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-idle"), thread("thr-busy", activeRunId = "run-7")))

            assertTrue(h.repository.abortSession("thr-idle", directory = "/repos/a"))
            assertEquals(0, h.dispatched().size)

            assertTrue(h.repository.abortSession("thr-busy", directory = "/repos/a"))
            val command = h.lastCommand()
            assertEquals("run.interrupt", command.str("type"))
            assertEquals("thr-busy", command.str("threadId"))
            assertEquals("run-7", command.str("runId"))
        }

    @Test
    fun setRuntimeMode_dispatchesContractValueAndRejectsUnknown() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1", runtimeMode = "approval-required")))

            val session = h.repository.setRuntimeMode("thr-1", runtimeMode = "full-access", directory = "/repos/a")

            val command = h.lastCommand()
            assertEquals("thread.runtime-mode.set", command.str("type"))
            assertEquals("thr-1", command.str("threadId"))
            assertEquals("full-access", command.str("runtimeMode"))
            assertEquals("full-access", session.runtimeMode)

            try {
                h.repository.setRuntimeMode("thr-1", runtimeMode = "yolo", directory = "/repos/a")
                fail("unknown runtime mode must be rejected")
            } catch (error: SessionError.Http) {
                assertEquals(400, error.statusCode)
            }
            assertEquals(1, h.dispatched().size)
        }

    @Test
    fun setModelSelection_dispatchesInstanceAndModel() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shell(threads = listOf(thread("thr-1")))

            val session =
                h.repository.setModelSelection(
                    "thr-1",
                    providerInstanceId = "claude-code",
                    modelId = "claude-sonnet",
                    directory = "/repos/a",
                )

            val command = h.lastCommand()
            assertEquals("thread.model-selection.set", command.str("type"))
            assertEquals("thr-1", command.str("threadId"))
            val selection = command["modelSelection"]!!.jsonObject
            assertEquals("claude-code", selection["instanceId"]!!.jsonPrimitive.content)
            assertEquals("claude-sonnet", selection["model"]!!.jsonPrimitive.content)
            assertEquals("claude-code", session.providerInstanceId)
            assertEquals("claude-sonnet", session.modelId)

            try {
                h.repository.setModelSelection("thr-1", providerInstanceId = "", modelId = "x", directory = "/repos/a")
                fail("blank instance must be rejected")
            } catch (error: SessionError.Http) {
                assertEquals(400, error.statusCode)
            }
            assertEquals(1, h.dispatched().size)
        }

    @Test
    fun createSession_launchesWithSupervisedRuntimeModeDefault() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson =
                T3Json.parser.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("snapshotSequence", 1)
                        put("threads", JsonArray(emptyList()))
                        put("archivedThreads", JsonArray(emptyList()))
                        put(
                            "projects",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("id", "proj-a")
                                        put("title", "A")
                                        put("workspaceRoot", "/repos/a")
                                        put(
                                            "defaultModelSelection",
                                            buildJsonObject {
                                                put("instanceId", "codex-1")
                                                put("model", "gpt-5")
                                            },
                                        )
                                    },
                                ),
                            ),
                        )
                    },
                )
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] = serverConfigJson(listOf(harnessJson("codex-1")))

            runCatching { h.repository.createSession(parentId = null, title = "New", directory = "/repos/a") }

            val launch = h.socket.requests.last { it.tag == T3RpcCodec.LAUNCH_THREAD }.payload
            assertEquals("approval-required", launch.str("runtimeMode"))
            assertEquals("codex-1", launch["modelSelection"]!!.jsonObject["instanceId"]!!.jsonPrimitive.content)
        }

    /** A shell whose only project has no default model selection (nullable by contract). */
    private fun shellWithoutProjectDefault(): String = shell(threads = emptyList())

    private fun launchPayload(h: Harness): JsonObject =
        h.socket.requests
            .last { it.tag == T3RpcCodec.LAUNCH_THREAD }
            .payload

    private fun launchSelection(h: Harness): Pair<String?, String?> {
        val selection = launchPayload(h)["modelSelection"]!!.jsonObject
        return selection.str("instanceId") to selection.str("model")
    }

    @Test
    fun createSession_withoutProjectDefault_fallsBackToGlobalDefault() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellWithoutProjectDefault()
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] =
                serverConfigJson(
                    providers = listOf(harnessJson("codex-1"), harnessJson("claude-1")),
                    defaultInstanceId = "claude-1",
                    defaultModel = "opus",
                )

            runCatching { h.repository.createSession(parentId = null, title = "New", directory = "/repos/a") }

            assertEquals("claude-1" to "opus", launchSelection(h))
        }

    @Test
    fun createSession_withoutAnyDefault_usesFirstUsableHarnessDefaultModel() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellWithoutProjectDefault()
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] =
                serverConfigJson(
                    providers =
                        listOf(
                            harnessJson("codex-1", enabled = false),
                            harnessJson(
                                "claude-1",
                                models = listOf(harnessModelJson("sonnet"), harnessModelJson("opus", isDefault = true)),
                            ),
                        ),
                )

            runCatching { h.repository.createSession(parentId = null, title = "New", directory = "/repos/a") }

            assertEquals("claude-1" to "opus", launchSelection(h))
        }

    @Test
    fun createSession_passesExplicitSelectionAndRuntimeModeIntoLaunch() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellWithoutProjectDefault()
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] =
                serverConfigJson(
                    providers =
                        listOf(
                            harnessJson("codex-1"),
                            harnessJson("claude-1", models = listOf(harnessModelJson("sonnet"), harnessModelJson("opus"))),
                        ),
                    defaultInstanceId = "codex-1",
                )

            runCatching {
                h.repository.createSession(
                    parentId = null,
                    title = "New",
                    directory = "/repos/a",
                    options =
                        SessionCreateOptions(
                            providerId = "claude-1",
                            modelId = "opus",
                            runtimeMode = "full-access",
                        ),
                )
            }

            assertEquals("claude-1" to "opus", launchSelection(h))
            assertEquals("full-access", launchPayload(h).str("runtimeMode"))
        }

    @Test
    fun createSession_runtimeModeOutsideHarnessSupportFallsBackToSupervised() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellWithoutProjectDefault()
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] =
                serverConfigJson(
                    listOf(harnessJson("codex-1", supportedRuntimeModes = listOf("approval-required", "auto"))),
                )

            runCatching {
                h.repository.createSession(
                    parentId = null,
                    title = "New",
                    directory = "/repos/a",
                    options = SessionCreateOptions(runtimeMode = "full-access"),
                )
            }

            assertEquals("approval-required", launchPayload(h).str("runtimeMode"))
        }

    @Test
    fun createSession_withoutUsableHarness_failsWithConfigureMessageAndNoLaunch() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellWithoutProjectDefault()
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] =
                serverConfigJson(listOf(harnessJson("codex-1", authStatus = "unauthenticated")))

            try {
                h.repository.createSession(parentId = null, title = "New", directory = "/repos/a")
                fail("creation must fail without a usable harness")
            } catch (error: SessionError.Http) {
                assertEquals("No T3 harness is available. Configure a provider in T3 Code first.", error.message)
            }
            assertTrue(h.socket.requests.none { it.tag == T3RpcCodec.LAUNCH_THREAD })
        }

    @Test
    fun serverConfig_isCachedPerProfileAndRefreshedOnRequest() =
        runTest {
            val h = Harness(this).build()
            h.socket.connect(t3Profile.baseUrl)
            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] = serverConfigJson(listOf(harnessJson("codex-1")))

            val first = h.runtime.serverConfig()
            h.runtime.serverConfig()
            assertEquals(1, h.socket.requests.count { it.tag == T3RpcCodec.SERVER_GET_CONFIG })
            assertEquals(listOf("codex-1"), first.harnesses.map { it.instanceId })

            h.socket.responses[T3RpcCodec.SERVER_GET_CONFIG] = serverConfigJson(listOf(harnessJson("claude-1")))
            val refreshed = h.runtime.serverConfig(forceRefresh = true)
            assertEquals(2, h.socket.requests.count { it.tag == T3RpcCodec.SERVER_GET_CONFIG })
            assertEquals(listOf("claude-1"), refreshed.harnesses.map { it.instanceId })
            assertEquals(refreshed, h.runtime.cachedServerConfig())

            h.runtime.stop()
            assertNull(h.runtime.cachedServerConfig())
        }

    // endregion
}
