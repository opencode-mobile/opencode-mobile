package dev.opencode.mobile.domain.recovery

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.LiveConnection
import java.util.concurrent.CopyOnWriteArrayList

class RecordingForegroundEventStream(
    val fake: FakeForegroundEventStream = FakeForegroundEventStream(),
) : LiveConnection by fake {
    val ops: MutableList<String> = CopyOnWriteArrayList()

    override fun start() {
        ops += "start"
        fake.start()
    }

    override suspend fun stop() {
        ops += "stop"
        fake.stop()
    }
}
