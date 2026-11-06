package dev.opencode.hexcode

import android.app.Application
import dev.opencode.hexcode.app.HexServices

class HexCodeApplication : Application() {
    val services: HexServices by lazy { HexServices(this) }

    override fun onCreate() {
        super.onCreate()
        // The speech model loads as the process starts, not when a screen opens (app-plan.md, The speech model).
        services.loadSpeechAtLaunch()
    }
}
