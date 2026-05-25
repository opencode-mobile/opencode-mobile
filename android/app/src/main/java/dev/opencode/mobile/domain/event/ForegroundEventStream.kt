package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ForegroundEventStream {
  val connectionState: StateFlow<EventStreamConnectionState>
  val events: SharedFlow<OpencodeEvent>

  fun start()

  fun stop()
}
