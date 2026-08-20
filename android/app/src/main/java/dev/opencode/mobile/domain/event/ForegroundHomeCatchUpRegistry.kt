package dev.opencode.mobile.domain.event

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundHomeCatchUpRegistry
    @Inject
    constructor() : ForegroundHomeCatchUp {
        @Volatile
        private var handler: (suspend () -> Unit)? = null

        fun setHandler(handler: (suspend () -> Unit)?) {
            this.handler = handler
        }

        override suspend fun catchUp() {
            handler?.invoke()
        }
    }
