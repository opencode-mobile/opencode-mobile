package dev.opencode.mobile.domain.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OptimisticSessionCreateCoordinator
    @Inject
    constructor() {
        private val createFailedEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val createFailed: SharedFlow<String> = createFailedEvents.asSharedFlow()

        fun reportCreateFailed(message: String) {
            createFailedEvents.tryEmit(message)
        }
    }
