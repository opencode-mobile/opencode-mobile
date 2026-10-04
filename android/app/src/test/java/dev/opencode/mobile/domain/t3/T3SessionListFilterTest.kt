package dev.opencode.mobile.domain.t3

import org.junit.Assert.assertEquals
import org.junit.Test

class T3SessionListFilterTest {
    @Test
    fun shellJson_listSessionsKeepsActiveThreadOnly() {
        val snapshot =
            T3Json.shellSnapshot(
                """
                {
                  "threads": [
                    {
                      "id": "thr-active",
                      "title": "Active",
                      "projectId": "prj-1",
                      "status": "idle",
                      "updatedAt": "2026-10-03T12:00:00Z"
                    },
                    {
                      "id": "thr-running",
                      "title": "Running",
                      "projectId": "prj-1",
                      "status": { "type": "busy" },
                      "activeRunId": "run-1",
                      "activityRunStatus": "running"
                    },
                    {
                      "id": "thr-settled",
                      "title": "Settled",
                      "projectId": "prj-1",
                      "status": "idle",
                      "settledAt": "2026-10-01T00:00:00Z"
                    },
                    {
                      "id": "thr-archived",
                      "title": "Archived",
                      "projectId": "prj-1",
                      "status": "idle",
                      "archivedAt": "2026-10-01T00:00:00Z"
                    },
                    {
                      "id": "thr-deleted",
                      "title": "Deleted",
                      "projectId": "prj-1",
                      "status": "idle",
                      "deletedAt": "2026-10-01T00:00:00Z"
                    },
                    {
                      "id": "thr-override",
                      "title": "Override",
                      "projectId": "prj-1",
                      "status": "idle",
                      "settledAt": "2026-10-01T00:00:00Z",
                      "settledOverride": "active"
                    }
                  ]
                }
                """.trimIndent(),
            )
        val listed =
            snapshot.threads
                .filter(T3Activity::isActiveListRow)
                .map { thread -> T3SessionMapper.toSession(thread) to T3SessionMapper.toStatus(thread) }
        assertEquals(listOf("thr-active", "thr-running", "thr-override"), listed.map { it.first.id })
        assertEquals("idle", listed[0].second.raw["type"])
        assertEquals("running", listed[1].second.raw["type"])
        assertEquals("idle", listed[2].second.raw["type"])
    }

    @Test
    fun pendingRuntimeRequest_becomesPermissionAndSkipsUserInput() {
        val snapshot =
            T3Json.shellSnapshot(
                """
                {
                  "threads": [
                    {
                      "id": "thr-ask",
                      "title": "Ask",
                      "projectId": "prj-1",
                      "status": "idle",
                      "pendingRuntimeRequest": {
                        "id": "rr-1",
                        "kind": "file-change",
                        "createdAt": "2026-10-03T12:00:00Z"
                      }
                    },
                    {
                      "id": "thr-question",
                      "title": "Question",
                      "projectId": "prj-1",
                      "status": "idle",
                      "pendingRuntimeRequest": {
                        "id": "rr-2",
                        "kind": "user_input",
                        "createdAt": "2026-10-03T12:00:00Z"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )
        val permissions = snapshot.threads.mapNotNull(T3SessionMapper::toPermission)
        assertEquals(1, permissions.size)
        assertEquals("rr-1", permissions[0].id)
        assertEquals("thr-ask", permissions[0].sessionId)
        assertEquals("file_change", permissions[0].permission)
        assertEquals(listOf("this session"), permissions[0].always)
    }
}
