package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerProtocolDetectorTest {
    private val detector = ServerProtocolDetector(ProjectDomainAdapter())
    private val v1Health = HealthDto(healthy = true, version = "1.0.0")
    private val v2Health = HealthDto(healthy = true, version = "2.0.0", pid = 42)

    @Test
    fun savedV1_returnsOnGlobalHealthWithoutWaitingForTheOthers() =
        runTest {
            val detected =
                detector.detectServer(
                    fetchGlobalHealth = { v1Health },
                    fetchApiInfo = { awaitCancellation() },
                    fetchApiHealth = { awaitCancellation() },
                    expected = ServerProtocol.V1,
                )

            assertEquals(ServerProtocol.V1, detected.protocol)
        }

    @Test
    fun savedV2_returnsWithinTheGraceWhenGlobalHealthHangs() =
        runTest {
            val detected =
                detector.detectServer(
                    fetchGlobalHealth = { awaitCancellation() },
                    fetchApiInfo = { v2Health },
                    fetchApiHealth = { awaitCancellation() },
                    expected = ServerProtocol.V2,
                )

            assertEquals(ServerProtocol.V2, detected.protocol)
            assertEquals("2.0.0", detected.version)
            assertTrue(currentTime <= ServerProtocolDetector.SAVED_V2_GLOBAL_GRACE_MS)
        }

    @Test
    fun savedV2_serverNowV1_isStillDetected() =
        runTest {
            val detected =
                detector.detectServer(
                    fetchGlobalHealth = { v1Health },
                    fetchApiInfo = { throw IllegalStateException("no route") },
                    fetchApiHealth = { throw IllegalStateException("no route") },
                    expected = ServerProtocol.V2,
                )

            assertEquals(ServerProtocol.V1, detected.protocol)
        }

    @Test
    fun savedV1_serverNowV2_isStillDetected() =
        runTest {
            val detected =
                detector.detectServer(
                    fetchGlobalHealth = { throw IllegalStateException("no route") },
                    fetchApiInfo = { v2Health },
                    fetchApiHealth = { v2Health },
                    expected = ServerProtocol.V1,
                )

            assertEquals(ServerProtocol.V2, detected.protocol)
        }

    @Test
    fun savedV2_globalAnsweringInsideTheGrace_stillWins() =
        runTest {
            val global = CompletableDeferred<HealthDto>()
            val detection =
                async {
                    detector.detectServer(
                        fetchGlobalHealth = { global.await() },
                        fetchApiInfo = { v2Health },
                        fetchApiHealth = { v2Health },
                        expected = ServerProtocol.V2,
                    )
                }
            testScheduler.runCurrent()

            global.complete(v1Health)

            assertEquals(ServerProtocol.V1, detection.await().protocol)
        }
}
