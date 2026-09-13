package dev.opencode.mobile.domain.sync

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.LiveConnection
import dev.opencode.mobile.domain.event.WorldSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

fun testSyncCoordinator(
    eventStream: LiveConnection,
    worldSignals: WorldSignals = FakeWorldSignals(),
    scope: CoroutineScope,
    nowMs: () -> Long = { System.currentTimeMillis() },
    wait: suspend (Long) -> Unit = { delay(it) },
): DefaultSyncCoordinator =
    DefaultSyncCoordinator(
        liveConnection = eventStream,
        worldSignals = worldSignals,
        scope = scope,
        nowMs = nowMs,
        wait = wait,
    )

fun testSyncCoordinator(
    eventStream: FakeForegroundEventStream,
    scope: CoroutineScope,
    worldSignals: WorldSignals = FakeWorldSignals(),
    nowMs: () -> Long = { System.currentTimeMillis() },
    wait: suspend (Long) -> Unit = { delay(it) },
): DefaultSyncCoordinator =
    testSyncCoordinator(
        eventStream = eventStream as LiveConnection,
        worldSignals = worldSignals,
        scope = scope,
        nowMs = nowMs,
        wait = wait,
    )
