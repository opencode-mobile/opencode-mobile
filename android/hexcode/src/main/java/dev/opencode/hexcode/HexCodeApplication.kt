package dev.opencode.hexcode

import android.app.Application
import dev.opencode.hexcode.app.HexServices
import kotlinx.coroutines.runBlocking

/**
 * A cold start only reads what's saved on the phone before the home shows (app-plan.md, Home › Cold start): the
 * network waits for the home's first frame, and the speech model loads about 1 s after it ([HexServices.speechLaunch]).
 */
class HexCodeApplication : Application() {
    val services: HexServices by lazy { HexServices(this) }

    override fun onCreate() {
        super.onCreate()
        // The services (the HTTP clients, the stores, the speech engine's catalogue) are built beside the activity's own
        // start instead of in its onCreate on the main thread, which then only waits for what's left; and the settings
        // file (the setup, today's saved session, the saved card) is read into memory, so the splash's only wait finds it
        // ready. Nothing here reaches the network. A failure here is met again, and handled, where it's used.
        Thread({ runCatching { runBlocking { services.settings.current() } } }, "hex-services").start()
    }
}
