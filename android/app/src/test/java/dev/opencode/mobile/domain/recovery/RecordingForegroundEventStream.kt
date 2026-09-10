package dev.opencode.mobile.domain.recovery

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.ForegroundEventStream
import java.util.concurrent.CopyOnWriteArrayList

class RecordingForegroundEventStream(
    val fake: FakeForegroundEventStream = FakeForegroundEventStream(),
) : ForegroundEventStream by fake {
    val ops: MutableList<String> = CopyOnWriteArrayList()

    override fun start() {
        ops += "start"
        fake.start()
    }

    override fun stop() {
        ops += "stop"
        fake.stop()
    }
}
